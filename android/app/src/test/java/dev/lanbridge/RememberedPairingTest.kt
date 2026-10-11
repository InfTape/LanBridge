package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class RememberedPairingTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val pair = Pairing("192.168.0.102", 42831, "0123456789ABCDEF")
    @Test fun restoresAddressAndSecretWithoutPlaintextStorage() {
        val key = key(); val encrypted = PairingEnvelope.seal(pair, key)
        assertEquals(pair, PairingEnvelope.open(encrypted, key))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(pair.code))
        assertFalse(encrypted.contentEquals(PairingEnvelope.seal(pair, key)))
    }
    @Test fun rejectsTamperedOrWrongKeyCredentials() {
        val key = key(); val encrypted = PairingEnvelope.seal(pair, key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { PairingEnvelope.open(encrypted, key) }
        assertThrows(Exception::class.java) { PairingEnvelope.open(PairingEnvelope.seal(pair, key), key()) }
    }
    @Test fun rejectsUnsupportedOrTruncatedEnvelope() {
        val key = key(); val encrypted = PairingEnvelope.seal(pair, key)
        assertThrows(Exception::class.java) { PairingEnvelope.open(encrypted.copyOf(20), key) }
        encrypted[0] = 2
        assertThrows(Exception::class.java) { PairingEnvelope.open(encrypted, key) }
    }
}
