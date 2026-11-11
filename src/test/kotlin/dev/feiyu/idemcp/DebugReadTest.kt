package dev.feiyu.idemcp

import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DebugReadTest {
    private fun context() = object : XSuspendContext() {
        override fun getActiveExecutionStack(): XExecutionStack? = null
    }

    private class SessionState(var context: XSuspendContext, var suspended: Boolean = true) {
        val session = Proxy.newProxyInstance(
            XDebugSession::class.java.classLoader, arrayOf(XDebugSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isSuspended" -> suspended
                "getSuspendContext" -> context
                else -> error("Unexpected session access: ${method.name}")
            }
        } as XDebugSession
    }

    @Test fun `new pause cannot reuse the previous frame`() {
        val state = SessionState(context())
        val read = PausedDebugRead(state.session, state.context)
        read.check()
        state.context = context()
        assertThrows(IllegalStateException::class.java) { read.check() }
    }

    @Test fun `resume rejects a pending read and cancels its work`() = runBlocking {
        supervisorScope {
            val state = SessionState(context())
            val read = PausedDebugRead(state.session, state.context)
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            val operation = async {
                read.run {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
            started.await()
            read.listener.beforeSessionResume()
            try {
                withTimeout(1000) { operation.await() }
                fail("Expected an obsolete-pause error")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("暂停状态已变化"))
            }
            assertTrue(cancelled)
            assertTrue(read.isObsolete)
        }
    }

    @Test fun `stop invalidates a suspended read`() {
        val state = SessionState(context())
        val read = PausedDebugRead(state.session, state.context)
        read.listener.sessionStopped()
        assertThrows(IllegalStateException::class.java) { read.check() }
    }

    @Test fun `message callback returns the debugger error immediately`() = runBlocking {
        val state = SessionState(context())
        val read = PausedDebugRead(state.session, state.context)
        val result = CompletableDeferred<List<Pair<String, XValueContainer>>>()
        val node = debugChildrenNode(read, object : XValueContainer() {}, 10, result)
        node.setMessage("variable lookup failed", null, SimpleTextAttributes.REGULAR_ATTRIBUTES, null)
        node.addChildren(XValueChildrenList.EMPTY, true)
        try {
            withTimeout(100) { result.await() }
            fail("Expected the original debugger error")
        } catch (e: IllegalStateException) {
            assertEquals("variable lookup failed", e.message)
        }
        assertTrue(node.isObsolete)
    }

    @Test fun `successful read returns its value`() = runBlocking {
        val state = SessionState(context())
        val read = PausedDebugRead(state.session, state.context)
        assertEquals(42, read.run { read.check(); 42 })
    }

    @Test fun `caller cancellation cancels the pending read`() = runBlocking {
        val state = SessionState(context())
        val read = PausedDebugRead(state.session, state.context)
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val operation = async {
            read.run {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        started.await()
        operation.cancelAndJoin()
        assertTrue(cancelled)
    }
}
