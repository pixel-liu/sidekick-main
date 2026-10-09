# SideKick

SideKick 是一个面向本地开发工作的 Java Agent CLI，提供 ReAct、计划执行和多 Agent 协作三种任务模式。它能读写项目文件、搜索代码、执行受控命令、检索代码库、调用 Web 与 MCP 工具，并为危险操作提供人工确认和审计。

项目变更与验证记录见 [CHANGELOG.md](CHANGELOG.md)。参与开发前请阅读 [SideKick.md](SideKick.md)。

## 核心能力

- **任务模式**：默认 ReAct 适合直接执行；`/plan` 将复杂任务拆解后确认执行；`/team` 由规划、执行和审查角色协作。
- **代码工作流**：文件读写、glob、实时 grep、Shell 命令、代码语义检索与依赖关系查询。
- **上下文与记忆**：短期上下文自动压缩；`/save` 保存跨会话事实；`SideKick.md` 保存项目规则。
- **扩展能力**：内置 Web 搜索与抓取，支持 MCP 工具、资源和 Chrome DevTools 浏览器自动化。
- **模型与运行时**：可在 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI、Agnes 间切换；支持后台任务与本地 Runtime API。
- **安全控制**：路径围栏、命令拦截、人工审批、操作审计和 Side-Git 快照回滚。

## 快速开始

要求：JDK 17、Maven，以及至少一个模型服务的 API Key。代码语义检索默认还需要本地 Ollama 与 `nomic-embed-text:latest`；不使用 `/index`、`/search` 时可以跳过。

```bash
# macOS / Linux
cp .env.example .env

# Windows PowerShell
Copy-Item .env.example .env
```

在 `.env` 中至少填写一个 API Key，例如：

```dotenv
GLM_API_KEY=your_api_key_here
```

构建并启动：

```bash
mvn clean package
java -jar target/Sidekick-1.0-SNAPSHOT.jar
```

启动后可以直接描述任务，例如“读取 pom.xml 并说明项目结构”。复杂任务使用：

```text
/plan 为当前项目补充一个测试，然后运行相关回归
```

## 常用命令

| 场景 | 命令 |
| --- | --- |
| 任务模式 | `/plan [任务]`、`/team [任务]`、`/cancel` |
| 代码库 | `/index [路径]`、`/search <查询>`、`/graph <类名>` |
| 记忆 | `/save <事实>`、`/save --global <事实>`、`/memory list/search/delete/clear` |
| 模型与配置 | `/model <provider>`、`/config provider ...` |
| MCP 与浏览器 | `/mcp`、`/mcp restart <name>`、`/browser status/connect/disconnect/tabs` |
| 安全与回滚 | `/hitl on/off`、`/policy`、`/audit [N]`、`/snapshot`、`/restore <N>` |
| 其他 | `/task`、`/wechat`、`/export`、`/init`、`/clear`、`/exit` |

输入 `/help` 或在命令后按补全键可查看完整参数。

## 工具与安全边界

Agent 可使用 `read_file`、`write_file`、`list_dir`、`glob_files`、`grep_code`、`execute_command`、`search_code`、`web_search`、`web_fetch` 和动态注册的 `mcp__{server}__{tool}` 工具。精确代码定位优先 `glob_files`、`grep_code` 与 `read_file`；`search_code` 用于语义辅助。

文件和代码检索工具只能访问项目根目录内的路径。`execute_command` 默认 60 秒超时，超时会终止完整进程树，并预先拒绝高风险命令。开启 `/hitl on` 后，写文件、执行命令、创建项目、回滚等操作需要人工确认；审计日志写入 `~/.sidekick/audit/`。

## 配置与数据位置

`.env.example` 列出了全部模型、Embedding、Web、MCP、日志和 Runtime API 配置。常用配置如下：

| 用途 | 位置或变量 |
| --- | --- |
| 模型 Key 与模型名 | `.env` 中的 `GLM_API_KEY`、`DEEPSEEK_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY`、`FREELLMAPI_API_KEY` 或 `AGNES_API_KEY` |
| 用户配置与 MCP | `~/.sidekick/config.json`、`~/.sidekick/mcp.json` |
| 项目 MCP 覆盖 | `.sidekick/mcp.json` |
| 项目记忆 | `SideKick.md`、`.sidekick/SideKick.md`；兼容 `PAI.md` |
| 长期记忆 | `~/.sidekick/memory/long_term_memory.json` |
| 代码索引 | `~/.sidekick/rag/codebase.db` |

MCP 配置文件不存在时会创建默认 Chrome DevTools 配置。自定义 MCP server 可使用 stdio `command` 或 Streamable HTTP `url`；配置中的 `${PROJECT_DIR}`、`${HOME}` 和环境变量会在启动时展开。

## 长期记忆容量与去重

长期记忆在原有字段之外存储 `content_hash`、`importance`、`confidence`、`access_count`、`updated_at` 和 `last_accessed_at`。两项评分范围为 0–1，默认均为 0.5；`save_memory` 工具支持可选的 `importance` / `confidence` 参数，Java 调用可使用 `storeFact(fact, scope, importance, confidence)`。

正文经过 Unicode NFC 归一化后，以 UTF-8 编码计算 SHA-256；大小写和空白仍有区别。在同一作用域和项目内按哈希去重，重复保存保留原 ID、正文、创建时间及召回次数，分别取新旧 importance/confidence 的较大值并刷新 updated_at。全局与项目记忆、不同项目之间仍独立存储。

容量配置使用 JVM 系统属性，需放在 `-jar` 前：

```bash
java -DSidekick.memory.max.entries=1000 -DSidekick.memory.max.tokens=128000 -jar target/Sidekick-1.0-SNAPSHOT.jar
```

`max_entries` 默认 1000；token 预算默认 `Integer.MAX_VALUE`，可通过上面的 `Sidekick.memory.max.tokens` 配置。上限均为正整数，对整个长期记忆文件生效。每次保存和启动加载后检查容量，超限时按保留分从低到高淘汰，直到同时满足两项上限；新写入记录也参与排序，单条超过 token 上限时可能立即被淘汰。

保留分为 `0.40 × importance + 0.30 × confidence + 0.15 × frequency + 0.15 × recency`，其中 `frequency = access_count / (access_count + 5)`，`recency = 1 / (1 + 距 updated_at 的天数 / 30)`。同分时先淘汰更新时间更早的记录，再按创建时间和 ID 确定顺序。

实际按 ID 读取、搜索返回或模型召回会增加 access_count，并刷新 updated_at / last_accessed_at；列表查看和候选评分扫描不增加计数。构造模型上下文时仅统计实际进入 token 预算的记录。旧 JSON 自动补齐默认字段、重建哈希索引，并按当前容量上限迁移；这可能在启动时淘汰旧记录。保存继续使用同目录临时文件与原子替换。

## Runtime API

Runtime API 仅监听本机地址，必须配置密钥：

```bash
Sidekick_RUNTIME_API_KEY=your_local_api_key \
  java -jar target/Sidekick-1.0-SNAPSHOT.jar serve --http --port 8080
```

提供 `POST /v1/threads`、`POST /v1/threads/{id}/turns` 与 `GET /v1/threads/{id}/events`。请求使用 `Authorization: Bearer <key>` 或 `X-Sidekick-API-Key: <key>` 认证；运行繁忙时 turn 请求会返回 HTTP 429。

## 测试

```bash
# 日常快速回归
mvn test -Pquick

# 终端与 inline renderer 冒烟
mvn test -Pphase16-smoke

# 指定测试
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false

# 发布或大范围重构前的完整回归
mvn test -DskipTests=false
```

`mvn clean package` 默认跳过测试，用于生成可手工验收的 jar。

## 项目结构

```text
src/main/java/com/sidekick/
├── agent/      # ReAct、计划执行与多 Agent 编排
├── cli/        # 命令行入口和斜杠命令
├── tool/       # 内置工具、代码搜索与策略接入
├── memory/     # 短期与长期记忆
├── mcp/        # MCP 客户端、配置与资源
├── rag/        # 索引、向量检索与关系图谱
├── runtime/    # 后台任务与 Runtime API
└── prompt/     # 分层 Prompt 与项目记忆加载
```
