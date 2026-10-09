# Repository instructions

Read SideKick.md for the shared architecture and development commands.

## Iteration workflow

- 每次迭代项目代码，都必须在 CHANGELOG.md 中记录本次变更、验证结果和遗留问题；完成相关验证后提交本次迭代的代码与文档，并推送到当前 GitHub 仓库的对应分支。

## Long-term memory

- Preserve project/global visibility boundaries. Deduplicate with (scope, project, SHA-256 of UTF-8 Unicode NFC content).
- Duplicate saves retain identity and creation time; merge importance/confidence with max and refresh updated_at.
- Enforce Sidekick.memory.max.entries (default 1000) and optional Sidekick.memory.max.tokens after saves and startup migration using the documented retention score.
- Count only returned recalls or memories actually injected into the model context. Administrative list/scoring scans must not affect recall statistics.
- Maintain compatibility with legacy JSON; persist via same-directory temporary file and atomic replacement.
- Validate memory changes with: mvn test "-Dtest=MemoryEntryTest,LongTermMemoryTest,LongTermMemoryRetentionTest,MemoryRetrieverTest,MemoryManagerTest,ToolRegistryTest,PromptAssemblerTest" -DskipTests=false