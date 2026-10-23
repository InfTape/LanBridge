package dev.lanbridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Safe UI projection: pairing secrets never enter Compose or saved activity state. */
data class BoundComputer(val id: String, val name: String, val host: String)

internal data class SavedComputer(val id: String, val pairing: Pairing, val name: String = "") {
    init {
        require(id.length in 1..64)
        require(pairing == Pairing.parse("${pairing.host}:${pairing.port}", pairing.code))
        require(name == DeviceNames.normalize(name))
    }
    fun display() = BoundComputer(id, name, pairing.host)
}

internal data class ComputerBindings(val computers: List<SavedComputer> = emptyList(), val selectedId: String? = null, val defaultId: String? = selectedId) {
    init {
        require(computers.size <= 256) { "最多可保存 256 台电脑" }
        require(computers.map { it.id }.distinct().size == computers.size)
        require(computers.map { it.pairing.code }.distinct().size == computers.size)
        require(if(computers.isEmpty()) selectedId == null else computers.any { it.id == selectedId })
        require(defaultId == null || computers.any { it.id == defaultId })
    }
    val current: SavedComputer? get() = computers.find { it.id == selectedId }

    /** Authentication calls this only after the encrypted hello succeeds. */
    fun remember(pair: Pairing): ComputerBindings {
        val byCode = computers.find { it.pairing.code == pair.code }
        val byCurrentEndpoint = if(byCode == null) {
            current?.takeIf { it.pairing.host == pair.host && it.pairing.port == pair.port }
        } else null
        val byUniqueEndpoint = if(byCode == null && byCurrentEndpoint == null) {
            computers.find { it.pairing.host == pair.host && it.pairing.port == pair.port }
        } else null

        val previous = byCode ?: byCurrentEndpoint ?: byUniqueEndpoint
        val computer = previous?.copy(pairing = pair) ?: SavedComputer(UUID.randomUUID().toString(), pair)
        val updated = if(previous == null) {
            computers.filterNot { it.pairing.host == pair.host && it.pairing.port == pair.port } + computer
        } else {
            computers.map { if(it.id == computer.id) computer else it }
                .filterNot { it.id != computer.id && it.pairing.host == pair.host && it.pairing.port == pair.port }
        }
        val nextDefault = when {
            computers.isEmpty() -> computer.id
            defaultId == null -> null
            previous != null && defaultId == previous.id -> computer.id
            updated.none { it.id == defaultId } -> computer.id
            else -> defaultId
        }
        return ComputerBindings(updated, computer.id, nextDefault)
    }
    fun select(id: String): ComputerBindings {
        require(computers.any { it.id == id }) { "这台电脑已解除绑定" }
        return copy(selectedId = id)
    }
    fun rename(value: String): ComputerBindings {
        val id = requireNotNull(current) { "请先绑定电脑" }.id
        return rename(id, value)
    }
    fun rename(id: String, value: String): ComputerBindings {
        require(computers.any { it.id == id }) { "这台电脑已解除绑定" }
        val name = DeviceNames.normalize(value)
        return copy(computers = computers.map { if(it.id == id) it.copy(name = name) else it })
    }
    fun forget(): ComputerBindings {
        return selectedId?.let { remove(setOf(it)) } ?: this
    }
    fun remove(ids: Set<String>): ComputerBindings {
        require(ids.isNotEmpty() && ids.all { id -> computers.any { it.id == id } }) { "所选设备已改变，请重新选择" }
        val remaining = computers.filterNot { it.id in ids }
        val next = if(selectedId !in ids) selectedId else {
            val index = computers.indexOfFirst { it.id == selectedId }
            (remaining.getOrNull(index) ?: remaining.lastOrNull())?.id
        }
        return ComputerBindings(remaining, next, defaultId?.takeUnless { it in ids })
    }
    fun withDefault(id: String?): ComputerBindings {
        require(id == null || computers.any { it.id == id }) { "这台电脑已解除绑定" }
        return copy(defaultId = id)
    }
    fun forStartup(): ComputerBindings = defaultId?.let { select(it) } ?: this
    companion object {
        fun migrate(pair: Pairing, name: String): ComputerBindings = ComputerBindings().remember(pair).rename(name)
    }
}

/** All addresses, aliases, secrets and the last selection share one authenticated envelope. */
internal object ComputerBindingsEnvelope {
    private val aad = "LanBridge computer bindings v1".toByteArray()
    fun seal(bindings: ComputerBindings, key: SecretKey): ByteArray {
        val plain = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { out ->
                out.writeInt(bindings.computers.size)
                out.writeUTF(bindings.selectedId.orEmpty()); out.writeUTF(bindings.defaultId.orEmpty())
                for(computer in bindings.computers) {
                    out.writeUTF(computer.id); out.writeUTF(computer.name)
                    out.writeUTF(computer.pairing.host); out.writeInt(computer.pairing.port); out.writeUTF(computer.pairing.code)
                }
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
        return byteArrayOf(2) + cipher.iv + cipher.doFinal(plain)
    }
    fun open(data: ByteArray, key: SecretKey): ComputerBindings {
        require(data.size in 30..262144 && data[0] in listOf(1.toByte(), 2.toByte())) { "无效的设备绑定信息" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, 12)); cipher.updateAAD(aad)
        return DataInputStream(ByteArrayInputStream(cipher.doFinal(data, 13, data.size - 13))).use { input ->
            val count = input.readInt(); require(count in 0..256)
            val selected = input.readUTF().ifEmpty { null }
            val default = if(data[0] == 1.toByte()) selected else input.readUTF().ifEmpty { null }
            val computers = List(count) {
                val id = input.readUTF(); val name = input.readUTF(); val host = input.readUTF(); val port = input.readInt(); val code = input.readUTF()
                SavedComputer(id, Pairing.parse("$host:$port", code), name)
            }
            require(input.available() == 0)
            ComputerBindings(computers, selected, default)
        }
    }
}

internal object DeviceSwitchGuard {
    fun reason(sending: Boolean, receiving: Boolean, queued: Int): String? = when {
        sending || receiving -> "正在传输，请完成后再切换电脑"
        queued > 0 -> "有待发送内容，请发送完成后再切换电脑"
        else -> null
    }
}
