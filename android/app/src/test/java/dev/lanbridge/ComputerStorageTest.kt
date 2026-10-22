package dev.lanbridge

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class ComputerStorageTest {
    private val first = Pairing("192.168.0.100", 42831, "0123456789ABCDEF")
    private val second = Pairing("192.168.0.101", 42831, "FEDCBA9876543210")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store(preferences: MemoryPreferences) = RememberedPairing(preferences) { key }

    @Test fun upgradeMigratesOldCredentialAndAliasThenRemovesLegacyKeys() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("credential", Base64.getEncoder().encodeToString(PairingEnvelope.seal(first, key)))
            .putString("device_name", "我的电脑").commit()
        val migrated = store(prefs).bindings()
        assertEquals(first, migrated.current!!.pairing)
        assertEquals("我的电脑", migrated.current!!.name)
        assertFalse(prefs.contains("credential")); assertFalse(prefs.contains("device_name"))
        assertTrue(prefs.contains("computers"))
        assertEquals(migrated, store(prefs).bindings())
    }
    @Test fun failedMigrationKeepsLegacyRecordAndCanBeRetriedAfterRestart() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("credential", Base64.getEncoder().encodeToString(PairingEnvelope.seal(first, key)))
            .putString("device_name", "旧备注").commit()
        prefs.failNextCommit = true
        assertEquals("旧备注", store(prefs).bindings().current!!.name)
        assertTrue(prefs.contains("credential")); assertFalse(prefs.contains("computers"))
        assertEquals("旧备注", store(prefs).bindings().current!!.name)
        assertFalse(prefs.contains("credential"))
    }
    @Test fun reloadRetainsAllAliasesAndLastSelectedComputer() {
        val prefs = MemoryPreferences(); val store = store(prefs)
        val one = store.save(first)
        store.renameDevice("工作电脑")
        store.save(second); store.renameDevice("家里电脑")
        val selected = store.select(one.selectedId!!)
        val reloaded = store(prefs).bindings()
        assertEquals(selected, reloaded)
        assertEquals(listOf("工作电脑", "家里电脑"), reloaded.computers.map { it.name })
        assertEquals(first, reloaded.current!!.pairing)
    }
    @Test fun storageFailureDoesNotPublishSelectionOrDeleteOtherBindings() {
        val prefs = MemoryPreferences(); val store = store(prefs)
        val firstId = store.save(first).selectedId!!
        val before = store.save(second)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.select(firstId) }
        assertEquals(before, store.bindings())
        assertEquals(before, store(prefs).bindings())
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.forget() }
        assertEquals(before, store.bindings())
    }
    @Test fun unbindingOneComputerLeavesOtherSecretsDecryptableAndLastRemovalIsEmpty() {
        val prefs = MemoryPreferences(); val store = store(prefs)
        store.save(first); store.renameDevice("保留我")
        store.save(second); store.forget()
        val reloaded = store(prefs)
        assertEquals(first, reloaded.bindings().current!!.pairing)
        assertEquals("保留我", reloaded.bindings().current!!.name)
        reloaded.forget()
        assertEquals(ComputerBindings(), store(prefs).bindings())
    }
    @Test fun corruptedNewStorageNeverFallsBackToAnOldCredential() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("computers", "broken")
            .putString("credential", Base64.getEncoder().encodeToString(PairingEnvelope.seal(first, key))).commit()
        assertThrows(Exception::class.java) { store(prefs).bindings() }
    }
    @Test fun startupSelectsDefaultAndFutureEditsUseThatSameDevice() {
        val prefs = MemoryPreferences(); val saved = store(prefs)
        val firstId = saved.save(first).selectedId!!
        saved.save(second)
        val restored = store(prefs)
        assertEquals(firstId, restored.startupBindings().selectedId)
        assertEquals(firstId, restored.bindings().selectedId)
        restored.renameDevice("默认电脑")
        assertEquals("默认电脑", restored.bindings().computers.first().name)
        assertEquals("", restored.bindings().computers.last().name)
    }
    @Test fun unpinPersistsAndFailureDoesNotClearPreviousDefault() {
        val prefs = MemoryPreferences(); val saved = store(prefs)
        val original = saved.save(first)
        saved.save(second)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { saved.setDefault(null) }
        assertEquals(original.defaultId, saved.bindings().defaultId)
        saved.setDefault(null)
        val restored = store(prefs).startupBindings()
        assertNull(restored.defaultId)
        assertEquals(second, restored.current!!.pairing)
    }
    @Test fun managementEditsInactiveDevicesAndRemovesBatchAtomically() {
        val prefs = MemoryPreferences(); val saved = store(prefs)
        val a = saved.save(first).selectedId!!
        val b = saved.save(second).selectedId!!
        saved.renameComputer(a, "另一台电脑")
        assertEquals(b, saved.bindings().selectedId)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { saved.remove(setOf(a, b)) }
        assertEquals(2, store(prefs).bindings().computers.size)
        saved.remove(setOf(a, b))
        assertEquals(ComputerBindings(), store(prefs).bindings())
    }
}

/** In-memory adapter for the production repository; Android Keystore is tested on devices. */
private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    var failNextCommit = false
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { pending[requireNotNull(key)] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[requireNotNull(key)] = values?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { pending[requireNotNull(key)] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[requireNotNull(key)] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[requireNotNull(key)] = value }
        override fun remove(key: String?) = apply { pending[requireNotNull(key)] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if(failNextCommit) { failNextCommit = false; return false }
            if(clear) values.clear()
            pending.forEach { (key, value) -> if(value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}
