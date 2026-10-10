package dev.raaid.hopshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.raaid.hopshare.databinding.ActivityMainBinding
import dev.raaid.hopshare.databinding.DialogNetworkBinding
import dev.raaid.hopshare.databinding.ItemRowBinding
import kotlinx.coroutines.flow.first
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
        b.editNetwork.setOnClickListener { showNetworkDialog() }
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

    // ------------------------------------------------------------------ render

    private fun render(state: HopState) {
        val running = state is HopState.Running
        b.details.isVisible = running
        b.connectedCard.isVisible = running
        b.idleHint.isVisible = !running
        b.editNetwork.isEnabled = state !is HopState.Starting

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
                b.status.text = getString(R.string.status_running, state.peers.size, state.connections)
                b.toggle.text = getString(R.string.action_stop)
                b.ssidValue.text = state.ssid
                b.passValue.text = state.passphrase
                b.proxyValue.text = state.proxyAddress
                b.pacValue.text = "http://${state.proxyAddress}/proxy.pac"
                renderConnected(state)
            }
            is HopState.Failed -> {
                b.status.text = state.message
                b.toggle.text = getString(R.string.action_start)
                renderIdleCredentials()
            }
        }
    }

    private fun renderConnected(state: HopState.Running) {
        b.peersTitle.text = getString(R.string.peers_title, state.peers.size)
        fillRows(b.peersList, state.peers.map { it.name to it.address }, R.string.peers_empty)

        val now = System.currentTimeMillis()
        b.clientsTitle.text = getString(R.string.clients_title, state.clients.size)
        fillRows(b.clientsList, state.clients.map { c ->
            val title = c.deviceHint?.let { "$it · ${c.ip}" } ?: c.ip
            val up = Formatter.formatShortFileSize(this, c.bytesUp)
            val down = Formatter.formatShortFileSize(this, c.bytesDown)
            val sub = if (c.activeConnections > 0) {
                getString(R.string.client_active, c.activeConnections, up, down)
            } else {
                val ago = DateUtils.getRelativeTimeSpanString(
                    c.lastSeenMs, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                )
                getString(R.string.client_idle, ago, up, down)
            }
            title to sub
        }, R.string.clients_empty)
    }

    private fun fillRows(container: LinearLayout, rows: List<Pair<String, String>>, emptyRes: Int) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        if (rows.isEmpty()) {
            val row = ItemRowBinding.inflate(inflater, container, true)
            row.rowTitle.isVisible = false
            row.rowSubtitle.text = getString(emptyRes)
            return
        }
        rows.forEach { (title, sub) ->
            val row = ItemRowBinding.inflate(inflater, container, true)
            row.rowTitle.text = title
            row.rowSubtitle.text = sub
        }
    }

    /** Show the saved network name/password even before starting, so the user can pre-share it. */
    private fun renderIdleCredentials() {
        val c = Credentials.load(this)
        b.idleHint.text = getString(R.string.idle_hint, c.ssid, c.passphrase)
    }

    // ---------------------------------------------------------- edit network

    private fun showNetworkDialog() {
        val d = DialogNetworkBinding.inflate(layoutInflater)
        val current = Credentials.load(this)
        d.nameInput.setText(current.name)
        d.passInput.setText(current.passphrase)
        d.nameInput.doAfterTextChanged { d.nameLayout.error = null }
        d.passInput.doAfterTextChanged { d.passLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_network_title)
            .setView(d.root)
            .setPositiveButton(R.string.action_save, null)
            .setNeutralButton(R.string.action_random_password, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            // Override so invalid input keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                d.passInput.setText(Credentials.randomPassphrase())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = d.nameInput.text?.toString().orEmpty().trim()
                val pass = d.passInput.text?.toString().orEmpty()
                val nameErr = Credentials.validateName(name)
                val passErr = Credentials.validatePassphrase(pass)
                d.nameLayout.error = nameErr
                d.passLayout.error = passErr
                if (nameErr == null && passErr == null) {
                    dialog.dismiss()
                    applyNetwork(name, pass)
                }
            }
        }
        dialog.show()
    }

    private fun applyNetwork(name: String, pass: String) {
        val old = Credentials.load(this)
        Credentials.save(this, name, pass)
        val changed = old.name != name.trim() || old.passphrase != pass
        if (changed && HopService.state.value is HopState.Running) {
            // The group must be recreated for new credentials to apply.
            toast(getString(R.string.network_saved_restart))
            HopService.stop(this)
            lifecycleScope.launch {
                HopService.state.first { it is HopState.Idle || it is HopState.Failed }
                startSharing()
            }
        } else {
            toast(getString(R.string.network_saved))
            renderIdleCredentials()
        }
    }

    // ----------------------------------------------------------------- utils

    private fun copy(tv: TextView) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("HopShare", tv.text))
        toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
