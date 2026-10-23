package dev.lanbridge

import java.util.UUID

enum class ReceivePhase { RECEIVING, SAVING, SUCCEEDED, FAILED }

data class ReceiveProgress(
    val name: String,
    val total: Long,
    val destination: String,
    val directory: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    val current: Long = 0,
    val phase: ReceivePhase = ReceivePhase.RECEIVING,
    val savedUri: String? = null,
    val error: String = "",
    val detail: String = ""
) : java.io.Serializable {
    init { require(total >= 0 && current in 0..total) }
    val active: Boolean get() = phase == ReceivePhase.RECEIVING || phase == ReceivePhase.SAVING
    val fraction: Float get() = if(total == 0L) 1f else (current.toDouble() / total).toFloat().coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).toInt()

    fun advance(bytes: Long): ReceiveProgress {
        if(!active) return this
        require(bytes in current..total)
        return copy(current = bytes)
    }
    fun saving(): ReceiveProgress {
        if(!active) return this
        require(current == total) { "文件尚未接收完整" }
        return copy(phase = ReceivePhase.SAVING)
    }
    fun saved(uri: String, savedName: String = name): ReceiveProgress {
        require(phase == ReceivePhase.SAVING && uri.isNotBlank())
        return copy(name = savedName, phase = ReceivePhase.SUCCEEDED, savedUri = uri)
    }
    fun failed(summary: String, details: String): ReceiveProgress =
        if(!active) this else copy(phase = ReceivePhase.FAILED, savedUri = null, error = summary, detail = details)
}
