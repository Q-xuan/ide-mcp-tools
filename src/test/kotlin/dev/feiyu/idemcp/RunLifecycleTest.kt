package dev.feiyu.idemcp

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.KillableProcess
import com.intellij.mcpserver.McpExpectedError
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream

class RunLifecycleTest {
    private open class TestProcess : ProcessHandler() {
        init { startNotify() }
        override fun destroyProcessImpl() { finish() }
        override fun detachProcessImpl() { notifyProcessDetached() }
        override fun detachIsDefault() = false
        override fun getProcessInput(): OutputStream? = null
        fun finish() { notifyProcessTerminated(0) }
    }

    private class KillableTestProcess(private val supported: Boolean) : TestProcess(), KillableProcess {
        var kills = 0
        override fun canKillProcess() = supported
        override fun killProcess() { kills++; finish() }
    }

    @Test fun `force stop supports a wrapper implementing KillableProcess`() {
        val process = KillableTestProcess(true)
        forceStop(process)
        assertEquals(1, process.kills)
        assertTrue(process.isProcessTerminated)
        forceStop(process)
        assertEquals(1, process.kills)
    }

    @Test fun `unsupported force stop does not kill the process`() {
        val process = KillableTestProcess(false)
        assertThrows(McpExpectedError::class.java) { forceStop(process) }
        assertEquals(0, process.kills)
        assertFalse(process.isProcessTerminated)
        process.finish()
    }

    @Test fun `退出前不报告已停止`() = runBlocking {
        val process = TestProcess()
        assertFalse(waitForProcessExit(process, 20))
        assertFalse(process.isProcessTerminated)
        process.finish()
    }

    @Test fun `收到真正退出才报告完成`() = runBlocking {
        val process = TestProcess()
        launch { delay(30); process.finish() }
        assertTrue(waitForProcessExit(process, 1000))
    }

    @Test fun `已经退出立即完成`() = runBlocking {
        val process = TestProcess()
        process.finish()
        assertTrue(waitForProcessExit(process, 1))
    }

    @Test fun `调用取消不吞掉取消也不顺带杀进程`() = runBlocking {
        val process = TestProcess()
        val pending = async { waitForProcessExit(process, 60_000) }
        yield()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(process.isProcessTerminated)
        process.finish()
    }

    @Test fun `同一进程的会话标识保持一致`() {
        val process = TestProcess()
        assertEquals(processSessionId(process), processSessionId(process))
        process.finish()
    }

    @Test fun `新进程不能复用旧会话标识`() {
        val previous = TestProcess()
        val previousId = processSessionId(previous)
        previous.finish()
        val current = TestProcess()
        assertNotEquals(previousId, processSessionId(current))
        current.finish()
    }

    @Test fun `进程退出后保留原标识用于退出回包`() {
        val process = TestProcess()
        val id = processSessionId(process)
        process.finish()
        assertEquals(id, processSessionId(process))
    }
}
