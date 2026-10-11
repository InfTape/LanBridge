package dev.lanbridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** LB01: AES-256-GCM frames, PBKDF2-HMAC-SHA256 and direction/sequence authenticated as AAD. */
class Wire(private val socket: Socket, code: String) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val key: SecretKeySpec
    private val random = SecureRandom()
    private var tx = 0L
    private var rx = 0L
    init {
        val hello = ByteArray(20).also(input::readFully)
        require(hello.copyOfRange(0, 4).contentEquals("LB01".toByteArray())) { "不是 LanBridge 服务" }
        val spec = PBEKeySpec(code.toCharArray(), hello.copyOfRange(4, 20), 120000, 256)
        key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        spec.clearPassword()
    }
    @Synchronized fun send(type: Int, data: ByteArray = byteArrayOf()) {
        require(data.size <= 262144) { "单帧太大" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(ByteBuffer.allocate(9).put(0.toByte()).putLong(tx).array())
        val encrypted = cipher.doFinal(byteArrayOf(type.toByte()) + data)
        output.writeInt(nonce.size + encrypted.size)
        output.write(nonce); output.write(encrypted); output.flush(); tx++
    }
    fun receive(): Pair<Int, ByteArray> {
        val length = input.readInt()
        require(length in 29..262173) { "无效的数据帧" }
        val packet = ByteArray(length).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packet, 0, 12))
        cipher.updateAAD(ByteBuffer.allocate(9).put(1.toByte()).putLong(rx).array())
        val plain = cipher.doFinal(packet, 12, length - 12); rx++
        return (plain[0].toInt() and 255) to plain.copyOfRange(1, plain.size)
    }
    override fun close() { socket.close() }
}

data class Pairing(val host: String, val port: Int, val code: String) {
    companion object {
        fun fromQr(value: String): Pairing {
            require(value.length <= 256 && value.startsWith("lanbridge://")) { "请扫描电脑上的 LanBridge 配对二维码" }
            val uri = java.net.URI(value)
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "二维码中的连接信息无效" }
            return parse(value, "")
        }
        fun parse(address: String, password: String): Pairing {
            var host = address.trim(); var port = 42831; var code = password.trim().uppercase()
            if (host.startsWith("lanbridge://")) {
                val uri = java.net.URI(host)
                host = uri.host ?: error("连接信息中的地址无效")
                port = if (uri.port == -1) 42831 else uri.port
                code = uri.path.removePrefix("/").uppercase()
            } else if (host.count { it == ':' } == 1) {
                port = host.substringAfter(':').toInt(); host = host.substringBefore(':')
            }
            require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) && host.split('.').all { it.toInt() in 0..255 }) { "请输入电脑的 IPv4 地址" }
            require(port in 1..65535) { "端口无效" }
            require(code.matches(Regex("[0-9A-F]{16}"))) { "配对码需要 16 位字母或数字" }
            return Pairing(host, port, code)
        }
    }
}
