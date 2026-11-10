package dev.feiyu.idemcp

import com.intellij.database.mcp.toolsets.DatabaseToolset as NativeDatabaseToolset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DatabaseMetadataTest {
    private fun result() = NativeDatabaseToolset.SchemaObjectsInfo(
        NativeDatabaseToolset.SchemaInfo("", "test", true), emptyList())

    @Test fun `连续采样保留实际耗时和加载状态`() = runBlocking {
        var calls = 0
        val samples = metadataSamples(3) {
            calls++
            delay(10)
            result()
        }
        assertEquals(3, calls)
        assertEquals(3, samples.size)
        samples.forEach {
            assertTrue(it.jsonObject.getValue("nativeMetadataMs").jsonPrimitive.double >= 5.0)
            assertTrue(it.jsonObject.getValue("isIntrospected").jsonPrimitive.boolean)
            assertEquals(0, it.jsonObject.getValue("objectCount").jsonPrimitive.int)
        }
    }

    @Test fun `不合法的采样次数不执行读取`() = runBlocking {
        for (iterations in listOf(0, 6)) {
            try {
                metadataSamples(iterations) { error("不应读取") }
                fail("不应接受越界次数")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `读取失败不自动重试或返回部分成功`() = runBlocking {
        var calls = 0
        try {
            metadataSamples(3) {
                calls++
                if (calls == 2) error("读取失败")
                result()
            }
            fail("不应返回部分成功")
        } catch (error: IllegalStateException) {
            assertEquals("读取失败", error.message)
            assertEquals(2, calls)
        }
    }

    @Test(expected = CancellationException::class)
    fun `取消直接传播不转换为成功采样`() = runBlocking<Unit> {
        metadataSamples(3) { throw CancellationException("调用取消") }
    }
}
