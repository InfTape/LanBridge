package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class DeviceManagementTest {
    private val a = Pairing("192.168.0.100", 42831, "0123456789ABCDEF")
    private val b = a.copy(host = "192.168.0.101", code = "FEDCBA9876543210")
    private val c = a.copy(host = "192.168.0.102", code = "1111222233334444")
    private fun book() = ComputerBindings().remember(a).rename("A").remember(b).rename("B").remember(c).rename("C")

    @Test fun firstPairIsDefaultButAddingAndSwipingDoNotReplaceIt() {
        val book = book()
        assertEquals(book.computers.first().id, book.defaultId)
        assertEquals(book.computers.last().id, book.selectedId)
        assertEquals(book.defaultId, book.select(book.computers[1].id).defaultId)
        assertEquals(book.defaultId, book.remember(b).defaultId)
    }
    @Test fun defaultAndCurrentAreSeparateAndStartupUsesOnlyExplicitDefault() {
        val book = book()
        val changed = book.withDefault(book.computers[1].id)
        assertEquals(book.selectedId, changed.selectedId)
        assertEquals(changed.defaultId, changed.forStartup().selectedId)
        val unpinned = changed.withDefault(null)
        assertNull(unpinned.defaultId)
        assertEquals(unpinned, unpinned.forStartup())
        assertNull(unpinned.remember(a).defaultId)
    }
    @Test fun unpinAndCurrentSelectionSurviveEncryption() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val original = book().withDefault(null)
        assertEquals(original, ComputerBindingsEnvelope.open(ComputerBindingsEnvelope.seal(original, key), key))
    }
    @Test fun deletingInactiveDevicesPreservesCurrentAndDoesNotChooseANewDefault() {
        val book = book()
        val removed = book.remove(setOf(book.computers.first().id, book.computers[1].id))
        assertEquals(book.current, removed.current)
        assertNull(removed.defaultId)
        assertEquals(ComputerBindings(), removed.forget())
    }
    @Test fun batchDeleteCurrentKeepsRemainingCredentialsAndDefault() {
        val book = book()
        val removed = book.remove(setOf(book.computers[1].id, book.computers[2].id))
        assertEquals(book.computers.first(), removed.current)
        assertEquals(book.defaultId, removed.defaultId)
    }
    @Test fun renamingAnInactiveDeviceDoesNotSelectOrDisconnectIt() {
        val book = book()
        val renamed = book.rename(book.computers.first().id, "工作电脑")
        assertEquals(book.selectedId, renamed.selectedId)
        assertEquals(book.defaultId, renamed.defaultId)
        assertEquals("工作电脑", renamed.computers.first().name)
        assertEquals(book.current, renamed.current)
    }
    @Test fun staleBatchAndDefaultTargetsFailWithoutPartialRemoval() {
        val book = book()
        assertThrows(IllegalArgumentException::class.java) { book.remove(setOf(book.computers.first().id, "stale")) }
        assertThrows(IllegalArgumentException::class.java) { book.remove(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { book.withDefault("stale") }
        assertThrows(IllegalArgumentException::class.java) { book.rename("stale", "错误目标") }
        assertEquals(3, book.computers.size)
    }
    @Test fun previousMultiDeviceEnvelopeMigratesLastSelectionToDefault() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val old = book()
        val plain = ByteArrayOutputStream().apply { DataOutputStream(this).use { out ->
            out.writeInt(old.computers.size); out.writeUTF(old.selectedId!!)
            old.computers.forEach {
                out.writeUTF(it.id); out.writeUTF(it.name); out.writeUTF(it.pairing.host); out.writeInt(it.pairing.port); out.writeUTF(it.pairing.code)
            }
        } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD("LanBridge computer bindings v1".toByteArray())
        val v1 = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val migrated = ComputerBindingsEnvelope.open(v1, key)
        assertEquals(old.computers, migrated.computers)
        assertEquals(old.selectedId, migrated.defaultId)
        assertEquals(old.selectedId, migrated.selectedId)
        assertEquals(migrated, ComputerBindingsEnvelope.open(ComputerBindingsEnvelope.seal(migrated, key), key))
    }
}
