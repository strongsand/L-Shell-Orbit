package io.github.strongsand.lshell.beaconprotocol

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanHttpCompatibilityTest {
    @Test fun closingServerClosesAllConcurrentlyTrackedConnections() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val loopback = InetAddress.getLoopbackAddress()
        val server = LanHttpServer(loopback) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            LanResponse(json = "{}")
        }
        val clients = mutableListOf<Socket>()
        try {
            repeat(2) {
                clients += Socket(loopback, server.port).apply {
                    soTimeout = 4000
                    getOutputStream().write("GET /v1/info HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                }
            }
            assertTrue("Both workers must hold a tracked socket", entered.await(3, TimeUnit.SECONDS))
            server.close()
            clients.forEach { socket ->
                try { assertEquals(-1, socket.getInputStream().read()) }
                catch (_: SocketException) { /* A reset also proves the peer was closed. A read timeout must fail. */ }
            }
        } finally {
            release.countDown()
            clients.forEach { it.close() }
            server.close()
        }
    }
}
