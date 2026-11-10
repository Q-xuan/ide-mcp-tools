package dev.feiyu.idemcp

import com.intellij.database.mcp.toolsets.DatabaseToolset as NativeDatabaseToolset
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.mcpFail
import kotlinx.serialization.json.*

internal suspend fun metadataSamples(
    iterations: Int,
    read: suspend () -> NativeDatabaseToolset.SchemaObjectsInfo,
): JsonArray {
    require(iterations in 1..5) { "iterations 必须为 1—5" }
    return buildJsonArray {
        repeat(iterations) {
            val start = System.nanoTime()
            val result = read()
            val nativeMs = (System.nanoTime() - start) / 1_000_000.0
            add(buildJsonObject {
                put("nativeMetadataMs", nativeMs)
                put("objectCount", result.schemaObjects.size)
                put("isIntrospected", result.schemaInfo.isIntrospected)
            })
        }
    }
}

/** 数据库连接、权限确认、事务和查询会话都由 Database Tools 持有。 */
class DatabaseToolset : McpToolset {
    override fun isExperimental() = false
    override fun isEnabled() = NativeDatabaseToolset().isEnabled()

    @McpTool
    @McpToolHints(readOnlyHint = TRUE, destructiveHint = FALSE, idempotentHint = TRUE)
    @McpDescription("只读诊断已知库的元数据调用耗时：一次 MCP 调用内连续读取1—5次集合/表列表，不主动刷新、不新建连接。复用 IDE 原生权限检查与元数据模型。nativeMetadataMs 包含原生数据源查找、权限确认和模型读取，不能视为数据库网络耗时；totalMs 仅为工具内采样耗时，不含外部排队、协议序列化和传输。仅返回数量、加载状态与耗时，不返回数据记录。")
    suspend fun probe_database_metadata(
        @McpDescription("已有数据库连接的精确 ID") connectionId: String,
        @McpDescription("已知的 schema/库名；MongoDB 在这里传库名") schemaName: String,
        @McpDescription("数据库名；驱动不使用该层时为空，默认空串") databaseName: String = "",
        @McpDescription("连续读取次数，默认3，允许1—5；第一次仅表示本次采样的首次调用，不保证是冷启动") iterations: Int = 3,
    ): String {
        val start = System.nanoTime()
        val native = NativeDatabaseToolset()
        val samples = metadataSamples(iterations) {
            native.listSchemaObjects(connectionId, databaseName, schemaName, null)
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        return buildJsonObject {
            put("totalMs", totalMs)
            put("samples", samples)
        }.toString()
    }

    @McpTool
    @McpToolHints(readOnlyHint = FALSE, destructiveHint = TRUE, idempotentHint = FALSE)
    @McpDescription("在 GoLand 已配置的数据源执行明确授权的增删改 SQL 或驱动命令。复用内置数据库执行器和 IDE 确认，不读取或复制密码。入账结果未知时先查询核对，禁止自动重试。只读数据源拒绝写入；非空 errorMessage 按工具失败返回。")
    suspend fun execute_database_write(
        @McpDescription("已有数据库连接的精确 ID") connectionId: String,
        @McpDescription("要执行的增删改 SQL 或该数据源支持的驱动命令") statement: String,
        @McpDescription("数据库名；驱动不使用该层时为空，默认空串") databaseName: String = "",
        @McpDescription("schema名；驱动不使用该层时为空，默认空串") schemaName: String = "",
    ): NativeDatabaseToolset.QueryExecutionResult {
        require(statement.isNotBlank()) { "statement 不能为空" }
        val native = NativeDatabaseToolset()
        val source = native.listDatabaseConnections().connections.firstOrNull { it.id == connectionId }
            ?: mcpFail("数据库连接不存在：$connectionId")
        if (source.readOnly) mcpFail("数据源已设置为只读，请在 IDE 中确认用途后调整")
        val result = native.executeQuery(connectionId, databaseName, schemaName, statement)
        if (!result.errorMessage.isNullOrBlank()) mcpFail(result.errorMessage!!)
        return result
    }

    @McpTool
    @McpDescription("仅在元数据未加载或明确需要更新时，通过内置 Database Tools 刷新指定 schema。普通查询或列集合不需要每次先刷新。已知库名直接使用 list_schema_objects 即可读取集合列表与加载状态。本工具不修改数据库记录。")
    suspend fun refresh_database_schema(
        @McpDescription("已有数据库连接的精确 ID") connectionId: String,
        @McpDescription("数据库名，默认空串") databaseName: String = "",
        @McpDescription("schema名，默认空串") schemaName: String = "",
    ): NativeDatabaseToolset.SchemaInfo = NativeDatabaseToolset().introspectSchema(connectionId, databaseName, schemaName)
}
