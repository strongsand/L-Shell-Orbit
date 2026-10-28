package io.github.strongsand.lshell.beaconprotocol

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LanRequest(val method: String, val target: String, val authorization: String, val body: String)
class LanResponse(val status: Int = 200, val json: String)

/** Small connection-close HTTP/1 server: bounded input, workers, queue and socket lifetime. */
class LanHttpServer(address: InetAddress, private val handler: (LanRequest) -> LanResponse) : AutoCloseable {
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val server = ServerSocket(0, 8, address)
    val port: Int get() = server.localPort
    private val acceptor = thread(name = "beacon-http", isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            val peer = socket.inetAddress
            if (!peer.isSiteLocalAddress && !peer.isLinkLocalAddress && !peer.isLoopbackAddress) { socket.close(); continue }
            sockets += socket
            val deadline = deadlines.schedule({ runCatching { socket.close() } }, 5, TimeUnit.SECONDS)
            try { workers.execute { try { serve(socket) } finally { deadline.cancel(false) } } } catch (_: java.util.concurrent.RejectedExecutionException) {
                deadline.cancel(false); sockets -= socket; socket.close()
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 3_000
                val input = socket.getInputStream().buffered()
                var bytes = 0
                fun line(): String {
                    val text = StringBuilder()
                    while (true) {
                        val c = input.read(); require(c >= 0 && ++bytes <= 8192)
                        if (c == 10) return text.toString().removeSuffix("\r")
                        require(c in 32..126 || c == 13); text.append(c.toChar())
                    }
                }
                val request = line().split(' ')
                require(request.size == 3 && request[0] in setOf("GET", "POST") &&
                    request[2] in setOf("HTTP/1.0", "HTTP/1.1") && request[1].startsWith("/"))
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val next = line(); if (next.isEmpty()) break
                    val colon = next.indexOf(':'); require(colon > 0)
                    val key = next.substring(0, colon).lowercase(java.util.Locale.ROOT)
                    require(key !in headers); headers[key] = next.substring(colon + 1).trim()
                }
                require("transfer-encoding" !in headers && "expect" !in headers)
                val length = headers["content-length"]?.toInt() ?: 0
                require(length in 0..2048)
                if (request[0] == "POST") require(headers["content-type"]?.substringBefore(';') == "application/x-www-form-urlencoded")
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val count = input.read(body, read, length - read); require(count > 0); read += count }
                val response = handler(LanRequest(request[0], request[1], headers["authorization"].orEmpty(), body.toString(Charsets.UTF_8)))
                respond(socket, response)
            } catch (_: Exception) { runCatching { respond(socket, LanResponse(400, "{\"ok\":false,\"code\":\"INVALID_REQUEST\"}")) } }
            finally { sockets -= socket }
        }
    }
    private fun respond(socket: Socket, response: LanResponse) {
        val body = response.json.toByteArray(Charsets.UTF_8)
        require(body.size <= 512 * 1024)
        val header = "HTTP/1.1 ${response.status} Response\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
    override fun close() {
        server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); deadlines.shutdownNow()
        acceptor.join(1000)
    }
}
