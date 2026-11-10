package dev.feiyu.idemcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import java.nio.file.Path

private fun breakpointInfo(b: XLineBreakpoint<*>) = buildJsonObject {
    put("file", b.fileUrl)
    put("line", b.line + 1)
    put("type", b.type.id)
    put("enabled", b.isEnabled)
    put("condition", b.conditionExpression?.expression)
}

class BreakpointToolset : McpToolset {
    override fun isExperimental() = false

    @McpTool
    @McpToolHints(readOnlyHint = TRUE)
    @McpDescription("列出当前项目的源码行断点及条件、启用状态；行号从1开始。")
    suspend fun list_line_breakpoints(): String {
        val project = currentCoroutineContext().project
        return onEdt {
            JsonArray(XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>().map(::breakpointInfo)).toString()
        }
    }

    @McpTool
    @McpDescription("创建或更新一个源码行断点。已有断点直接更新条件与启用状态，不做 toggle。condition=null 清除条件；不启动或恢复会话。")
    @Suppress("UNCHECKED_CAST")
    suspend fun set_line_breakpoint(
        @McpDescription("源码文件绝对路径或相对项目路径") filePath: String,
        @McpDescription("源码行号，从1开始") line: Int,
        @McpDescription("可选条件，默认null表示无条件") condition: String? = null,
        @McpDescription("是否启用，默认true") enabled: Boolean = true,
    ): String {
        require(line > 0) { "line 必须大于0" }
        val project = currentCoroutineContext().project
        val path = Path.of(filePath).let { if (it.isAbsolute) it else Path.of(project.basePath ?: mcpFail("项目没有目录")).resolve(it) }
        return onEdt {
            val file = LocalFileSystem.getInstance().findFileByPath(path.normalize().toString().replace('\\', '/'))
                ?: mcpFail("源码文件不存在：$filePath")
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val matches = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.fileUrl == file.url && it.line == line - 1 }
            if (matches.size > 1) mcpFail("该行存在多个断点，请在 IDE 中选择具体断点")
            WriteAction.compute<String, RuntimeException> {
                val breakpoint = matches.singleOrNull() ?: run {
                    val type = XDebuggerUtil.getInstance().lineBreakpointTypes
                        .filter { it.canPutAt(file, line - 1, project) }.maxByOrNull { it.priority }
                        as? XLineBreakpointType<XBreakpointProperties<*>> ?: mcpFail("该位置不能设置断点")
                    manager.addLineBreakpoint(type, file.url, line - 1, type.createBreakpointProperties(file, line - 1))
                }
                breakpoint.isEnabled = enabled
                breakpoint.setCondition(condition)
                breakpointInfo(breakpoint).toString()
            }
        }
    }

    @McpTool
    @McpDescription("删除精确源码文件和行号对应的行断点；该位置无断点时返回 removed=0。行号从1开始。")
    suspend fun remove_line_breakpoint(
        @McpDescription("源码文件绝对路径或相对项目路径") filePath: String,
        @McpDescription("源码行号，从1开始") line: Int,
    ): String {
        require(line > 0)
        val project = currentCoroutineContext().project
        val path = Path.of(filePath).let { if (it.isAbsolute) it else Path.of(project.basePath ?: mcpFail("项目没有目录")).resolve(it) }
        return onEdt {
            val file = LocalFileSystem.getInstance().findFileByPath(path.normalize().toString().replace('\\', '/'))
                ?: mcpFail("源码文件不存在：$filePath")
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val matches = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.fileUrl == file.url && it.line == line - 1 }
            WriteAction.run<RuntimeException> { matches.forEach(manager::removeBreakpoint) }
            buildJsonObject { put("removed", matches.size) }.toString()
        }
    }
}
