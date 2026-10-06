# 迭代记录

按日期倒序记录项目变更、验证结果和待处理问题。

## 2026-10-06 — Skill 注入、索引重建与记忆去重修复

### 已完成

- **Skill 注入**：移除引导语中写死的“PDF 任务”，改为继续用户当前任务；删除重复拼接的正文，每次注入只保留一份技能指引。同步 `load_skill` 工具说明及测试中的注入格式。
- **索引重建**：遍历、分块、向量生成或关系分析失败时停止重建，保留原有索引并提示失败；代码块和关系在同一个数据库事务中替换，写入异常时整体回滚。补充根目录校验，允许显式选择隐藏目录作为索引根目录。
- **长期记忆去重**：相同内容仅在相同作用域和相同项目内去重；不同项目、全局与项目作用域可以分别保存相同事实。无作用域的历史记忆继续视为全局记忆，单实例保存过程加锁以避免并发重复写入。
- **测试与文档**：补充失败回滚、成功替换、跨项目隔离和记忆重新加载等回归用例；索引测试使用独立临时目录和模拟向量服务，避免依赖本机 Ollama。同步 README 和项目维护说明。

### 验证结果

| 检查 | 结果 |
| --- | --- |
| 最终针对性测试 | 44 个测试全部通过，0 个失败、0 个错误 |
| Maven 打包 | 成功，更新 `target/Sidekick-1.0-SNAPSHOT.jar` |
| 常规快速回归 | 本次执行 725 个测试，6 个失败、5 个错误，未全绿 |
| 变更格式检查 | `git diff --check` 通过 |

针对性测试覆盖 `SkillContextBufferTest`、`LoadSkillToolTest`、`LongTermMemoryTest`、`CodeIndexTest` 和 `VectorStoreTest`。运行方式：

```bash
mvn package -Dtest=SkillContextBufferTest,LoadSkillToolTest,LongTermMemoryTest,CodeIndexTest,VectorStoreTest -DskipTests=false
```

常规回归使用 `mvn test -Pquick`，在最后补充的一个运行时异常回滚测试加入前执行；最终 44 个针对性测试包含该补充用例。

### 待处理

常规回归中的以下问题在本轮之前已出现，本轮仍未解决：

- `ImageReferenceParserTest`：Windows `file://` 图片路径相关的 3 个测试失败。
- `MemoryManagerTest`：项目路径断言失败，涉及跨平台路径格式。
- `ProjectMemoryLoaderTest`：用户、项目及本地记忆加载顺序用例失败，原因待进一步定位。
- `InlineRendererTest`：换行符断言与 Windows 输出不一致。
- `PathGuardTest`：4 个用例因临时目录访问权限报错。
- `CodeRetrieverTest`：测试数据库只读导致初始化报错。

前期代码检查还发现以下改进项，本轮未修改：Runtime API 的 JSON 控制字符转义、大文件流式读取、长期记忆文件原子保存，以及接口并发上限。
