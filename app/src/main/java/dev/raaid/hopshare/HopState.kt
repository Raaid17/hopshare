package dev.raaid.hopshare

sealed interface HopState {
    data object Idle : HopState
    data object Starting : HopState

    data class Running(
        val ssid: String,
        val passphrase: String,
        val proxyHost: String,
        val proxyPort: Int,
        /** Devices that joined the Wi-Fi Direct group. */
        val peers: List<Peer> = emptyList(),
        /** Devices (by IP) that have sent traffic through the proxy. */
        val clients: List<ProxyClient> = emptyList(),
    ) : HopState {
        val proxyAddress get() = "$proxyHost:$proxyPort"
        val connections get() = clients.sumOf { it.activeConnections }
    }

    data class Failed(val message: String) : HopState
}

data class Peer(val name: String, val address: String)

data class ProxyClient(
    val ip: String,
    val activeConnections: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val lastSeenMs: Long,
    /** Best guess from User-Agent, e.g. "Windows", "iPhone". */
    val deviceHint: String?,
)
