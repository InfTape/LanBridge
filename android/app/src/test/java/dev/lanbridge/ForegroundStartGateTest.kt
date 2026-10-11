package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test

class ForegroundStartGateTest {
    @Test fun cancelledStartMustStillReachForegroundBeforeStopping() {
        val gate = ForegroundStartGate()
        gate.request(1); gate.cancel()
        assertFalse(gate.canStop)
        assertEquals(ForegroundStartGate.Arrival.STOP, gate.arrive(1))
        assertTrue(gate.canStop)
    }
    @Test fun immediateConnectionFailureStopsOnlyAfterAcknowledgement() {
        val gate = ForegroundStartGate()
        gate.request(1)
        assertFalse(gate.canStop)
        assertEquals(ForegroundStartGate.Arrival.START, gate.arrive(1))
        assertTrue(gate.complete(1))
    }
    @Test fun staleStartAndCompletionCannotStopReplacementConnection() {
        val gate = ForegroundStartGate()
        gate.request(1); gate.cancel(); gate.request(2)
        assertEquals(ForegroundStartGate.Arrival.WAIT, gate.arrive(1))
        assertFalse(gate.complete(1))
        assertEquals(ForegroundStartGate.Arrival.START, gate.arrive(2))
        assertFalse(gate.canStop)
        assertTrue(gate.complete(2))
    }
    @Test fun outOfOrderAcknowledgementsDrainBeforeStopping() {
        val gate = ForegroundStartGate()
        gate.request(1); gate.cancel(); gate.request(2)
        assertEquals(ForegroundStartGate.Arrival.START, gate.arrive(2))
        assertFalse(gate.complete(2))
        assertEquals(ForegroundStartGate.Arrival.STOP, gate.arrive(1))
    }
}
