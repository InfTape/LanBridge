package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    @Test fun parsesConnectionInfo() {
        val p = Pairing.parse("lanbridge://192.168.1.5:42831/abcdef0123456789", "")
        assertEquals("192.168.1.5", p.host); assertEquals(42831, p.port); assertEquals("ABCDEF0123456789", p.code)
    }
    @Test fun parsesManualAddress() { assertEquals(42831, Pairing.parse("10.0.0.5", "0123456789ABCDEF").port) }
    @Test fun rejectsBadInputs() {
        for((host, code) in listOf("999.1.2.3" to "0123456789ABCDEF", "10.0.0.2" to "123", "lanbridge://10.0.0.2:99999/0123456789ABCDEF" to "")) {
            assertThrows(IllegalArgumentException::class.java) { Pairing.parse(host, code) }
        }
    }
    @Test fun parsesWindowsQr() {
        assertEquals(Pairing("192.168.77.42", 42831, "ABCDEF0123456789"), Pairing.fromQr("lanbridge://192.168.77.42:42831/abcdef0123456789"))
    }
    @Test fun rejectsUnrelatedAndMalformedQr() {
        for(value in listOf("https://example.com", "192.168.1.5", "lanbridge://user@192.168.1.5:42831/0123456789ABCDEF", "lanbridge://192.168.1.5:42831/0123456789ABCDEF?extra=1", "lanbridge://192.168.1.5:42831/0123456789ABCDEF#fragment", "lanbridge://999.1.2.3:42831/0123456789ABCDEF", "lanbridge://192.168.1.5:0/0123456789ABCDEF", "lanbridge://192.168.1.5:42831/123", "lanbridge://"+"A".repeat(257))) {
            assertThrows(Exception::class.java) { Pairing.fromQr(value) }
        }
    }
}
