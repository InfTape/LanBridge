package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class ComputerBindingsTest {
    private val office = Pairing("192.168.0.100", 42831, "0123456789ABCDEF")
    private val home = Pairing("192.168.0.101", 42831, "FEDCBA9876543210")
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun migratesLegacyCredentialAndAliasWithoutLosingIdentity() {
        val key = key()
        val old = PairingEnvelope.seal(office, key)
        val migrated = ComputerBindings.migrate(PairingEnvelope.open(old, key), "我的电脑")
        assertEquals(office, migrated.current!!.pairing)
        assertEquals("我的电脑", migrated.current!!.name)
        assertEquals(migrated, ComputerBindingsEnvelope.open(ComputerBindingsEnvelope.seal(migrated, key), key))
    }
    @Test fun addsComputersWithoutReplacingOldBindingsOrNames() {
        val first = ComputerBindings.migrate(office, "办公室")
        val both = first.remember(home).rename("家里")
        assertEquals(2, both.computers.size)
        assertEquals(home, both.current!!.pairing)
        assertEquals("办公室", both.computers.first().name)
        assertEquals("家里", both.current!!.name)
        assertEquals(first.selectedId, both.computers.first().id)
    }
    @Test fun reconnectUpdatesAddressWithoutDuplicatingOrReorderingPages() {
        val both = ComputerBindings.migrate(office, "办公室").remember(home).rename("家里")
        val moved = office.copy(host = "192.168.1.50", port = 42832)
        val updated = both.remember(moved)
        assertEquals(both.computers.map { it.id }, updated.computers.map { it.id })
        assertEquals(moved, updated.current!!.pairing)
        assertEquals("办公室", updated.current!!.name)
        assertEquals("家里", updated.computers.last().name)
    }
    @Test fun selectionAndAliasesAreIndependentAndSurviveEncryptedRoundTrip() {
        val both = ComputerBindings.migrate(office, "办公室").remember(home).rename("家里")
        val selected = both.select(both.computers.first().id).rename("  工作电脑  ")
        val key = key()
        val restored = ComputerBindingsEnvelope.open(ComputerBindingsEnvelope.seal(selected, key), key)
        assertEquals(selected, restored)
        assertEquals("工作电脑", restored.current!!.name)
        assertEquals("家里", restored.computers.last().name)
        assertEquals("", restored.rename("").current!!.name)
    }
    @Test fun forgettingOneComputerRetainsOthersAndTheirUsableCredentials() {
        val both = ComputerBindings.migrate(office, "办公室").remember(home).rename("家里")
        val removed = both.select(both.computers.first().id).forget()
        assertEquals(1, removed.computers.size)
        assertEquals(both.computers.last(), removed.current)
        val key = key()
        assertEquals(removed, ComputerBindingsEnvelope.open(ComputerBindingsEnvelope.seal(removed, key), key))
        assertEquals(ComputerBindings(), removed.forget())
    }
    @Test fun forgettingMiddleSelectsNextAndRebindingStartsWithDefaultAlias() {
        val third = office.copy(host = "192.168.0.102", code = "1111222233334444")
        val all = ComputerBindings.migrate(office, "办公室").remember(home).rename("家里").remember(third)
        val removed = all.select(all.computers[1].id).forget()
        assertEquals(all.computers.last().id, removed.selectedId)
        val rebound = removed.remember(home)
        assertEquals("", rebound.current!!.name)
        assertNotEquals(all.computers[1].id, rebound.selectedId)
    }
    @Test fun rePairingSameEndpointUpdatesCredentialAndPreservesDefaultWithoutDuplication() {
        val first = ComputerBindings.migrate(office, "办公室")
        assertEquals(1, first.computers.size)
        assertEquals(first.computers.first().id, first.defaultId)

        // PC regenerates pairing code; host and port remain the same
        val renewed = office.copy(code = "9999888877776666")
        val updated = first.remember(renewed)

        // Must update existing computer, preserve ID, custom name and defaultId without duplicating
        assertEquals(1, updated.computers.size)
        assertEquals(first.computers.first().id, updated.computers.first().id)
        assertEquals("办公室", updated.current!!.name)
        assertEquals(renewed, updated.current!!.pairing)
        assertEquals(first.defaultId, updated.defaultId)
        assertEquals(first.defaultId, updated.forStartup().selectedId)
    }
    @Test fun rePairingPrunesOrphanGhostsAndMigratesDefunctDefault() {
        val oldA = SavedComputer("id-1", office, "我的电脑")
        // Stale record created by old bug: same host & port, but different defunct pairing code
        val oldGhost = SavedComputer("id-ghost", office.copy(code = "0000000000000000"), "重命名电脑")
        val homePc = SavedComputer("id-home", home, "家里")
        // defaultId was pointing to the ghost
        val corrupted = ComputerBindings(listOf(oldA, oldGhost, homePc), selectedId = "id-1", defaultId = "id-ghost")

        // Now re-pairing office with new code
        val renewed = office.copy(code = "1111111111111111")
        val healed = corrupted.remember(renewed)

        // The ghost must be pruned, only id-1 and id-home remain
        assertEquals(2, healed.computers.size)
        assertEquals(setOf("id-1", "id-home"), healed.computers.map { it.id }.toSet())
        assertEquals("我的电脑", healed.current!!.name)
        assertEquals(renewed, healed.current!!.pairing)
        // defaultId must be self-healed and migrated to id-1
        assertEquals("id-1", healed.defaultId)
        assertEquals("id-1", healed.forStartup().selectedId)
    }
    @Test fun repeatedFastSwitchesPreserveAllCredentialsAndLastSelection() {
        var book = ComputerBindings.migrate(office, "办公室").remember(home).rename("家里")
        val ids = book.computers.map { it.id }
        val original = book.computers
        repeat(100) { book = book.select(ids[it % 2]) }
        assertEquals(original, book.computers)
        assertEquals(ids[1], book.selectedId)
        assertThrows(IllegalArgumentException::class.java) { book.select("removed-device") }
    }
    @Test fun newEnvelopeHidesEverySecretAndRejectsTamperingWrongKeyAndLegacyFormat() {
        val key = key()
        val book = ComputerBindings.migrate(office, "办公室").remember(home)
        val encrypted = ComputerBindingsEnvelope.seal(book, key)
        val text = String(encrypted, Charsets.ISO_8859_1)
        assertFalse(text.contains(office.code)); assertFalse(text.contains(home.code))
        assertFalse(encrypted.contentEquals(ComputerBindingsEnvelope.seal(book, key)))
        assertThrows(Exception::class.java) { ComputerBindingsEnvelope.open(encrypted, key()) }
        val damaged = encrypted.copyOf(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { ComputerBindingsEnvelope.open(damaged, key) }
        assertThrows(Exception::class.java) { ComputerBindingsEnvelope.open(PairingEnvelope.seal(office, key), key) }
        assertThrows(Exception::class.java) { ComputerBindingsEnvelope.open(encrypted.copyOf(20), key) }
        val unsupported = encrypted.copyOf(); unsupported[0] = 3
        assertThrows(Exception::class.java) { ComputerBindingsEnvelope.open(unsupported, key) }
    }
    @Test fun rejectsDuplicateOrInvalidSelections() {
        val one = ComputerBindings().remember(office)
        assertThrows(IllegalArgumentException::class.java) { ComputerBindings(one.computers, "not-there") }
        assertThrows(IllegalArgumentException::class.java) { ComputerBindings(one.computers + one.computers, one.selectedId) }
        assertThrows(IllegalArgumentException::class.java) { SavedComputer("", office) }
    }
    @Test fun locksSwitchingDuringTransfersAndQueuedShares() {
        assertNotNull(DeviceSwitchGuard.reason(sending = true, receiving = false, queued = 0))
        assertNotNull(DeviceSwitchGuard.reason(sending = false, receiving = true, queued = 0))
        assertNotNull(DeviceSwitchGuard.reason(sending = false, receiving = false, queued = 1))
        assertNull(DeviceSwitchGuard.reason(sending = false, receiving = false, queued = 0))
        assertTrue(TransferState(incoming = ReceiveProgress("a.bin", 0, "下载/LanBridge").saving()).deviceSwitchBlocked)
        assertTrue(TransferState(pendingShares = 2).deviceSwitchBlocked)
        assertFalse(TransferState().deviceSwitchBlocked)
    }
}
