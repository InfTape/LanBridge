package dev.lanbridge

// Accessed under TransferEngine's monitor. Every requested start must reach
// Service.startForeground before cancellation is allowed to stop the service.
internal class ForegroundStartGate {
    enum class Arrival { START, WAIT, STOP }
    private val pending = mutableSetOf<Long>()
    private var current: Long? = null
    fun request(token: Long) { pending.add(token); current = token }
    fun cancel() { current = null }
    fun arrive(token: Long): Arrival {
        val requested = pending.remove(token)
        return when {
            requested && current == token -> Arrival.START
            canStop -> Arrival.STOP
            else -> Arrival.WAIT
        }
    }
    fun complete(token: Long): Boolean {
        if(current != token) return false
        current = null
        return canStop
    }
    val canStop get() = current == null && pending.isEmpty()
    val hasPending get() = pending.isNotEmpty()
}
