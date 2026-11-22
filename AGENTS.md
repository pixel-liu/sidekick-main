# Repository instructions

Read SideKick.md for the shared architecture and development commands.

## Iteration workflow

- 每次迭代项目代码，都必须在 CHANGELOG.md 中记录本次变更、验证结果和遗留问题；完成相关验证后提交本次迭代的代码与文档，并推送到当前 GitHub 仓库的对应分支。
- Git 提交信息和推送到 GitHub 的变更描述统一使用中文。
- Windows 日常更新入口位于安装目录的 `update-sidekick.bat`，通过 `scripts/update-local.ps1 -RegisterOnly` 生成，引用源码仓库的 BAT；常规更新成功后刷新入口。默认安装目录为 `D:\software\SideKick`，保留配置并备份旧程序文件。修改此流程时同步 README.md。

## Inline terminal input

- Keep chat-frame decoration and the empty-input hint in JLine's display. All three task modes share the line editor. Esc clears a draft or cancels an armed Plan/Team mode when the input is empty. Ctrl+C cancels a running CLI task, and a second press within two seconds exits. On CLI exit, hide the status rows and restore full terminal scrolling before closing the terminal.
- Use one status row with the model and `ctx` percentage and progress bar on the left, and grouped recent request `in/out/cache` on the right. Show cost with usage when space allows. Keep the three token fields visible before any reported usage and retain the latest reported values on status refresh. New interactive sessions start with HITL approval disabled. Users can enable it for the session with `/hitl on`. The bottom bar omits the HITL badge, MCP count, and skill count.
- Show the starting directory next to the SK logo, immediately above the hint that approvals are off by default and `/hitl on` enables them. Preserve the startup banner palette and assistant reply formatting. Use bright white for the entire `Thinking` fold control and RGB(102, 102, 102) for reasoning, tools, approvals, and other inline conversation details. Preserve the existing semantic colors for the chat input, completion timing, and bottom status row.
- 实时思考在 JLine 临时区域显示，与固定状态行分开管理，刷新期间隐藏光标。每条提问的多次模型思考合并在问题下方的同一个折叠入口中。Ctrl+O 展开后，入口、思考正文、回答顺序固定。在可展开的当前轮结束前保留完整回答，避免把部分正文提前写入历史。有可滚动内容时，折叠和展开视图都用鼠标滚轮双向浏览当前轮，触顶不会丢失滚轮；终端历史仍可通过终端自身的滚动条查看。Alt+O 仅查看最近一批工具调用及结果摘要。回答中的目录树和代码块按原顺序直接显示，不进入工具详情；仅有隐藏思考和工具调用的迭代不向正文追加空行。实时思考与展开后的思考正文不写入终端滚动历史，禁止使用备用屏幕或通过追加输出重放本轮内容。
- Validate interactive input and the status layout with `mvn test -Pphase16-smoke`.
- Ctrl+C / Esc 必须能取消任务前快照等待并恢复聊天输入，不能等待目录扫描或大文件压缩结束；已取消的快照准备不得继续启动 Agent。前后快照保持串行，Git 遍历和文件读取需响应线程中断。
- `/snapshot clean` 只删除当前项目对应的 Side-Git 快照。Windows 下必须清除 JGit 对象文件的只读属性后再递归删除；不得影响项目工作目录或其他项目快照。
- `.env` 按当前项目、用户目录的顺序读取。重复 Key 必须跳过空值，项目中的第一个非空值优先于用户目录配置；修改加载逻辑时覆盖重复空值与用户目录回退。

## Built-in skills

- Built-in skills are `web-access`, `debugging`, `code-review`, and `skill-authoring`; register new resource files in `SkillBuiltinExtractor`.
- Keep skill bodies within the current `load_skill` limit of 5,120 Java characters, and keep essential instructions self-contained. Project rules and user authorization take precedence over skill guidance.
- Validate skill changes with: mvn test "-Dtest=Skill*Test,LoadSkillToolTest" -DskipTests=false

## Long-term memory

- Preserve project/global visibility boundaries. Deduplicate with (scope, project, SHA-256 of UTF-8 Unicode NFC content).
- Duplicate saves retain identity and creation time; merge importance/confidence with max and refresh updated_at.
- Enforce Sidekick.memory.max.entries (default 1000) and optional Sidekick.memory.max.tokens after saves and startup migration using the documented retention score.
- Count only returned recalls or memories actually injected into the model context. Administrative list/scoring scans must not affect recall statistics.
- Maintain compatibility with legacy JSON; persist via same-directory temporary file and atomic replacement.
- Validate memory changes with: mvn test "-Dtest=MemoryEntryTest,LongTermMemoryTest,LongTermMemoryRetentionTest,MemoryRetrieverTest,MemoryManagerTest,ToolRegistryTest" -DskipTests=false

## Request context budget

- ReAct accepts explicit `<!-- sidekick:pin ID -->` and `<!-- /sidekick:pin -->` blocks from user input. Keep their original text in session memory and send one copy as user context. Put only an ID reference in history, summaries, and recovery archives. Replacing an ID updates the active text. An `<!-- sidekick:unpin ID -->` line or `/clear` removes active text.
- Limit active pinned text to 16 entries and 8 KiB of UTF-8 per session. Count it in the model input budget and preserve it when trimming optional context or falling back from recovery. Report invalid blocks, quota violations, and remaining input overflow before sending the affected request.
- Count turns from actual user or task entry points. Automatic history compaction normally preserves three turns. Two consecutive failed or timed-out calls to the same tool with equivalent JSON arguments temporarily preserve five. Return to three after two subsequently completed turns without a new trigger.
- Cache up to two compacted turns per conversation. Restore only the needed turns in the outbound request, annotate their overlap with the summary, and drop the archive when clearing history. Keep the historical image policy and complete tool-call pairs.
- After history compaction, trim optional prompt context in this order: Skills index, lower-ranked recalls (keep the best match and importance ≥ 0.8), then explicitly marked optional project-memory blocks from last to first. Preserve core messages, tool-call pairs, and unmarked project rules.
- If the expanded history still exceeds the input budget, omit restored turns and compact to three if needed. If the core still exceeds the budget after that fallback, report the error before dispatch.
- Use standalone `<!-- sidekick:optional -->` and `<!-- /sidekick:optional -->` lines around disposable project background. Keep rules and required commands outside those blocks. Count recalls only after trimming, from the prompt actually dispatched.
