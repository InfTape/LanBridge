package dev.lanbridge

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryProtocolTest {
    private val code = "0123456789ABCDEF"
    private val nonce = ByteArray(16) { it.toByte() }
    private fun reply(): ByteArray {
        val tag = "569760a2f07d6d3b548dbd757da5aa5b541bcfb864501d8299c335165cf0d391".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return "LBR1".toByteArray() + nonce + byteArrayOf(0xa7.toByte(), 0x4f) + tag
    }
    @Test fun acceptsWindowsHmacAndUpdatedAddress() {
        assertEquals(Pairing("192.168.0.250", 42831, code), DiscoveryProtocol.verify(reply(), nonce, "192.168.0.250", code))
    }
    @Test fun ignoresOtherComputersTamperingAndReplayedNonce() {
        assertNull(DiscoveryProtocol.verify(reply(), nonce, "192.168.0.250", "FEDCBA9876543210"))
        assertNull(DiscoveryProtocol.verify(reply(), ByteArray(16), "192.168.0.250", code))
        val tampered = reply(); tampered[21] = 1
        assertNull(DiscoveryProtocol.verify(tampered, nonce, "192.168.0.250", code))
        assertNull(DiscoveryProtocol.verify(reply().copyOf(53), nonce, "192.168.0.250", code))
    }
    @Test fun liveWindowsDiscoveryInterop() {
        val port = System.getenv("LANBRIDGE_DISCOVERY_TEST_PORT")
        val fixtureCode = System.getenv("LANBRIDGE_TEST_CODE") ?: code
        assumeTrue("Windows UDP fixture not requested", port != null)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val request = DiscoveryProtocol.request(nonce)
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("127.0.0.1"), port!!.toInt()))
            val packet = DatagramPacket(ByteArray(256), 256); socket.receive(packet)
            assertEquals(Pairing("127.0.0.1", 42831, fixtureCode), DiscoveryProtocol.verify(packet.data.copyOf(packet.length), nonce, "127.0.0.1", fixtureCode))
        }
    }
}
