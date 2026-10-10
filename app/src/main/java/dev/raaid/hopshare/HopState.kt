package dev.raaid.hopshare

sealed interface HopState {
    data object Idle : HopState
    data object Starting : HopState

    data class Running(
        val ssid: String,
        val passphrase: String,
        val proxyHost: String,
        val proxyPort: Int,
        val devices: Int = 0,
        val connections: Int = 0,
    ) : HopState {
        val proxyAddress get() = "$proxyHost:$proxyPort"
    }

    data class Failed(val message: String) : HopState
}
