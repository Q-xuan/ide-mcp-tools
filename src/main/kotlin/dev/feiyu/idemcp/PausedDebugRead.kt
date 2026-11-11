package dev.feiyu.idemcp

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

internal class PausedDebugRead(val session: XDebugSession, val context: XSuspendContext) {
    private val invalidated = CompletableDeferred<Unit>()
    val isObsolete: Boolean get() = invalidated.isCompleted
    val listener = object : XDebugSessionListener {
        override fun beforeSessionResume() { invalidated.complete(Unit) }
        override fun sessionStopped() { invalidated.complete(Unit) }
    }

    // Inspect the IDE model on the EDT. Callbacks only read isObsolete.
    fun check() {
        if (isObsolete || !session.isSuspended || session.suspendContext !== context)
            throw IllegalStateException("暂停状态已变化，请重新读取调试会话")
    }

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val operation = async { block() }
        try {
            select {
                invalidated.onAwait { throw IllegalStateException("暂停状态已变化，请重新读取调试会话") }
                operation.onAwait { it }
            }
        } finally {
            operation.cancel()
        }
    }
}
