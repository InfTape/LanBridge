package dev.lanbridge

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated envelope; the secret key never leaves Android Keystore. */
object PairingEnvelope {
    private val aad = "LanBridge remembered pairing v1".toByteArray()
    fun seal(pair: Pairing, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
        val data = "lanbridge://${pair.host}:${pair.port}/${pair.code}".toByteArray(Charsets.UTF_8)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(data)
    }
    fun open(data: ByteArray, key: SecretKey): Pairing {
        require(data.size in 30..512 && data[0] == 1.toByte()) { "无效的绑定信息" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, 12)); cipher.updateAAD(aad)
        return Pairing.fromQr(String(cipher.doFinal(data, 13, data.size-13), Charsets.UTF_8))
    }
}
internal class RememberedPairing(private val preferences: SharedPreferences, private val keyProvider: (Boolean) -> SecretKey) {
    constructor(context: Context) : this(context.getSharedPreferences("trusted_pairing", Context.MODE_PRIVATE), ::rememberedPairingKey)
    private var cached: ComputerBindings? = null
    @Synchronized fun bindings(): ComputerBindings {
        cached?.let { return it }
        val book = preferences.getString("computers", null)?.let {
            ComputerBindingsEnvelope.open(Base64.getDecoder().decode(it), keyProvider(false))
        } ?: preferences.getString("credential", null)?.let {
            val pair = PairingEnvelope.open(Base64.getDecoder().decode(it), keyProvider(false))
            val name = runCatching { DeviceNames.normalize(preferences.getString("device_name", "").orEmpty()) }.getOrDefault("")
            ComputerBindings.migrate(pair, name).also { migrated ->
                // A failed migration write leaves the old encrypted record intact.
                runCatching { persist(migrated) }
            }
        } ?: ComputerBindings()
        cached = book
        return book
    }
    private fun persist(book: ComputerBindings): ComputerBindings {
        val sealed = ComputerBindingsEnvelope.seal(book, keyProvider(true))
        check(preferences.edit().putString("computers", Base64.getEncoder().encodeToString(sealed))
            .remove("credential").remove("device_name").commit()) { "无法保存设备绑定信息" }
        cached = book
        return book
    }
    @Synchronized fun save(pair: Pairing): ComputerBindings = persist(bindings().remember(pair))
    @Synchronized fun select(id: String): ComputerBindings = persist(bindings().select(id))
    @Synchronized fun startupBindings(): ComputerBindings {
        val book = bindings(); val startup = book.forStartup()
        return if(startup == book) book else persist(startup)
    }
    @Synchronized fun setDefault(id: String?): ComputerBindings = persist(bindings().withDefault(id))
    @Synchronized fun remove(ids: Set<String>): ComputerBindings = persist(bindings().remove(ids))
    @Synchronized fun renameDevice(value: String): ComputerBindings = persist(bindings().rename(value))
    @Synchronized fun renameComputer(id: String, value: String): ComputerBindings = persist(bindings().rename(id, value))
    // Keep the shared Keystore key: removing one computer must not invalidate others.
    @Synchronized fun forget(): ComputerBindings = persist(bindings().forget())
}

private fun rememberedPairingKey(create: Boolean): SecretKey {
    val alias = "LanBridge.remembered-pairing.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    require(create) { "绑定密钥已失效，请重新扫码" }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()
}
