package dev.lanbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class DiscoveryAddressesTest {
    @Test fun respectsWifiSubnetRatherThanAssumingLastOctet() {
        for((host, prefix, expected) in listOf(
            Triple("192.168.0.100", 24, "192.168.0.255"),
            Triple("192.168.2.100", 23, "192.168.3.255"),
            Triple("10.23.4.5", 16, "10.23.255.255"),
            Triple("192.168.0.101", 30, "192.168.0.103")
        )) assertEquals(expected, discoveryBroadcastAddress(InetAddress.getByName(host), prefix)?.hostAddress)
    }

    @Test fun skipsIpv6AndPrefixesWithoutUsableSubnetBroadcast() {
        assertNull(discoveryBroadcastAddress(InetAddress.getByName("fe80::1"), 64))
        val host = InetAddress.getByName("192.168.0.100")
        for(prefix in listOf(-1, 0, 31, 32, 33)) assertNull(discoveryBroadcastAddress(host, prefix))
    }
}
