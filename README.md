# SideKick

一个成熟的 Java Agent CLI 产品，对标 Claude Code。

项目迭代与验证记录见 [CHANGELOG.md](CHANGELOG.md)。

## 测试策略

日常开发不需要每次都跑全量测试。`mvn clean package` 默认跳过测试，优先产出可手工验收的 jar；需要回归时按改动范围选择：

```bash
# 终端 / TUI / inline renderer 冒烟
mvn test -Pphase16-smoke

# 常规快速回归，跳过外部进程 / 网络超时 / 命令超时类慢测试
mvn test -Pquick

# 代码搜索 deterministic golden set
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false

# 发版或大范围重构前再跑全量
mvn test -DskipTests=false
```

### ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、文件 glob、代码 grep、执行命令、创建项目、RAG 语义辅助检索、联网搜索、MCP 动态工具
- 更适合简单任务或单步操作

### Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### Memory + 上下文工程

- 短期记忆管理当前对话与工具结果
- 长期记忆通过 `/save <事实>` 或用户明确说“记一下 / 记住”时的 `save_memory` 保存关键事实，默认项目级作用域，跨会话复用
- 项目级记忆通过 `SideKick.md` / `.sidekick/SideKick.md` 启动自动注入，适合提交到仓库的团队共享规则；`SideKick.local.md` / `.sidekick/SideKick.local.md` 只做本地覆盖，同时兼容 `PAI.md` 系列文件名
- 注入给模型的相关记忆只使用长期稳定事实，不把当前轮短期对话误当成“历史记忆”
- 对话接近预算时自动做摘要压缩
- 新增 `/memory` 查看状态、`/memory list/search/delete/clear` 管理长期记忆、`/save` 手动保存事实；Agent 在用户明确说“记一下 / 记住”时可调用 `save_memory`

### RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- `search_code` 作为语义辅助检索工具；精确代码定位默认走 `glob_files` / `grep_code` / `read_file` 现用现查

### Multi-Agent 协作 + 角色分工

- 三个角色：规划者（Planner）、执行者（Worker）、检查者（Reviewer）
- 主从架构：编排器（Orchestrator）协调子代理（SubAgent）
- 规划者拆解任务 -> 执行者执行 -> 检查者审查质量
- 审查未通过时带反馈重试（最多 2 次），冲突自动解决
- 新增 `/team` CLI 命令，进入多 Agent 协作模式

### Human-in-the-Loop + 审批流

- 危险操作静态规则识别：`write_file`、`execute_command`、`create_project`、`revert_turn`
- 三级危险等级：高危（`execute_command`）、中危（`write_file` / `create_project`）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，通过 `/hitl on` 启用
- 新增 `/hitl` CLI 命令，支持 `/hitl on`、`/hitl off`、`/hitl`（查看状态）

### 异步执行 + 并行工具调用

- 同一轮 LLM 返回多个 `tool_calls` 时，工具层会并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 都复用统一的批量工具执行入口
- 工具结果仍按原始 `tool_call` 顺序回灌，保证消息历史协议稳定
- 批量工具调用有统一超时与取消兜底，单个 `execute_command` 仍保留 60 秒命令级超时
- Plan-and-Execute 与 Multi-Agent 已支持按依赖批次并行执行独立任务

### 多模型适配 + 运行时切换

- `LlmClient` 接口抽象 + `AbstractOpenAiCompatibleClient` 模板基类
- 内置 `GLMClient`、`DeepSeekClient`、`StepClient`、`KimiClient`、`FreeLlmApiClient`、`AgnesClient` 六个瘦实现
- `/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型；`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 切 provider 并读取配置里的具体模型
- 配置持久化到 `~/.Sidekick/config.json`，API Key 可从配置、环境变量或 `.env` 读取

### 联网能力 + Web 工具

- `web_search` 抽象成 `SearchProvider` 接口，内置三个实现：智谱 Web Search（默认，与 GLM 共用 Key，0.01–0.05 元/次）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- `web_fetch` 新工具：URL → OkHttp 抓取 → Jsoup 解析 → 简易 readability → Markdown 正文
- 当当前模型是 `step-3.7-flash*` 且自动/显式 `step_search` 远程 server 已就绪时，内置 `web_search` / `web_fetch` 会优先走 StepSearch MCP；未就绪或调用失败时自动回退到原 provider。
- ReAct 对“最新/当前/今天/今年/2026/趋势/新闻/版本”等时效性问题会先做一次 `web_search` 预检并注入本轮上下文，避免模型在工具可用时误说无法实时搜索；用户明确不要联网时跳过。
- 默认安全策略：屏蔽 `file://` / 内网 / loopback；30 秒超时；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙站点会返回空正文 + 已知边界提示，Agent 会 fallback 到浏览器 MCP 路线

### MCP 协议核心

- 新增 `com.sidekick.mcp` 模块，支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- 启动时读取 `~/.Sidekick/mcp.json` 与 `.Sidekick/mcp.json`，项目级配置按 server 名覆盖用户级配置
- MCP `${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`；检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP，显式同名配置优先
- MCP 工具自动注册为 `mcp__{server}__{tool}`，参数 schema 会清洗 `$ref` / `anyOf` / 超长 description，降低模型调用失败率
- 所有 MCP 工具默认走 HITL 审批和审计，审计参数会脱敏 token / key / password / Authorization / Bearer 凭证
- 支持 MCP resources：server 声明 `resources` capability 后，自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具
- 普通输入支持 `@server:protocol://path` 显式引用 resource，提交给 Agent 前展开为 `<resource>` 内联块
- 被动处理 `notifications/tools/list_changed`、`notifications/resources/list_changed`、`notifications/resources/updated`
- 运行中输入 `/cancel` 并回车可请求取消当前 Agent run
- CLI 命令：`/mcp`、`/mcp restart <name>`、`/mcp logs <name>`、`/mcp disable <name>`、`/mcp enable <name>`、`/mcp resources <name>`、`/mcp prompts <name>`
- `~/.Sidekick/mcp.json` 不存在时会自动创建默认 chrome-devtools 配置；项目级 `.Sidekick/mcp.json` 仍可按 server 名覆盖


### Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__navigate_page`、`take_snapshot`、`click`、`fill_form` 等浏览器工具
- `~/.Sidekick/mcp.json` 不存在时启动自动创建模板，默认使用 `--isolated=true` 临时浏览器 profile
- 用于处理 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号文章、知乎专栏、推特、小红书等 `web_fetch` 失败站点会引导走浏览器 MCP
- HITL 的“全部放行”支持 MCP server 维度，连续浏览器操作可对 `chrome-devtools` 一次确认
- `image` 类型结果会作为图片输入附加到下一轮；文本 fallback 仍保留，用于日志、人类可读摘要，以及 DeepSeek 等不接受图片块的 provider 自动降级上下文
- MCP initialize 默认超时为 60 秒；CLI 首屏默认最多等待 8 秒，超时后先进入交互，未完成的 server 保持 `starting` 并在后台继续启动，可用 `/mcp` 和 `/mcp logs <name>` 追踪

### CDP 会话复用 + 登录态访问

- 新增 `/browser status`、`/browser connect [port]`、`/browser disconnect`、`/browser tabs` 命令组，并给 Agent 暴露内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具
- 默认仍使用 `--isolated=true` 临时浏览器 profile；执行 `/browser connect` 后，运行时把 `chrome-devtools` 切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome
- Agent 遇到登录页、权限不足或明确需要登录态页面时，会先调用 `browser_connect` 自动切到 shared；公开页面如微信公众号文章不提前切换
- `/browser connect <port>` 保留旧式 CDP 端口兼容路径：先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败时不会改 MCP 启动参数，并输出 macOS / Windows / Linux 的 Chrome 启动命令
- 切换 shared / isolated 模式都会清空 `chrome-devtools` 的 server 维度全部放行，避免旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 Sidekick 自己创建的 tab；无法证明是 Sidekick 创建的 tab 会被策略层拒绝
- 敏感页面命中规则后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；读型工具如 `take_snapshot` 仍可继续使用
- 审计日志为 chrome-devtools 工具追加可选浏览器 metadata：`browser_mode`、`sensitive`、`target_url`，旧格式 JSONL 仍可读取

### Skill 系统 + 内置 web-access skill

把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用单元。每个 Skill 是一个目录：`SKILL.md`（决策手册）+ `references/`（按需读取）+ 可选 `scripts/`（可执行依赖）。

- 三层加载位置（按优先级，后者整体覆盖同名 skill）：jar 内置 < 用户级 `~/.Sidekick/skills/<name>/` < 项目级 `<project>/.Sidekick/skills/<name>/`
- 启动期把启用 skill 的 `name` + `description` 注入三处 Agent 系统提示词索引段（启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 在 system prompt 看到匹配 description 时主动调用，Sidekick 把 SKILL.md 正文（5KB 截断）写入 `SkillContextBuffer`，下一轮 user message 自动前置注入
- Skill 正文仅注入一次，操作指南始终围绕用户当前任务，不预设任务类型。
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- frontmatter 走手写 YAML 子集解析，不引 SnakeYAML；解析失败 stderr 警告但不阻塞启动
- CLI 命令：`/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 启用状态持久化：`~/.Sidekick/skills.json` 的 `disabled` 列表，默认全启用
- 与 HITL 协同：Skill 内调用 `execute_command` 等危险工具仍走既有 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

设计意图：从「写工具」演进到「打包专家手册」。当工具堆成山（Sidekick 当前内置 9 个 + MCP 60+ 工具），用 Skill 给 LLM 一份按场景展开的"专家手册"，比往 system prompt 里塞更多规则更可扩展。

### LSP 诊断注入（MVP）

- `write_file` 成功后触发 post-edit 诊断，诊断结果不会阻塞工具主流程
- 当前 MVP 对 Java 文件使用 JavaParser 做轻量语法诊断，不依赖本机安装 JDT LS
- ReAct、Plan-and-Execute、Multi-Agent 三条路径都会在下一轮 LLM 请求前注入 pending 诊断
- 诊断按 error / warning / info、文件、行列号、message 格式化，默认最多注入 20 条
- 配置：`Sidekick_LSP_ENABLED=false` 可关闭，`Sidekick_LSP_MAX_DIAGNOSTICS=20` 可调整注入上限
- 后续增强：接入 JDT LS / rust-analyzer / pyright / gopls 的 stdio JSON-RPC transport

### Prompt 分层架构（MVP）

- ReAct、Plan task executor、Multi-Agent 三角色、Planner 的 system prompt 已从 Java 硬编码抽离到 `src/main/resources/prompts/`
- `PromptAssembler` 按 `base -> personality -> mode -> approval -> runtime_context -> project_context -> skills -> context_mgmt -> handoff` 组装；`runtime_context` 注入当前日期/时区，动态项目上下文靠后注入
- `project_context` 会先注入 `SideKick.md` 项目记忆，再注入 `/save` 检索到的相关长期记忆和 MCP resource 索引
- 支持用户级覆盖 `~/.Sidekick/prompts/...`，支持项目级覆盖 `.Sidekick/prompts/...`，项目级优先级最高
- 覆盖是整文件替换；`base.md` 和最终 prompt 必须包含 `## Language`
- Prompt 改动审计模板见 `docs/prompt-analysis-template.md`

### 异步后台任务 + Runtime API（MVP）

- `DurableTaskManager` 使用 SQLite 持久化后台任务队列，默认位置 `~/.Sidekick/tasks/tasks.db`
- 任务生命周期：`enqueued -> running -> completed / failed / canceled`
- `/task`、`/task add <任务内容>`、`/task cancel <task_id>`、`/task log <task_id>` 提供 CLI 闭环
- Worker Pool 默认 2 个后台 worker，可通过 `Sidekick_TASK_WORKERS` 调整
- `java -jar target/Sidekick-1.0-SNAPSHOT.jar serve --http --port 8080` 启动 localhost Runtime API
- Runtime API 端点：`POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`
- Runtime API 强制要求 `Sidekick_RUNTIME_API_KEY` 或 `-DSidekick.runtime.api.key`
- Runtime API 使用独立的有界 turn 线程池；队列满时返回 HTTP 429，错误和事件响应统一由 Jackson 生成合法 JSON，格式错误的请求返回 HTTP 400
- 详细文档见 `docs/phase-20-runtime-api.md`


### HITL 增强（路径围栏 / 命令快速拒绝 / 操作审计）

`com.sidekick.policy` 包，作为 HITL 之外的辅助层（不是沙箱、不提供进程隔离）：

- `PathGuard` 路径围栏：文件类工具强制限定在项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- `CommandGuard` 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- `AuditLog` 结构化审计：危险工具调用按天写 JSONL 到 `~/.Sidekick/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`；`revert_turn` 也纳入危险工具链
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 查看安全策略状态、`/audit [N]` 看最近 N 条审计

**为什么不叫沙箱**：本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做容器/VM 沙箱——沙箱削弱 Agent 能力、给虚假安全感、体验更差。生产级 Agent 沙箱实际是 microVM-level（Devin / Modal / Anthropic Computer Use 用 Firecracker / gVisor）。Sidekick 的安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是隔离。

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI 或 Agnes API Key：

```bash
cp .env.example .env
# 编辑 .env 文件，填入你的 API Key
```

或者在环境变量中设置：

```bash
export GLM_API_KEY=your_api_key_here
# 或
export STEP_API_KEY=your_step_api_key_here
export STEP_MODEL=step-3.5-flash
# 或
export KIMI_API_KEY=your_kimi_api_key_here
export KIMI_MODEL=kimi-k2.6
# 或
export FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
export FREELLMAPI_BASE_URL=http://localhost:5173/v1
export FREELLMAPI_MODEL=auto
# 或
export AGNES_API_KEY=your_agnes_api_key_here
export AGNES_MODEL=agnes-2.0-flash
export AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
```

也可以在 Sidekick 内用命令写入 `~/.Sidekick/config.json`，不会覆盖 Kimi 配置：

```text
/config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
/model freellmapi
/config provider agnes --api-key <key> --model agnes-2.0-flash --default
/model agnes
```

长期记忆默认保存在用户目录下的 `~/.Sidekick/memory/long_term_memory.json`。
长期记忆只保存显式保存意图下的稳定事实：`/save <事实>`，或用户在自然语言里明确说“记一下 / 记住 / 以后记得”时由 Agent 调用 `save_memory`。默认保存为当前项目作用域；跨项目通用偏好可用 `/save --global <事实>` 或 `save_memory(scope=global)`。它不应包含一次性任务请求或临时文件名/目录名。
可用 `/memory list` 查看长期记忆，`/memory search <关键词>` 搜索当前项目可见记忆，`/memory delete <id>` 删除单条记忆。
相同内容仅在同一作用域和同一项目内去重；不同项目、全局与项目作用域可以分别保存相同事实。
长期记忆通过同目录临时文件原子替换，降低进程中断时留下半写 JSON 的风险。

项目级记忆使用 Markdown 文件维护，和 `/save` 的长期记忆分工不同：

- `~/.sidekick/SideKick.md`：用户级稳定偏好，所有项目可见。
- `SideKick.md` / `.sidekick/SideKick.md`：项目级团队规则，建议提交到 git。
- `SideKick.local.md` / `.sidekick/SideKick.local.md`：本地覆盖，适合个人调试约定，建议加入 `.gitignore`。
- 兼容 `PAI.md` / `PAI.local.md`；同一位置同时存在时优先读取 `SideKick.md` 系列。
- `@relative/path.md`：在项目记忆文件中导入项目根内的相对文件；越靠后的文件越接近本地覆盖，优先级越高。

可用 `/init` 为当前项目生成一份短 `SideKick.md`。该命令默认不覆盖已有文件；确认需要重建时使用 `/init --force`。
代码索引默认保存在 `~/.Sidekick/rag/codebase.db`。
重建索引时，全部文件的分块、向量和关系准备成功后才会在单个事务中替换旧索引；遍历、向量生成或写入失败会保留原有索引并提示失败。
调试日志默认滚动写入 `~/.Sidekick/logs/Sidekick.log`，旧日志会按保留天数和总容量自动清理。
ReAct / Plan task / SubAgent / Planner 的模型 `reasoning_content` 会以 `LLM reasoning [...]` 形式写入该日志，便于排查模型为什么选择某个工具或路径。

### 2. 可选：配置 MCP server

MCP 子系统默认开启。`~/.Sidekick/mcp.json` 不存在时，Sidekick 会自动创建默认 chrome-devtools 配置：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

需要继续接入其他 server 时，可编辑 `~/.Sidekick/mcp.json` 或项目内 `.Sidekick/mcp.json`：

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "${PROJECT_DIR}"]
    },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": {"Authorization": "Bearer ${REMOTE_TOKEN}"}
    },
    "step_search": {
      "url": "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
      "headers": {"Authorization": "Bearer ${STEP_API_KEY}"}
    }
  }
}
```

`command` 表示 stdio server，`url` 表示 Streamable HTTP server。`${PROJECT_DIR}` / `${HOME}` 是内置变量，其他 `${VAR}` 从环境变量读取；缺失会在启动时直接提示。

`step_search` 是约定名称：如果项目 `.env`、用户 `~/.env` 或系统环境变量里存在 `STEP_API_KEY`，Sidekick 会自动内置这个远程 MCP；上面的手写配置只用于覆盖默认地址或自定义鉴权。当前模型为 `step-3.7-flash*` 时，内置 `web_search` / `web_fetch` 会优先代理到该 MCP server。

需要复用当前登录态时，Chrome 144+ 推荐打开 `chrome://inspect/#remote-debugging` 并勾选 `Allow remote debugging for this browser instance`。旧版本或需要显式 CDP 端口时，可以启动带远程调试端口和独立 user-data-dir 的 Chrome，并在这个调试 Chrome 中完成登录：

```bash
# macOS
open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/Sidekick-chrome-profile

# Windows
start chrome.exe --remote-debugging-port=9222 --user-data-dir=%TEMP%\Sidekick-chrome-profile

# Linux
google-chrome --remote-debugging-port=9222 --user-data-dir=/tmp/Sidekick-chrome-profile
```

通常不需要用户预先切换；Agent 如果遇到登录页会自己调用 `browser_connect`。手工调试时也可以在 Sidekick 内执行：

```text
/browser status
/browser connect
/browser tabs
/browser disconnect
```

`/browser connect` 只在当前进程内把 `chrome-devtools` 切到 shared 模式，不会改写 `~/.Sidekick/mcp.json`。如果希望启动后默认 shared，可手动把 args 改为：

```json
["-y", "chrome-devtools-mcp@latest", "--autoConnect"]
```

旧式 CDP HTTP JSON 端口也可使用：

```json
["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:9222"]
```

浏览器测试可直接让 Agent 读取动态页面，例如：

```text
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

期望路径是 `web_fetch` 尝试失败后，fallback 到 `mcp__chrome-devtools__navigate_page` 与 `take_snapshot`。

如果 server 支持 resources，可以直接查看或引用：

```text
/mcp resources filesystem
/mcp prompts filesystem
帮我看下 @filesystem:file://README.md 这份文档
```

OAuth 和 `sampling/createMessage` 当前未实现；远程 server 需要鉴权时仍使用 `headers` + 环境变量注入 Bearer token。

### 3. 编译运行

```bash
# 编译（默认跳过测试）
mvn clean package

# 运行（需要本地 Ollama 已启动且拉取了 nomic-embed-text；grep_code 会优先使用本机 ripgrep，未安装时自动回退）
java -jar target/Sidekick-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.sidekick.cli.Main"
```

### 4. 如何进入 Plan 模式

当前默认模式是 `ReAct`。进入 `Plan-and-Execute` 的方式只有 `/plan`：

1. 输入 `/plan`
2. 下一条任务会用计划模式执行
3. 执行完成后自动回到默认 `ReAct`

如果想一条命令切模式并执行任务，可以直接输入：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

这条命令执行完成后，会自动回到默认的 `ReAct` 模式。

计划生成后，CLI 会先停下来等待确认：

- 按 `Enter`：按当前计划执行
- 按 `Ctrl+O`：展开完整计划
- 按 `ESC`：折叠完整计划或取消本次计划
- 按 `I`：输入补充要求并重新规划
- 按方向键不会触发取消；只有单独按下 `ESC` 才会取消待执行 plan

## 可用工具

- `read_file` - 流式读取文件内容；整文件读取最多返回 60,000 个字符，超出时提示截断，也可通过 offset/limit 读取指定行段
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `glob_files` - 按文件名 glob 实时查找项目内文件（只读，自动跳过常见构建/依赖目录）
- `grep_code` - 按关键字或正则实时搜索项目内代码，优先使用 ripgrep，返回文件、行号、可选上下文、partial 状态与 suggested_reads
- `execute_command` - 在当前项目目录执行短时 Shell 命令（默认 60 秒超时，超时会终止完整进程树，黑名单拦截破坏性命令）
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询，适合作为模糊语义或常规搜索无果时的辅助）
- `web_search` - 搜索互联网获取实时信息
- `web_fetch` - 抓取已知 URL 并提取正文 Markdown
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `mcp__{server}__{tool}` - MCP server 动态提供的外部工具
- `mcp__{server}__list_resources` / `mcp__{server}__read_resource` - 支持 resources 的 MCP server 自动注册的虚拟工具

同一轮模型返回多个工具调用时，Sidekick 会并行执行这些工具；如果工具之间有依赖关系，模型应分多轮调用。

文件类与代码检索工具（`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `create_project`）路径强制限定在项目根之内，越界请求会被策略层拒绝；`execute_command` 通过命令黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` 等。`revert_turn` 会批量回写工作区，默认触发 HITL 和审计。所有 `mcp__` 前缀工具默认触发 HITL 和审计。详见 `/policy`。

## 命令

交互式斜杠命令：

- `/wechat` - 扫码绑定并启动微信 iLink 通道；已绑定时直接启动
- `/wechat setup` - 重新扫码绑定并启动微信通道
- `/wechat status` - 查看当前 Sidekick 进程内微信通道状态
- `/wechat stop` - 停止当前 Sidekick 进程内微信通道
- `/plan` - 下一条任务使用 Plan-and-Execute 模式
- `/plan <任务>` - 直接用 Plan-and-Execute 模式执行这条任务
- `/team` - 下一条任务使用 Multi-Agent 协作模式
- `/team <任务>` - 直接用 Multi-Agent 协作模式执行这条任务
- `/cancel` - 运行中请求取消当前任务；空闲时会提示当前没有正在运行的任务
- `/hitl on` - 启用危险操作人工审批（HITL）
- `/hitl off` - 关闭 HITL 审批
- `/hitl` - 查看 HITL 当前状态
- `/mcp` - 查看所有 MCP server 状态
- `/mcp restart <name>` - 重启单个 MCP server
- `/mcp logs <name>` - 查看 MCP server 最近 200 行 stderr 日志
- `/mcp disable <name>` - 运行时禁用 MCP server 并移除其工具
- `/mcp enable <name>` - 运行时启用 MCP server
- `/mcp resources <name>` - 查看 MCP server 暴露的 resources
- `/mcp prompts <name>` - 查看 MCP server 暴露的 prompts（只查看，不注入对话）
- `/policy` - 查看安全策略状态（路径围栏 / 命令黑名单 / 资源上限 / 审计目录）
- `/audit [N]` - 查看今日最近 N 条危险工具审计记录（默认 10）
- `/snapshot` - 查看最近 Side-Git 快照
- `/snapshot status` - 查看 Side-Git 快照状态
- `/snapshot clean` - 清理当前项目 Side-Git 快照目录
- `/restore <N>` - 恢复到最近第 N 个 pre-turn 快照
- `/memory` / `/mem` - 查看记忆系统状态
- `/memory list` - 查看长期记忆列表
- `/memory search <关键词>` - 搜索当前项目可见长期记忆
- `/memory delete <id>` - 删除单条长期记忆
- `/memory clear` - 清空长期记忆
- `/save <事实>` - 手动保存项目级关键事实到长期记忆；`/save --global <事实>` 保存跨项目通用偏好
- `save_memory` - Agent 内置工具，仅在用户明确要求保存长期偏好或稳定事实时调用；默认 `scope=project`，跨项目通用偏好才用 `scope=global`
- `/init` - 生成精简项目级记忆 `SideKick.md`；已存在时不覆盖，`/init --force` 可重写
- `/export` - 导出当前 ReAct 会话对话记录为 Markdown（包含完整 system prompt），写入 `~/.Sidekick/exports/session-*.md`
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码（RAG 辅助路径）
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空当前对话历史、短期记忆、待注入 Skill 上下文和上一轮检索记忆注入；长期记忆保留
- `/exit` / `/quit` - 退出程序

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
- JLine 4（终端交互、Status、输入 widgets）
- SQLite（向量与图谱持久化）
- JavaParser（AST 分析）
- Ollama（本地 Embedding）

## 项目结构

```
src/main/java/com/Sidekick
├── agent/
│   ├── Agent.java              # ReAct Agent
│   ├── PlanExecuteAgent.java   # Plan-and-Execute Agent
│   ├── AgentRole.java          # Agent 角色枚举
│   ├── AgentMessage.java       # Agent 间通信消息
│   ├── SubAgent.java           # 可配置子代理
│   └── AgentOrchestrator.java  # Multi-Agent 编排器
├── cli/
│   ├── Main.java               # CLI 入口
│   ├── CliCommandParser.java   # 命令解析
│   └── PlanReviewInputParser.java  # 计划审核输入
├── llm/
│   ├── GLMClient.java          # GLM API 客户端；glm-5.1 走 Coding endpoint，glm-5v-turbo 走多模态 endpoint
│   ├── DeepSeekClient.java     # DeepSeek API 客户端
│   ├── StepClient.java         # 阶跃星辰 StepFun API 客户端
│   ├── KimiClient.java         # Kimi / Moonshot API 客户端
│   ├── FreeLlmApiClient.java   # 本地 FreeLLMAPI OpenAI-compatible 网关客户端
│   └── AgnesClient.java        # Agnes AI OpenAI-compatible 客户端
├── context/
│   ├── ContextMode.java        # short / balanced / long 模式
│   ├── ContextProfile.java     # 模型窗口与上下文策略
│   └── TokenUsageFormatter.java # Token / cache / 成本展示
├── memory/
│   ├── MemoryEntry.java        # 记忆条目
│   ├── ConversationMemory.java # 短期记忆
│   ├── LongTermMemory.java     # 长期记忆
│   ├── ContextCompressor.java  # 上下文压缩
│   ├── TokenBudget.java        # Token 预算管理
│   ├── MemoryRetriever.java    # 记忆检索
│   └── MemoryManager.java      # 记忆门面类
├── plan/
│   ├── Task.java               # 任务定义
│   ├── ExecutionPlan.java      # 执行计划
│   └── Planner.java            # 规划器
├── rag/
│   ├── EmbeddingClient.java    # Embedding API 客户端
│   ├── VectorStore.java        # SQLite 向量存储
│   ├── CodeChunk.java          # 代码块模型
│   ├── CodeChunker.java        # 代码分块器
│   ├── CodeAnalyzer.java       # AST 关系分析
│   ├── CodeRelation.java       # 代码关系模型
│   ├── CodeIndex.java          # 索引管理器
│   └── CodeRetriever.java      # 检索入口
└── tool/
    └── ToolRegistry.java       # 工具注册表
```
