package dev.feiyu.idemcp

import com.goide.dlv.DlvDebugProcess
import com.goide.dlv.protocol.DlvApi
import com.goide.dlv.protocol.DlvRequest
import com.goide.execution.application.GoApplicationConfiguration
import com.intellij.execution.DefaultExecutionTarget
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private val LOCAL_RESUME_TARGET = Key.create<ProcessHandle>("dev.feiyu.ide-mcp.localGoResumeTarget")
private val LOG = Logger.getInstance(GoDebugStopGuard::class.java)

internal class GoDebugStopGuard(private val project: Project) : DebugStopGuard {
    override suspend fun prepareResume(session: XDebugSession) {
        val process = session.debugProcess as? DlvDebugProcess ?: return
        val env = session.executionEnvironment ?: return
        if (!SystemInfo.isWindows || env.executionTarget != DefaultExecutionTarget.INSTANCE ||
            env.runProfile !is GoApplicationConfiguration) return

        // Capture the identity from this paused debugger before the RPC connection can stall.
        val state = withTimeoutOrNull(1000) { process.requestState().await() }
        val target = state?.takeUnless { it.running || it.exited }?.let {
            ProcessHandle.of(it.pid.toLong()).orElse(null)?.takeIf(::isLocalDelveChild)
        }
        process.processHandler.putUserData(LOCAL_RESUME_TARGET, target)
    }

    override fun forProcess(handler: ProcessHandler): DebugStopReadiness? {
        val process = XDebuggerManager.getInstance(project).debugSessions
            .firstOrNull { it.debugProcess.processHandler === handler }
            ?.debugProcess as? DlvDebugProcess ?: return null
        return DebugStopReadiness { stop -> stopWhenReady(process, handler, stop) }
    }

    private suspend fun stopWhenReady(process: DlvDebugProcess, handler: ProcessHandler, stop: () -> Unit) {
        val session = process.session
        val resumeVersion = AtomicLong()
        val listener = object : XDebugSessionListener {
            override fun beforeSessionResume() { resumeVersion.incrementAndGet() }
        }
        onEdt { session.addSessionListener(listener) }
        try {
            var waitedForInterrupt = false
            while (true) {
                val version = resumeVersion.get()
                val dispatched = onEdt {
                    val ready = handler.isProcessTerminating || handler.isProcessTerminated ||
                        session.isSuspended || !process.connected()
                    if (ready) stop()
                    ready
                }
                if (dispatched) return

                // XDebugger reports running before Delve receives the asynchronous continue.
                // A nonblocking backend state read confirms it without sending another halt.
                val reply = process.requestState()
                val target = handler.getUserData(LOCAL_RESUME_TARGET)
                val state = if (!waitedForInterrupt && target != null) {
                    awaitDebuggerReply(reply, 500) {
                        // A paused variable read can block the whole Delve RPC connection.
                        // Use the same graceful interrupt as GoLand on its verified local child.
                        waitedForInterrupt = true
                        val stillResuming = onEdt {
                            version == resumeVersion.get() && !session.isSuspended &&
                                !handler.isProcessTerminating && !handler.isProcessTerminated
                        }
                        if (stillResuming && isLocalDelveChild(target) &&
                            handler.replace(LOCAL_RESUME_TARGET, target, null)) {
                            try {
                                OSProcessUtil.terminateProcessGracefully(target.pid().toInt())
                                LOG.debug("Sent graceful interrupt to the local Go debug target after a stalled Delve state request")
                            } catch (e: UnsupportedOperationException) {
                                LOG.debug("Could not interrupt the local Go debug target", e)
                            }
                        }
                    }
                } else reply.await()
                if ((state?.running == true || state?.exited == true) && onEdt {
                    val sameResume = version == resumeVersion.get()
                    if (sameResume) stop()
                    sameResume
                }) return
                delay(25)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { session.removeSessionListener(listener) }
        }
    }

    private suspend fun DlvDebugProcess.requestState(): CompletableDeferred<DlvApi.DebuggerState?> {
        val result = CompletableDeferred<DlvApi.DebuggerState?>(currentCoroutineContext()[Job])
        send(DlvRequest.State(true))
            .onSuccess { result.complete(it) }
            .onError { result.complete(null) }
        return result
    }

    private fun isLocalDelveChild(target: ProcessHandle): Boolean {
        if (!target.isAlive || target.info().startInstant().isEmpty) return false
        val delve = target.parent().orElse(null) ?: return false
        if (delve.parent().orElse(null) != ProcessHandle.current()) return false
        val command = delve.info().command().orElse(null) ?: return false
        return Path.of(command) == Path.of(PathManager.getHomePath(), "plugins/go-plugin/lib/dlv/windows/dlv.exe")
    }
}
