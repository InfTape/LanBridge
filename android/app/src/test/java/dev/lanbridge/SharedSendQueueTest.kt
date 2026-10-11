package dev.lanbridge

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedSendQueueTest {
    @Test fun waitsForPairingAndDoesNotResendOnReconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val ready = MutableStateFlow(false)
            val starts = Channel<String>(Channel.UNLIMITED)
            val queue = SharedSendQueue<String>(scope, { ready.first { it } }, { item -> starts.trySend(item); Job().apply { complete() } })
            queue.enqueue("shared file")
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            assertEquals(1,queue.pending.value)
            ready.value = true
            assertEquals("shared file",withTimeout(2000) { starts.receive() })
            withTimeout(2000) { queue.pending.first { it == 0 } }
            ready.value = false; yield(); ready.value = true
            assertNull(withTimeoutOrNull(100) { starts.receive() })
        } finally { scope.cancel() }
    }
    @Test fun queuesNewSharesDuringTransmissionAndKeepsSeparateIdenticalShares() = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val ready = MutableStateFlow(true)
            val finishFirst = CompletableDeferred<Unit>()
            val starts = Channel<String>(Channel.UNLIMITED)
            val queue = SharedSendQueue<String>(scope, { ready.first { it } }, { item ->
                ready.value = false
                starts.trySend(item)
                scope.launch { if(item == "first") finishFirst.await(); ready.value = true }
            })
            queue.enqueue("first")
            assertEquals("first",withTimeout(2000) { starts.receive() })
            queue.enqueue("same file"); queue.enqueue("same file")
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            assertEquals(2,queue.pending.value)
            finishFirst.complete(Unit)
            assertEquals("same file",withTimeout(2000) { starts.receive() })
            assertEquals("same file",withTimeout(2000) { starts.receive() })
            withTimeout(2000) { queue.pending.first { it == 0 } }
            assertNull(withTimeoutOrNull(100) { starts.receive() })
        } finally { scope.cancel() }
    }
    @Test fun retainsRequestIfConnectionIsLostBeforeSenderAcceptsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val ready = MutableStateFlow(true)
            val attempts = AtomicInteger()
            val starts = Channel<String>(Channel.UNLIMITED)
            val rejected = CompletableDeferred<Unit>()
            val queue = SharedSendQueue<String>(scope, { ready.first { it } }, { item ->
                if(attempts.incrementAndGet() == 1) { ready.value = false; rejected.complete(Unit); null }
                else { starts.trySend(item); Job().apply { complete() } }
            })
            queue.enqueue("retained")
            withTimeout(2000) { rejected.await() }
            assertEquals(1,queue.pending.value)
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            ready.value = true
            assertEquals("retained",withTimeout(2000) { starts.receive() })
            withTimeout(2000) { queue.pending.first { it == 0 } }
            assertNull(withTimeoutOrNull(100) { starts.receive() })
        } finally { scope.cancel() }
    }
}
