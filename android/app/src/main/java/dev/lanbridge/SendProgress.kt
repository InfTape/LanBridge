package dev.lanbridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.util.UUID

enum class SendPhase { PREPARING, SENDING, CONFIRMING, SUCCEEDED, FAILED }
enum class SendKind(val label: String) { FILE("文件"), FOLDER("文件夹"), TEXT("文本") }

data class SendProgress(
    val id: String = UUID.randomUUID().toString(),
    val phase: SendPhase = SendPhase.PREPARING,
    val name: String = "",
    val kind: SendKind = SendKind.FILE,
    val current: Long = 0,
    val total: Long? = null,
    val itemIndex: Int = 1,
    val itemCount: Int = 1,
    val error: String = ""
) {
    val active: Boolean get() = phase != SendPhase.SUCCEEDED && phase != SendPhase.FAILED
    val fraction: Float? get() = total?.let { if(it == 0L) 1f else (current.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val percent: Int? get() = fraction?.let { (it * 100).toInt() }
}

/** Register before writing: a fast Windows receipt must not be lost.
 * Receipts have no protocol id, so only one can be outstanding on a connection.
 * A timed out sender closes its connection; old connections cannot confirm a new send.
 */
internal class SendReceiptGate(private val timeoutMillis: Long = 30_000) {
    private data class Pending(val owner: Any, val result: CompletableDeferred<Unit>)
    private var pending: Pending? = null

    suspend fun send(owner: Any, write: suspend () -> Unit, onWritten: () -> Unit) {
        currentCoroutineContext().ensureActive()
        val receipt = synchronized(this) {
            check(pending == null) { "仍在等待上一项发送确认" }
            Pending(owner, CompletableDeferred()).also { pending = it }
        }
        try {
            write()
            currentCoroutineContext().ensureActive()
            onWritten()
            withTimeout(timeoutMillis) { receipt.result.await() }
        } finally {
            synchronized(this) { if(pending === receipt) pending = null }
            receipt.result.cancel()
        }
    }

    @Synchronized fun confirm(owner: Any) {
        pending?.takeIf { it.owner === owner }?.result?.complete(Unit)
    }

    @Synchronized fun cancel() {
        pending?.result?.cancel()
        pending = null
    }
}
