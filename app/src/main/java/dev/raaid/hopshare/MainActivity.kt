package dev.raaid.hopshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.raaid.hopshare.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
            .filter { it != android.Manifest.permission.POST_NOTIFICATIONS }
        if (denied.isEmpty()) startSharing()
        else toast(getString(R.string.permission_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // targetSdk 35 is edge-to-edge; keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.toggle.setOnClickListener {
            when (HopService.state.value) {
                is HopState.Running, HopState.Starting -> HopService.stop(this)
                else -> requestStart()
            }
        }
        b.regenerate.setOnClickListener {
            Credentials.regenerate(this)
            toast(getString(R.string.regenerated))
            renderIdleCredentials()
        }
        listOf(b.ssidValue, b.passValue, b.proxyValue, b.pacValue).forEach { tv ->
            tv.setOnClickListener { copy(tv) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HopService.state.collect { render(it) }
            }
        }
    }

    private fun requestStart() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            toast(getString(R.string.wifi_off))
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
            return
        }
        val missing = HopService.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startSharing() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startSharing() = HopService.start(this)

    private fun render(state: HopState) {
        val running = state is HopState.Running
        b.details.isVisible = running
        b.idleHint.isVisible = !running
        b.regenerate.isVisible = state is HopState.Idle || state is HopState.Failed
        b.toggle.isEnabled = true

        when (state) {
            HopState.Idle -> {
                b.status.text = getString(R.string.status_idle)
                b.toggle.text = getString(R.string.action_start)
                renderIdleCredentials()
            }
            HopState.Starting -> {
                b.status.text = getString(R.string.status_starting)
                b.toggle.text = getString(R.string.action_cancel)
            }
            is HopState.Running -> {
                b.status.text = getString(R.string.status_running, state.devices, state.connections)
                b.toggle.text = getString(R.string.action_stop)
                b.ssidValue.text = state.ssid
                b.passValue.text = state.passphrase
                b.proxyValue.text = state.proxyAddress
                b.pacValue.text = "http://${state.proxyAddress}/proxy.pac"
            }
            is HopState.Failed -> {
                b.status.text = state.message
                b.toggle.text = getString(R.string.action_start)
                renderIdleCredentials()
            }
        }
    }

    /** Show the saved network name/password even before starting, so the user can pre-share it. */
    private fun renderIdleCredentials() {
        val c = Credentials.load(this)
        b.idleHint.text = getString(R.string.idle_hint, c.ssid, c.passphrase)
    }

    private fun copy(tv: TextView) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("HopShare", tv.text))
        toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
