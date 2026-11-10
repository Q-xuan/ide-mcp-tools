package dev.feiyu.idemcp

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentManagerImpl
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

internal suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

private val SESSION_ID = Key.create<String>("dev.feiyu.ide-mcp.sessionId")
private val RUN_IDENTITY = Key.create<RunIdentity>("dev.feiyu.ide-mcp.runIdentity")

private data class RunIdentity(
    val name: String,
    val executor: Executor,
    val settings: RunnerAndConfigurationSettings?,
)

class RunIdentityListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        handler.putUserData(RUN_IDENTITY, RunIdentity(env.runProfile.name, env.executor, env.runnerAndConfigurationSettings))
    }
}

// 标识跟随 IDE 已有进程对象，不维护另一份会话表，也不以配置名代替进程身份。
internal fun processSessionId(handler: ProcessHandler): String = handler.getUserData(SESSION_ID)
    ?: handler.putUserDataIfAbsent(SESSION_ID, UUID.randomUUID().toString())

internal fun sessionId(descriptor: RunContentDescriptor): String =
    processSessionId(descriptor.processHandler ?: mcpFail("会话没有关联进程"))

internal fun runSessions(project: Project): List<RunContentDescriptor> =
    RunContentManager.getInstance(project).allDescriptors.filter { it.processHandler?.isProcessTerminated == false }

private fun runInfo(d: RunContentDescriptor, handler: ProcessHandler? = d.processHandler) = buildJsonObject {
    val identity = handler?.getUserData(RUN_IDENTITY)
    val executor = identity?.executor ?: d.attachedContent?.let(RunContentManagerImpl::getExecutorByContent)
    put("sessionId", processSessionId(handler ?: mcpFail("会话没有关联进程")))
    put("configurationName", identity?.name ?: d.displayName)
    put("state", when {
        handler.isProcessTerminated -> "terminated"
        handler.isProcessTerminating -> "terminating"
        else -> "running"
    })
    put("executors", JsonArray(listOfNotNull(executor?.id?.let(::JsonPrimitive))))
}

internal fun findRun(project: Project, id: String): RunContentDescriptor =
    runSessions(project).singleOrNull { sessionId(it) == id } ?: mcpFail("运行会话不存在，请重新列出会话：$id")

internal suspend fun waitForProcessExit(handler: ProcessHandler, timeoutMs: Int): Boolean {
    withTimeoutOrNull(timeoutMs.toLong()) {
        while (!handler.isProcessTerminated) delay(50)
    }
    return handler.isProcessTerminated
}

internal suspend fun stopRun(project: Project, id: String, force: Boolean, timeout: Int): JsonObject {
    require(timeout in 1..60_000) { "timeoutMs 必须为 1—60000" }
    val (descriptor, handler) = onEdt {
        val descriptor = findRun(project, id)
        descriptor to (descriptor.processHandler ?: mcpFail("会话没有进程"))
    }
    val requestStop = {
        if (force) {
            val killable = handler as? KillableProcessHandler ?: mcpFail("该进程不支持强制终止")
            if (!killable.canKillProcess()) mcpFail("该进程不支持强制终止")
            killable.killProcess()
        } else if (!handler.isProcessTerminating && !handler.isProcessTerminated) {
            ExecutionManagerImpl.stopProcess(handler)
        }
    }
    val guard = if (force) null else onEdt { project.getService(DebugStopGuard::class.java)?.forProcess(handler) }
    if (guard == null) {
        onEdt(requestStop)
        waitForProcessExit(handler, timeout)
    } else {
        var requested = false
        withTimeoutOrNull(timeout.toLong()) {
            guard.stopWhenReady {
                requestStop()
                requested = true
            }
            waitForProcessExit(handler, timeout)
        }
        if (!requested && !handler.isProcessTerminated)
            mcpFail("等待调试器响应超时，IDE 停止流程尚未开始，请重新列出会话")
    }
    return onEdt { runInfo(descriptor, handler) }
}

internal suspend fun launchDebug(project: Project, name: String): JsonObject {
    val settings = onEdt {
        RunManager.getInstance(project).allSettings.singleOrNull { it.name == name }
            ?: mcpFail("运行配置不存在或名称不唯一：$name")
    }
    return launch(project, settings, DefaultDebugExecutor.getDebugExecutorInstance())
}

private suspend fun launch(
    project: Project,
    settings: RunnerAndConfigurationSettings,
    executor: Executor,
): JsonObject = withTimeout(60_000) {
    withContext(Dispatchers.EDT) {
        val name = settings.name
        if (!RunManager.getInstance(project).hasSettings(settings))
            mcpFail("运行配置不存在：$name")
        if (runSessions(project).any { (it.processHandler?.getUserData(RUN_IDENTITY)?.name ?: it.displayName) == name })
            mcpFail("配置仍在运行，请先停止指定会话：$name")
        suspendCancellableCoroutine { continuation ->
            val callback = object : ProgramRunner.Callback {
                override fun processStarted(descriptor: RunContentDescriptor) {
                    descriptor.processHandler?.putUserData(RUN_IDENTITY, RunIdentity(name, executor, settings))
                    if (continuation.isActive) continuation.resume(runInfo(descriptor))
                }
                override fun processNotStarted(error: Throwable?) {
                    if (continuation.isActive) continuation.resumeWithException(error ?: IllegalStateException("IDE 未能启动配置：$name"))
                }
            }
            val environment = ExecutionEnvironmentBuilder.create(executor, settings).build(callback)
            if (ExecutionManager.getInstance(project).isStarting(environment))
                mcpFail("配置正在启动：$name")
            ProgramRunnerUtil.executeConfiguration(environment, false, true)
        }
    }
}

class RunToolset : McpToolset {
    override fun isExperimental() = false

    @McpTool
    @McpToolHints(readOnlyHint = TRUE)
    @McpDescription("列出当前项目的 Run / Debug 活会话，包含 sessionId、配置名和 running/terminating 状态。停止时传精确 sessionId。")
    suspend fun list_run_sessions(): String {
        val project = currentCoroutineContext().project
        return onEdt { JsonArray(runSessions(project).map { runInfo(it) }).toString() }
    }

    @McpTool
    @McpDescription("停止精确 Run / Debug 会话并有界等待退出。默认正常退出；force=true 才强杀。terminating 表示仍在退出。Go 调试器无响应时可能返回工具错误，请重新列出会话。")
    suspend fun stop_run_session(
        @McpDescription("来自 list_run_sessions 的精确会话 ID") sessionId: String,
        @McpDescription("是否强制终止，默认 false") force: Boolean = false,
        @McpDescription("等待退出的毫秒数，默认 10000，最大 60000") timeoutMs: Int = 10_000,
    ): String = stopRun(currentCoroutineContext().project, sessionId, force, timeoutMs).toString()

    @McpTool
    @McpDescription("重启一个已运行的精确会话，保留其配置及 Run/Debug 模式。原进程未退出就报错，不启动第二个实例。启动等待最多60秒；超时后先列会话确认，禁止盲目重试。")
    suspend fun restart_run_configuration(
        @McpDescription("来自 list_run_sessions 的精确会话 ID") sessionId: String,
        @McpDescription("等待旧进程退出的毫秒数，默认 10000，最大 60000") timeoutMs: Int = 10_000,
    ): String {
        val project = currentCoroutineContext().project
        val (settings, executor) = onEdt {
            val identity = findRun(project, sessionId).processHandler?.getUserData(RUN_IDENTITY)
            val settings = identity?.settings?.takeIf(RunManager.getInstance(project)::hasSettings)
                ?: mcpFail("会话没有可复用的运行配置")
            settings to identity.executor
        }
        val stopped = stopRun(project, sessionId, false, timeoutMs)
        if (stopped["state"]?.jsonPrimitive?.content != "terminated") mcpFail("旧进程尚未退出，未启动新实例")
        return launch(project, settings, executor).toString()
    }
}
