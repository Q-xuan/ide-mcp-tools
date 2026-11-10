# IDE MCP Tools

IDE MCP Tools adds run session control, debugging, and explicit database writes to GoLand's built-in MCP server. It uses your existing run configurations and database connections. Database writes follow the IDE's confirmation flow and respect read-only data sources.

让已连接 GoLand 的 AI 助手直接管理测试进程、查看断点现场，或对你指定的数据源执行写入。插件沿用 GoLand 自带的 MCP 连接。MCP 是客户端调用 IDE 工具的接口。

## 安装并连接

0.1.4 面向 GoLand 2026.3（IDE 构建号 `263.*`）。可在 GoLand 的 **帮助 → 关于** 查看构建号。GoLand 2026.2（`262.*`）使用 0.1.1。

1. 打开 **设置 → 插件 → 齿轮 → 从磁盘安装插件**，选择 `ide-mcp-tools-0.1.4.zip`。如果 IDE 提示重启，重启后继续。
2. 在 **设置 → 工具 → MCP Server** 启用服务，按页面提示连接你的 MCP 客户端。已经连接的客户端可以重新连接一次，刷新工具列表。
3. 打开目标项目。若同时开了多个项目，向客户端说明项目的完整目录。先试一句「列出这个项目正在运行的 Run 和 Debug 会话」，对应工具是 `list_run_sessions`。

[GoLand 的 MCP 设置文档](https://www.jetbrains.com/help/go/mcp-server.html)

## 可以怎么用

| 你想做什么 | 工具和操作 |
| --- | --- |
| 重启正在运行的服务 | 用 `list_run_sessions` 找到服务的 `sessionId`，再调用 `restart_run_configuration`。旧进程退出后才会启动新进程。新进程有新的 ID。 |
| 停止指定测试进程 | 用 `stop_run_session` 传入会话 ID。默认走正常退出。只有显式传 `force=true` 且 IDE 支持时才强制终止。 |
| 看程序卡在哪里 | 用已有运行配置启动 Debug，设置行断点。暂停后可以读取调用栈、局部变量和表达式结果。GoLand 2026.3 的原生 `xdebug_*` 工具也可用于新调试流程。 |
| 改一条测试数据 | 先确定临时数据源和 SQL，再用 `execute_database_write` 执行。GoLand 的原生确认和只读限制继续生效。执行后可用原生查询工具核对结果。 |
| 加载数据库的表结构 | 用 `refresh_database_schema` 加载 IDE 已发现的 schema。查询对象列表用原生 `list_schema_objects`。需要测元数据读取耗时时，可用 `probe_database_metadata` 在一次调用内采样 1 至 5 次。 |

### 几个常见情况

- Run 和 Debug 的会话 ID 来自本插件的会话列表。重启后请用新的 ID。热加载前启动的会话若缺少原始配置记录，可列出和停止，重启会被拒绝。
- 首次启动调试时 GoLand 可能需要先编译。启动调用超时后，先列出会话确认程序有没有启动，再决定是否重试。
- 停止默认等待 10 秒，最长可设 60 秒。`terminating` 表示仍在退出。Go 调试会话刚恢复时，插件先核对 Delve 的实际状态。如果 Windows 上的本地调试连接卡住，插件可向已核实的调试目标发送一次正常退出信号。遇到等待超时，请重新列出会话确认进程状态。
- 调试求值可能调用函数或改变程序状态。若涉及副作用且调用超时，先核对运行状态。断点源码行号从 1 开始，调用帧序号从 0 开始。
- `refresh_database_schema` 使用 IDE 的元数据模型。完全未发现的 schema 会返回找不到，已加载的 schema 可能直接返回缓存状态。0.1.4 的数据库实测范围是 SQLite。
- 元数据探针只读对象数量和加载状态。它的工具内耗时包含原生确认和模型读取。客户端往返耗时可用项目里的 `benchmark_database.py` 单独测量。

## 从源码构建

从项目根目录运行，`--ide-home` 指向 GoLand 安装目录。构建使用 GoLand 自带的 JBR 和 SDK。

```powershell
python -X utf8 build.py --ide-home "C:/path/to/GoLand" test packagePlugin
```

安装包写到 `.tmp/build/distributions/ide-mcp-tools-0.1.4.zip`。

0.1.4 的运行态验证使用 Windows 上的 GoLand 2026.3（`263.7307.211`）。测试从断点和手动暂停恢复后立即停止，连续 8 轮均在默认 10 秒内正常退出。

## JetBrains Marketplace 提交

在 Marketplace 的 **Description** 字段可使用本文首段英文介绍。0.1.4 安装包已包含英文版本说明。

> Fixes a race when stopping a Go debug session immediately after resuming it.

首次上传需要填写有效的发布者网站和联系邮箱，提供原创的 40 × 40 SVG 图标和最终用户许可协议。选择开源许可时还需填写可访问的源码链接。这些发布资料需在提交前补齐。JetBrains 会对上传包做兼容性检查和人工审核。

[Marketplace 审核要求](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html) · [首次上传流程](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
