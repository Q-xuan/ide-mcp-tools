package dev.feiyu.idemcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.EDT
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.*
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import javax.swing.Icon

private fun findDebug(project: Project, id: String): XDebugSession =
    XDebuggerManager.getInstance(project).debugSessions.singleOrNull { processSessionId(it.debugProcess.processHandler) == id }
        ?: mcpFail("调试会话不存在，请重新列出会话：$id")

private fun debugInfo(session: XDebugSession) = buildJsonObject {
    put("sessionId", processSessionId(session.debugProcess.processHandler))
    put("configurationName", session.sessionName)
    put("state", when {
        session.debugProcess.processHandler.isProcessTerminated -> "terminated"
        session.isSuspended -> "paused"
        else -> "running"
    })
    session.currentPosition?.let {
        put("file", it.file.path)
        put("line", it.line + 1)
    }
}

private fun paused(session: XDebugSession) {
    if (!session.isSuspended) mcpFail("调试会话未暂停，请先 pause 或等待断点")
}

private suspend fun <T> withPausedRead(session: XDebugSession, block: suspend (PausedDebugRead) -> T): T {
    var registered: PausedDebugRead? = null
    try {
        onEdt {
            paused(session)
            val context = session.suspendContext ?: mcpFail("没有暂停上下文")
            val read = PausedDebugRead(session, context)
            registered = read
            session.addSessionListener(read.listener)
        }
        val read = checkNotNull(registered)
        return read.run {
            val value = block(read)
            onEdt { read.check() }
            value
        }
    } finally {
        registered?.let { read ->
            withContext(NonCancellable + Dispatchers.EDT) { session.removeSessionListener(read.listener) }
        }
    }
}

// 直接把 XDebugger 的回调转为有界协程，不保存帧、变量或第二份会话状态。
private suspend fun frames(read: PausedDebugRead, limit: Int): List<XStackFrame> = withTimeout(5000) {
    val result = CompletableDeferred<List<XStackFrame>>(currentCoroutineContext()[Job])
    val collected = mutableListOf<XStackFrame>()
    onEdt {
        read.check()
        val stack = read.context.activeExecutionStack ?: mcpFail("没有当前执行线程")
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun isObsolete() = !result.isActive || read.isObsolete
            override fun errorOccurred(errorMessage: String) { result.completeExceptionally(IllegalStateException(errorMessage)) }
            override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                synchronized(collected) {
                    collected.addAll(stackFrames.take((limit - collected.size).coerceAtLeast(0)))
                    if (last || collected.size >= limit) result.complete(collected.toList())
                }
            }
        })
    }
    result.await()
}

private suspend fun frame(read: PausedDebugRead, index: Int): XStackFrame {
    require(index in 0..199) { "frameIndex 必须为 0—199" }
    return frames(read, index + 1).getOrNull(index) ?: mcpFail("帧序号不存在：$index")
}

private suspend fun evaluate(read: PausedDebugRead, frame: XStackFrame, expression: String): XValue = withTimeout(5000) {
    val result = CompletableDeferred<XValue>(currentCoroutineContext()[Job])
    onEdt {
        read.check()
        val evaluator = frame.evaluator ?: mcpFail("当前调试器不支持表达式求值")
        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) { result.complete(value) }
            override fun errorOccurred(errorMessage: String) { result.completeExceptionally(IllegalStateException(errorMessage)) }
        }, frame.sourcePosition)
    }
    result.await()
}

internal fun debugChildrenNode(
    read: PausedDebugRead,
    value: XValueContainer,
    limit: Int,
    result: CompletableDeferred<List<Pair<String, XValueContainer>>>,
): XCompositeNode {
    val collected = mutableListOf<Pair<String, XValueContainer>>()
    // 复用平台节点对旧版分页回调的转发。这里只收集数据，所有回调都不操作树。
    return object : XValueContainerNode<XValueContainer>(null, null, false, value) {
        override fun isObsolete() = !result.isActive || read.isObsolete
        override fun setAlreadySorted(alreadySorted: Boolean) {}
        override fun setErrorMessage(message: String) { result.completeExceptionally(IllegalStateException(message)) }
        override fun setErrorMessage(message: String, link: XDebuggerTreeNodeHyperlink?) { setErrorMessage(message) }
        override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) { setErrorMessage(message) }
        override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {
            synchronized(collected) { result.complete(collected.take(limit)) }
        }
        override fun addChildren(list: XValueChildrenList, last: Boolean) {
            synchronized(collected) {
                collected.addAll(list.topGroups.map { it.name to it })
                for (i in 0 until list.size()) if (collected.size < limit) collected.add(list.getName(i) to list.getValue(i))
                collected.addAll(list.bottomGroups.map { it.name to it })
                if (last || collected.size >= limit) result.complete(collected.take(limit))
            }
        }
    }
}

private suspend fun children(read: PausedDebugRead, value: XValueContainer, limit: Int): List<Pair<String, XValueContainer>> = withTimeout(5000) {
    val result = CompletableDeferred<List<Pair<String, XValueContainer>>>(currentCoroutineContext()[Job])
    val node = debugChildrenNode(read, value, limit, result)
    onEdt { read.check(); value.computeChildren(node) }
    result.await()
}

private suspend fun presentation(read: PausedDebugRead, value: XValue): JsonObject = withTimeout(5000) {
    val result = CompletableDeferred<JsonObject>(currentCoroutineContext()[Job])
    onEdt {
        read.check()
        value.computePresentation(object : XValueNode {
            override fun isObsolete() = !result.isActive || read.isObsolete
            override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) {}
            override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) {
                result.complete(buildJsonObject {
                    put("type", type)
                    put("value", text.take(4096))
                    put("hasChildren", hasChildren)
                })
            }
            override fun setPresentation(icon: Icon?, valuePresentation: XValuePresentation, hasChildren: Boolean) {
                setPresentation(icon, valuePresentation.type, XValuePresentationUtil.computeValueText(valuePresentation), hasChildren)
            }
        }, XValuePlace.TREE)
    }
    result.await()
}

class DebugToolset : McpToolset {
    override fun isExperimental() = false

    @McpTool
    @McpDescription("用已有运行配置启动 Debug 会话，最多等待60秒。已有同名活进程时报错；启动超时后先列会话确认，不自动重试。")
    suspend fun start_debug_session(@McpDescription("精确运行配置名") configurationName: String): String =
        launchDebug(currentCoroutineContext().project, configurationName).toString()

    @McpTool
    @McpToolHints(readOnlyHint = TRUE)
    @McpDescription("列出当前项目的调试会话及其精确 sessionId、运行/暂停状态和当前源码位置。")
    suspend fun list_debug_sessions(): String {
        val project = currentCoroutineContext().project
        return onEdt { JsonArray(XDebuggerManager.getInstance(project).debugSessions.map(::debugInfo)).toString() }
    }

    @McpTool
    @McpDescription("控制精确调试会话：pause、resume、step_over、step_into、step_out、wait_for_pause、stop。单步和恢复要求已暂停。返回观察到的状态；命令发出不等于已暂停。stop 复用正常退出并等待。")
    suspend fun control_debug_session(
        @McpDescription("来自 list_debug_sessions 的精确 ID") sessionId: String,
        @McpDescription("pause/resume/step_over/step_into/step_out/wait_for_pause/stop") action: String,
        @McpDescription("wait_for_pause/stop 的等待毫秒数，默认10000，最大60000") timeoutMs: Int = 10_000,
    ): String {
        require(timeoutMs in 1..60_000)
        val project = currentCoroutineContext().project
        if (action == "stop") return stopRun(project, sessionId, false, timeoutMs).toString()
        if (action == "resume") {
            val (session, context) = onEdt {
                val session = findDebug(project, sessionId).also(::paused)
                session to session.suspendContext
            }
            project.getService(DebugStopGuard::class.java)?.prepareResume(session)
            return onEdt {
                paused(session)
                if (session.suspendContext !== context) mcpFail("暂停位置已变化，请重新查看调试会话")
                session.resume()
                debugInfo(session).toString()
            }
        }
        val session = onEdt {
            findDebug(project, sessionId).also { s ->
                when (action) {
                    "pause" -> if (!s.isSuspended) s.pause()
                    "step_over" -> { paused(s); s.stepOver(false) }
                    "step_into" -> { paused(s); s.stepInto() }
                    "step_out" -> { paused(s); s.stepOut() }
                    "wait_for_pause" -> Unit
                    else -> mcpFail("未知调试动作：$action")
                }
            }
        }
        if (action == "wait_for_pause") withTimeoutOrNull(timeoutMs.toLong()) {
            while (onEdt { !session.isSuspended && !session.debugProcess.processHandler.isProcessTerminated }) delay(50)
        }
        return onEdt { debugInfo(session).toString() }
    }

    @McpTool
    @McpToolHints(readOnlyHint = TRUE)
    @McpDescription("读取已暂停调试会话当前线程的调用帧。index 从0开始，源码行号从1开始。最多200帧，等待最多5秒；恢复后重新读取。")
    suspend fun get_debug_stack(
        @McpDescription("精确调试会话 ID") sessionId: String,
        @McpDescription("最多返回的帧数，默认50，最大200") limit: Int = 50,
    ): String {
        require(limit in 1..200)
        val project = currentCoroutineContext().project
        val session = onEdt { findDebug(project, sessionId) }
        return withPausedRead(session) { read ->
            val frames = frames(read, limit)
            onEdt {
                read.check()
                JsonArray(frames.mapIndexed { i, f -> buildJsonObject {
                    put("index", i)
                    put("name", SimpleColoredText().also(f::customizePresentation).toString())
                    f.sourcePosition?.let { put("file", it.file.path); put("line", it.line + 1) }
                } }).toString()
            }
        }
    }

    @McpTool
    @McpDescription("读取暂停帧的局部变量。expression 非空时展开该表达式的子项；表达式求值可能调用函数，应只传变量/成员访问。最多100项，总等待最多10秒。")
    suspend fun get_debug_variables(
        @McpDescription("精确调试会话 ID") sessionId: String,
        @McpDescription("本次暂停下的帧序号，默认0") frameIndex: Int = 0,
        @McpDescription("展开变量的表达式；默认null读取帧局部变量") expression: String? = null,
        @McpDescription("最多返回项数，默认50，最大100") limit: Int = 50,
    ): String = withTimeout(10_000) {
        require(limit in 1..100)
        val project = currentCoroutineContext().project
        val session = onEdt { findDebug(project, sessionId) }
        withPausedRead(session) { read ->
            val frame = frame(read, frameIndex)
            val target = if (expression == null) frame else evaluate(read, frame, expression)
            val values = children(read, target, limit).flatMap { (name, value) ->
                if (value is XValueGroup) children(read, value, limit) else listOf(name to value)
            }.take(limit)
            JsonArray(values.map { (name, value) -> buildJsonObject {
                put("name", name)
                if (value is XValue) for ((key, item) in presentation(read, value)) put(key, item)
            } }).toString()
        }
    }

    @McpTool
    @McpDescription("在暂停帧执行调试表达式并返回值。表达式可调用函数或改变运行态；求值等待最多5秒，超时不保证目标函数已停止，禁止自动重试有副作用的表达式。")
    suspend fun evaluate_debug_expression(
        @McpDescription("精确调试会话 ID") sessionId: String,
        @McpDescription("调试器支持的表达式") expression: String,
        @McpDescription("本次暂停下的帧序号，默认0") frameIndex: Int = 0,
    ): String {
        val project = currentCoroutineContext().project
        val session = onEdt { findDebug(project, sessionId) }
        return withPausedRead(session) { read ->
            presentation(read, evaluate(read, frame(read, frameIndex), expression)).toString()
        }
    }
}
