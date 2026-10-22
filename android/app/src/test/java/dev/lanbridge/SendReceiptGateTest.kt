package dev.lanbridge

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SendReceiptGateTest {
    @Test fun waitsForWindowsInsteadOfTreatingWrittenBytesAsSuccess() = runBlocking {
        val gate = SendReceiptGate()
        val connection = Any()
        var written = false
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            gate.send(connection, write = {}, onWritten = { written = true })
        }
        assertTrue(written)
        assertFalse(task.isCompleted)
        gate.confirm(connection)
        task.await()
    }

    @Test fun immediateReceiptDuringWriteIsRetained() = runBlocking {
        val gate = SendReceiptGate()
        val connection = Any()
        gate.send(connection, write = { gate.confirm(connection) }, onWritten = {})
    }

    @Test fun oldConnectionCannotConfirmNewConnection() = runBlocking {
        val gate = SendReceiptGate()
        val oldConnection = Any()
        val newConnection = Any()
        val oldTask = async(start = CoroutineStart.UNDISPATCHED) {
            gate.send(oldConnection, write = {}, onWritten = {})
        }
        gate.cancel()
        val newTask = async(start = CoroutineStart.UNDISPATCHED) {
            gate.send(newConnection, write = {}, onWritten = {})
        }
        oldTask.join() // Its cleanup must not remove the new receipt.
        gate.confirm(oldConnection)
        assertFalse(newTask.isCompleted)
        gate.confirm(newConnection)
        newTask.await()
    }

    @Test fun providerOrSocketFailureReleasesReceiptSlot() = runBlocking {
        val gate = SendReceiptGate()
        val connection = Any()
        var written = false
        try {
            gate.send(connection, write = { throw IOException("read failed") }, onWritten = { written = true })
            fail("write should fail")
        } catch(_: IOException) { }
        assertFalse(written)
        gate.send(connection, write = { gate.confirm(connection) }, onWritten = {})
    }

    @Test fun timeoutDoesNotProduceSuccessAndReleasesSlot() = runBlocking {
        val gate = SendReceiptGate(timeoutMillis = 30)
        val connection = Any()
        try {
            gate.send(connection, write = {}, onWritten = {})
            fail("missing receipt should time out")
        } catch(_: TimeoutCancellationException) { }
        val nextConnection = Any()
        gate.send(nextConnection, write = { gate.confirm(nextConnection) }, onWritten = {})
    }

    @Test fun folderCommitAloneDoesNotConsumeNextFilesReceipt() = runBlocking {
        val gate = SendReceiptGate()
        val connection = Any()
        val commit = CompletableDeferred<Unit>()
        var committed = false
        val folder = async(start = CoroutineStart.UNDISPATCHED) {
            gate.send(connection, write = { commit.await(); committed = true }, onWritten = {})
        }
        commit.complete(Unit)
        yield()
        assertTrue(committed)
        assertFalse(folder.isCompleted)
        gate.confirm(connection)
        folder.await()
        val file = async(start = CoroutineStart.UNDISPATCHED) {
            gate.send(connection, write = {}, onWritten = {})
        }
        assertFalse(file.isCompleted)
        gate.confirm(connection)
        file.await()
    }
}
