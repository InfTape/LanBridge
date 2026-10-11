package dev.lanbridge

import android.content.Context
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
class RememberedPairing(context: Context) {
    private val preferences = context.getSharedPreferences("trusted_pairing", Context.MODE_PRIVATE)
    private val alias = "LanBridge.remembered-pairing.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        require(create) { "绑定密钥已失效，请重新扫码" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun load(): Pairing? {
        val text = preferences.getString("credential", null) ?: return null
        return PairingEnvelope.open(Base64.getDecoder().decode(text), key(false))
    }
    @Synchronized fun save(pair: Pairing) {
        val sealed = PairingEnvelope.seal(pair, key(true))
        check(preferences.edit().putString("credential", Base64.getEncoder().encodeToString(sealed)).commit()) { "无法保存绑定信息" }
    }
    @Synchronized fun forget() {
        check(preferences.edit().clear().commit()) { "无法移除绑定信息" }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if(containsAlias(alias)) deleteEntry(alias) }
    }
}
