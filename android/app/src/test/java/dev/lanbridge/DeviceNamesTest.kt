package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test

class DeviceNamesTest {
    private val pair = Pairing("192.168.0.100", 42831, "0123456789ABCDEF")

    @Test fun trimsNamesAndAllowsDefaultReset() {
        assertEquals("书房电脑 🖥️", DeviceNames.normalize("  书房电脑 🖥️  "))
        assertEquals("", DeviceNames.normalize("   "))
    }
    @Test fun limitsUnicodeCharactersWithoutSplittingEmoji() {
        val name = "😀".repeat(32)
        assertEquals(name, DeviceNames.normalize(name))
        assertThrows(IllegalArgumentException::class.java) { DeviceNames.normalize("😀".repeat(33)) }
        assertThrows(IllegalArgumentException::class.java) { DeviceNames.normalize("中".repeat(33)) }
    }
    @Test fun rejectsMultilineAndControlCharacters() {
        for(name in listOf("电脑\n名称", "电脑\r名称", "电脑\t名称", "电脑\u0000名称")) {
            assertThrows(IllegalArgumentException::class.java) { DeviceNames.normalize(name) }
        }
    }
    @Test fun followsBoundIdentityAcrossAddressChanges() {
        assertEquals("书房电脑", DeviceNames.retain(pair, pair.copy(host = "192.168.1.200"), "书房电脑"))
        assertEquals("书房电脑", DeviceNames.retain(pair, pair, "书房电脑"))
    }
    @Test fun newBindingsDoNotInheritAnotherComputersName() {
        assertEquals("", DeviceNames.retain(pair, pair.copy(code = "FEDCBA9876543210"), "旧电脑"))
        assertEquals("", DeviceNames.retain(null, pair, "旧电脑"))
    }
}
