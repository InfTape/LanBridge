package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class SocketRoutingTest {
    @Test fun rejectedTcpBindClosesCandidateAndConnectsUsingFreshSocket() {
        val created = mutableListOf<Socket>()
        var rejection: Exception? = null
        val routed = routeSocket({ Socket().also { created.add(it) } }, { throw SocketException("EPERM") }, { rejection = it })
        assertEquals(2, created.size)
        assertTrue(created.first().isClosed)
        assertTrue(rejection is IOException)
        assertFalse(routed.preferredNetwork)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            routed.socket.use { client ->
                client.connect(server.localSocketAddress, 1000)
                server.accept().use { peer ->
                    client.getOutputStream().write(42)
                    peer.soTimeout = 1000
                    assertEquals(42, peer.getInputStream().read())
                }
            }
        }
    }
    @Test fun rejectedUdpBindStillDiscoversOverDefaultRoute() {
        val created = mutableListOf<DatagramSocket>()
        val routed = routeSocket({ DatagramSocket().also { created.add(it) } }, { throw SecurityException("EPERM") }, {})
        assertTrue(created.first().isClosed)
        assertFalse(routed.preferredNetwork)
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            routed.socket.use { sender ->
                val nonce = ByteArray(16) { it.toByte() }
                val request = DiscoveryProtocol.request(nonce)
                sender.send(DatagramPacket(request, request.size, receiver.localSocketAddress))
                receiver.soTimeout = 1000
                val packet = DatagramPacket(ByteArray(64), 64)
                receiver.receive(packet)
                assertArrayEquals(request, packet.data.copyOf(packet.length))
            }
        }
    }
    @Test fun successfulPreferredBindingIsKept() {
        val socket = Socket()
        routeSocket({ socket }, { assertSame(socket, it) }, { fail("Unexpected rejection") }).socket.use {
            assertSame(socket, it)
            assertFalse(it.isClosed)
        }
    }
    @Test fun unrelatedProgrammingErrorIsNotHidden() {
        val socket = Socket()
        try {
            routeSocket({ socket }, { throw IllegalStateException("unexpected") }, { fail("Unexpected fallback") })
            fail("Expected error")
        } catch(_: IllegalStateException) { assertTrue(socket.isClosed) }
    }
}
