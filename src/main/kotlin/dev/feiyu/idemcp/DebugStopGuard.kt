package dev.feiyu.idemcp

import com.intellij.execution.process.ProcessHandler
import com.intellij.xdebugger.XDebugSession

internal interface DebugStopGuard {
    suspend fun prepareResume(session: XDebugSession)
    fun forProcess(handler: ProcessHandler): DebugStopReadiness?
}

internal fun interface DebugStopReadiness {
    // The callback must run on the EDT once the debugger is ready to stop.
    suspend fun stopWhenReady(stop: () -> Unit)
}
