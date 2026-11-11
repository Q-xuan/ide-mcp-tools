package dev.feiyu.idemcp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebuggerReplyTest {
    @Test fun `ready reply does not request an interrupt`() = runBlocking {
        val reply = CompletableDeferred(true)
        assertTrue(awaitDebuggerReply(reply, 100) { error("Unexpected interrupt") })
    }

    @Test fun `stalled reply triggers one recovery and returns its result`() = runBlocking {
        val reply = CompletableDeferred<Boolean>()
        var interrupts = 0
        val result = withTimeout(1000) {
            awaitDebuggerReply(reply, 1) { interrupts++; reply.complete(true) }
        }
        assertTrue(result)
        assertEquals(1, interrupts)
    }

    @Test fun `cancellation does not interrupt the target`() = runBlocking {
        val reply = CompletableDeferred<Boolean>()
        var interrupts = 0
        val waiting = async { awaitDebuggerReply(reply, 60_000) { interrupts++ } }
        yield()
        waiting.cancelAndJoin()
        assertEquals(0, interrupts)
        assertTrue(reply.complete(true))
    }

    @Test fun `backend failure is preserved without recovery`() = runBlocking {
        val reply = CompletableDeferred<Boolean>()
        reply.completeExceptionally(IllegalStateException("disconnected"))
        try {
            awaitDebuggerReply(reply, 100) { error("Unexpected interrupt") }
            fail("Expected backend failure")
        } catch (e: IllegalStateException) {
            assertEquals("disconnected", e.message)
        }
    }

    @Test fun `outer timeout ends a recovery that has not completed`() = runBlocking {
        val reply = CompletableDeferred<Boolean>()
        var interrupts = 0
        val outcome = withTimeoutOrNull(100) {
            awaitDebuggerReply(reply, 1) { interrupts++ }
        }
        assertNull(outcome)
        assertEquals(1, interrupts)
        reply.cancel()
    }
}
