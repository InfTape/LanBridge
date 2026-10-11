package dev.lanbridge

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Application-owned FIFO: wait for connection/idle, claim once, then await completion. */
class SharedSendQueue<T>(scope: CoroutineScope, awaitReady: suspend () -> Unit, start: (T) -> Job?) {
    private val requests = Channel<T>(Channel.UNLIMITED)
    private val waiting = MutableStateFlow(0)
    val pending = waiting.asStateFlow()
    init {
        scope.launch {
            for(request in requests) {
                var job: Job? = null
                while(job == null) {
                    awaitReady()
                    job = start(request)
                    if(job == null) yield() // Connection or another sender won the race; retain the request.
                }
                waiting.update { it-1 }
                job.join()
            }
        }
    }
    fun enqueue(request: T) {
        waiting.update { it+1 }
        check(requests.trySend(request).isSuccess)
    }
}
