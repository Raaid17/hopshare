package dev.raaid.hopshare

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/HTTPS forward proxy.
 *
 * - `CONNECT host:port` → raw TCP tunnel (all HTTPS traffic).
 * - `GET http://host/path` (absolute-form) → request rewritten to origin-form and relayed.
 * - Requests addressed to the proxy itself serve a help page and a PAC file at /proxy.pac.
 *
 * Outbound sockets are opened by the phone, so DNS and traffic use the phone's
 * default network (normally mobile data).
 */
class ProxyServer(
    private val bindAddress: InetAddress,
    val port: Int,
    private val onConnectionsChanged: (Int) -> Unit,
) {
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet<Socket>()
    private val active = AtomicInteger(0)
    @Volatile private var running = false
    private var server: ServerSocket? = null

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port), BACKLOG)
        server = ss
        running = true
        pool.execute { acceptLoop(ss) }
        Log.i(TAG, "Proxy listening on ${bindAddress.hostAddress}:$port")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.shutdownNow()
        active.set(0)
        onConnectionsChanged(0)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        sockets += client
        onConnectionsChanged(active.incrementAndGet())
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream(), BUF_SIZE)
            val head = readHead(input) ?: return
            val lines = head.split("\r\n", "\n")
            val parts = lines.first().split(" ")
            if (parts.size < 3) {
                respond(client, 400, "Bad Request", "Malformed request line\n")
                return
            }
            val (method, target, version) = parts
            val headers = lines.drop(1).filter { it.isNotEmpty() }

            val up = (if (method.equals("CONNECT", ignoreCase = true)) {
                openTunnel(client, target)
            } else {
                openHttp(client, method, target, version, headers)
            }) ?: return
            upstream = up

            sockets += up
            client.soTimeout = 0
            relay(client, input, up)
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            runCatching { client.close() }
            upstream?.let { runCatching { it.close() }; sockets -= it }
            sockets -= client
            onConnectionsChanged(active.decrementAndGet())
        }
    }

    /** CONNECT host:port → tunnel. Returns the upstream socket, or null after replying with an error. */
    private fun openTunnel(client: Socket, target: String): Socket? {
        val (host, port) = splitHostPort(target, 443) ?: run {
            respond(client, 400, "Bad Request", "Bad CONNECT target\n")
            return null
        }
        val upstream = connectUpstream(client, host, port) ?: return null
        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        return upstream
    }

    /** Absolute-form HTTP request → rewrite and forward the head. Body flows through relay(). */
    private fun openHttp(
        client: Socket,
        method: String,
        target: String,
        version: String,
        headers: List<String>,
    ): Socket? {
        if (target.startsWith("/")) {
            serveLocal(client, target)
            return null
        }
        val uri = runCatching { URI(target) }.getOrNull()
        if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host == null) {
            respond(client, 400, "Bad Request", "Only http:// URLs and CONNECT are supported\n")
            return null
        }
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val port = if (uri.port == -1) 80 else uri.port
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val upstream = connectUpstream(client, host, port) ?: return null

        val head = StringBuilder()
        head.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
        var hasHost = false
        for (h in headers) {
            val name = h.substringBefore(':').trim().lowercase()
            if (name in HOP_BY_HOP) continue
            if (name == "host") hasHost = true
            head.append(h).append("\r\n")
        }
        if (!hasHost) {
            head.append("Host: ").append(host)
            if (port != 80) head.append(':').append(port)
            head.append("\r\n")
        }
        // One request per connection keeps body handling simple and correct.
        head.append("Connection: close\r\n\r\n")

        upstream.getOutputStream().apply {
            write(head.toString().toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        return upstream
    }

    private fun connectUpstream(client: Socket, host: String, port: Int): Socket? {
        return try {
            Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            respond(client, 502, "Bad Gateway", "Could not reach $host:$port (${e.message})\n")
            null
        }
    }

    /** Pipe both directions until either side closes. */
    private fun relay(client: Socket, clientIn: InputStream, upstream: Socket) {
        val up = pool.submit(Runnable { pump(clientIn, upstream) })
        pump(upstream.getInputStream(), client)
        up.cancel(true)
    }

    private fun pump(from: InputStream, to: Socket) {
        val buf = ByteArray(BUF_SIZE)
        val out = to.getOutputStream()
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    /** Requests sent straight to the proxy (e.g. a browser opening http://192.168.49.1:8282/). */
    private fun serveLocal(client: Socket, path: String) {
        val self = "${client.localAddress.hostAddress}:$port"
        if (path.startsWith("/proxy.pac") || path.startsWith("/wpad.dat")) {
            val pac = """
                function FindProxyForURL(url, host) {
                  if (isPlainHostName(host) || shExpMatch(host, "192.168.49.*")) return "DIRECT";
                  return "PROXY $self";
                }
            """.trimIndent() + "\n"
            respond(client, 200, "OK", pac, "application/x-ns-proxy-autoconfig")
        } else {
            val body = """
                HopShare proxy is running.

                Manual proxy:   $self  (HTTP, also used for HTTPS)
                Auto-config:    http://$self/proxy.pac
            """.trimIndent() + "\n"
            respond(client, 200, "OK", body)
        }
    }

    private fun respond(
        client: Socket,
        code: Int,
        reason: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        }
    }

    companion object {
        private const val TAG = "HopProxy"
        private const val BACKLOG = 128
        private const val BUF_SIZE = 32 * 1024
        private const val MAX_HEAD = 64 * 1024
        private const val HEADER_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 15_000

        private val HOP_BY_HOP = setOf(
            "connection", "proxy-connection", "keep-alive",
            "proxy-authorization", "proxy-authenticate", "te", "trailer", "upgrade",
        )

        /** Reads up to the blank line ending the request head; leaves any body in the stream. */
        internal fun readHead(input: InputStream): String? {
            val out = ByteArrayOutputStream(512)
            var last4 = 0
            while (out.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                out.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A || (last4 and 0xFFFF) == 0x0A0A) {
                    return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r', '\n')
                }
            }
            return null
        }

        /** "host:port", "[v6]:port" or bare "host". */
        internal fun splitHostPort(target: String, defaultPort: Int): Pair<String, Int>? {
            if (target.startsWith("[")) {
                val end = target.indexOf(']')
                if (end < 0) return null
                val host = target.substring(1, end)
                val rest = target.substring(end + 1)
                val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() else defaultPort
                return port?.takeIf { it in 1..65535 }?.let { host to it }
            }
            val idx = target.lastIndexOf(':')
            if (idx < 0) return target to defaultPort
            val port = target.substring(idx + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return target.substring(0, idx) to port
        }
    }
}
