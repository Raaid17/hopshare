package dev.raaid.hopshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Foreground service that owns the Wi-Fi Direct group and the proxy.
 * Clients join the group like a normal Wi-Fi network, then point their proxy at us.
 */
class HopService : Service() {

    companion object {
        const val ACTION_START = "dev.raaid.hopshare.START"
        const val ACTION_STOP = "dev.raaid.hopshare.STOP"
        const val PROXY_PORT = 8282

        private const val TAG = "HopService"
        private const val CHANNEL_ID = "hopshare"
        private const val NOTIF_ID = 1
        private const val GROUP_INFO_RETRIES = 20
        private val DEFAULT_GO_ADDRESS: InetAddress = InetAddress.getByName("192.168.49.1")

        private val _state = MutableStateFlow<HopState>(HopState.Idle)
        val state: StateFlow<HopState> = _state.asStateFlow()

        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, HopService::class.java).setAction(ACTION_START)
        )

        fun stop(context: Context) {
            context.startService(Intent(context, HopService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var p2p: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var proxy: ProxyServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var active = false

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val s = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (s == WifiP2pManager.WIFI_P2P_STATE_DISABLED && active) {
                        fail("Wi-Fi was turned off")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshClients()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        p2p = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        createChannel()
        // Keep the notification in sync with state.
        scope.launch { state.collect { if (active) notify(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                _state.value = HopState.Idle
                stopSelf()
            }
            else -> if (!active) {
                begin()
            } else {
                // Already running; still satisfy the startForegroundService() contract.
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(_state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        if (_state.value !is HopState.Failed) _state.value = HopState.Idle
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- startup

    private fun begin() {
        active = true
        _state.value = HopState.Starting
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(HopState.Starting),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (!hasPermissions()) {
            fail("Permission missing — open HopShare and allow Nearby devices / Location")
            return
        }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            fail("Turn Wi-Fi on first (it doesn't need to be connected to anything)")
            return
        }

        acquireLocks(wifi)
        ContextCompat.registerReceiver(
            this, p2pReceiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        val ch = p2p.initialize(this, mainLooper) {
            if (active) fail("Wi-Fi Direct service disconnected")
        }
        channel = ch
        // Tear down any stale group (ours or another app's) before creating a new one.
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = createGroup(ch)
            override fun onFailure(reason: Int) = createGroup(ch)
        })
    }

    @SuppressLint("MissingPermission") // checked in begin()
    private fun createGroup(ch: WifiP2pManager.Channel) {
        if (!active) return
        val creds = Credentials.load(this)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(creds.ssid)
            .setPassphrase(creds.passphrase)
            .enablePersistentMode(false)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .build()

        p2p.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = awaitGroup(ch, creds, GROUP_INFO_RETRIES)
            override fun onFailure(reason: Int) = fail(describe(reason))
        })
    }

    /** createGroup() returns before the group is up; poll until it reports in. */
    @SuppressLint("MissingPermission")
    private fun awaitGroup(ch: WifiP2pManager.Channel, creds: Credentials, retries: Int) {
        if (!active) return
        p2p.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            when {
                group != null && group.isGroupOwner -> startProxy(ch, group, creds)
                retries > 0 -> handler.postDelayed({ awaitGroup(ch, creds, retries - 1) }, 500)
                else -> fail("Wi-Fi Direct group didn't come up. Try toggling Wi-Fi.")
            }
        }
    }

    private fun startProxy(ch: WifiP2pManager.Channel, group: WifiP2pGroup, creds: Credentials) {
        p2p.requestConnectionInfo(ch) { info ->
            if (!active) return@requestConnectionInfo
            val goAddress = info?.groupOwnerAddress ?: DEFAULT_GO_ADDRESS
            val server = ProxyServer(goAddress, PROXY_PORT) { n ->
                _state.update { if (it is HopState.Running) it.copy(connections = n) else it }
            }
            // Binding can fail for a moment while the p2p interface is still configuring.
            Thread {
                var lastError: Exception? = null
                repeat(10) {
                    try {
                        server.start()
                        handler.post {
                            if (!active) { server.stop(); return@post }
                            proxy = server
                            _state.value = HopState.Running(
                                ssid = group.networkName ?: creds.ssid,
                                passphrase = group.passphrase ?: creds.passphrase,
                                proxyHost = goAddress.hostAddress ?: "192.168.49.1",
                                proxyPort = PROXY_PORT,
                                devices = group.clientList.size,
                            )
                        }
                        return@Thread
                    } catch (e: Exception) {
                        lastError = e
                        Thread.sleep(500)
                    }
                }
                handler.post { fail("Couldn't start proxy: ${lastError?.message}") }
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshClients() {
        val ch = channel ?: return
        if (!active || !hasPermissions()) return
        p2p.requestGroupInfo(ch) { group ->
            val count = group?.clientList?.size ?: 0
            _state.update { if (it is HopState.Running) it.copy(devices = count) else it }
        }
    }

    // --------------------------------------------------------------- teardown

    private fun fail(message: String) {
        Log.w(TAG, message)
        shutdown()
        _state.value = HopState.Failed(message)
        stopSelf()
    }

    private fun shutdown() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        proxy?.stop()
        proxy = null
        channel?.let { ch ->
            p2p.removeGroup(ch, null)
            ch.close()
        }
        channel = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ---------------------------------------------------------------- helpers

    private fun hasPermissions() = requiredPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS } // optional
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @Suppress("DEPRECATION") // HIGH_PERF still keeps the radio awake with the screen off
    private fun acquireLocks(wifi: WifiManager) {
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HopShare:wifi")
            .apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HopShare:cpu")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun describe(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "This phone doesn't support Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Turn off the phone's own hotspot and try again."
        else -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "Couldn't create group (error $reason). Make sure Location is turned on."
        } else {
            "Couldn't create group (error $reason). Try toggling Wi-Fi."
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notify(state: HopState) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(state: HopState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (state) {
            is HopState.Running -> state.ssid to
                "Proxy ${state.proxyAddress} · ${state.devices} device(s) · ${state.connections} conn"
            else -> getString(R.string.app_name) to getString(R.string.status_starting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hop)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
