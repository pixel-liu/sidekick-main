# Repository instructions

Read SideKick.md for the shared architecture and development commands.

## Iteration workflow

- 每次迭代项目代码，都必须在 CHANGELOG.md 中记录本次变更、验证结果和遗留问题；完成相关验证后提交本次迭代的代码与文档，并推送到当前 GitHub 仓库的对应分支。
- Git 提交信息和推送到 GitHub 的变更描述统一使用中文。

## Inline terminal input

- Keep chat-frame decoration and the empty-input hint in JLine's display. All three task modes share the line editor. Esc clears a draft or cancels an armed Plan/Team mode when the input is empty. Ctrl+C cancels a running CLI task, and a second press within two seconds exits.
- Show model and `ctx` (percentage and progress bar) on the first status row with the directory aligned right. Only show the approval badge when HITL is enabled, the default for new interactive sessions. Distribute the environment and recent request `in/out/cache`, cost, and elapsed time on the second row. Keep the three token fields visible before any reported usage and retain the latest reported values when refreshing the environment.
- Validate interactive input and the status layout with `mvn test -Pphase16-smoke`.

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
