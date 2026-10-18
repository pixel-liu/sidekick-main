# 迭代记录

按日期倒序记录项目变更、验证结果和待处理问题。

## 2026-10-18 — 内置 debugging Skill

### 设计审查与调整

- 简单、局部故障采用短流程；仅复杂或不确定问题维护假设与实验，避免强制计划、多个假设和全量检查。
- 区分诊断授权与修改授权、临时缓解与根因修复、相关测试通过与原始故障已验证，明确证据不足及环境受阻时的交付方式。
- 核对现有工具边界：`load_skill` 正文上限为 5,120 字符，文件工具限制在项目根目录内。首版将分类排查方法保留在自包含正文中，不依赖读取项目外的参考资料。

### 已完成

- 新增 `src/main/resources/skills/debugging/SKILL.md`，覆盖故障基线、代码定位、假设实验、构建/运行/并发/性能/集成分类排查、必要范围修复、回归验证及完成判断。
- 在 `SkillBuiltinExtractor` 注册第二个内置 skill；沿用三层覆盖、启停和正文注入机制，未增加工具或命令。
- 增加真实内置资源的解压、解析、发现、完整注入及一次消费集成检查，并验证已有同版本 `web-access` 缓存旁可正常新增 `debugging`。
- 同步 README、AGENTS.md 和 SideKick.md，补充使用入口、资源登记约定及验证命令。

### 验证结果

- `mvn test "-Dtest=Skill*Test,LoadSkillToolTest" -DskipTests=false`：49 项通过，0 失败、0 错误、0 跳过。
- `skill-creator/scripts/quick_validate.py src/main/resources/skills/debugging`：通过；PyYAML 临时依赖安装于被忽略的 `target/skill-validator-deps/`。
- `mvn package -DskipTests=true`：构建成功；此命令只作打包验证，测试结果来自上面的专项回归。
- 校验可执行 JAR 及 ZIP、tar.gz 内 JAR 的 `skills/debugging/SKILL.md`，均与源文件字节一致。
- `git diff --check`：通过。

### 遗留与边界

- 本轮完成指南设计审查、规范校验、加载链路与打包验证，未运行全量回归或调用真实模型进行故障修复效果评测。
- 工作区原有 `Main.java` 修改和未跟踪的 `output/` 保留，不纳入本次提交。

## 2026-10-16 — 安装脚本与 GitHub Release 第二版

### 已完成

- 增加 Windows PowerShell 安装器，支持安装最新或指定 GitHub Release、校验下载文件 SHA-256、本地归档冒烟测试、Java 17 检查、安全替换旧版本，以及幂等写入用户 PATH。
- 增加 macOS/Linux 安装器，支持相同的版本、校验和与本地归档安装流程，默认安装到用户目录、创建 `~/.local/bin/sidekick` 链接，并按需更新 shell profile。
- 增加版本标签触发的 GitHub Actions 发布流程：运行 quick 回归、构建带版本号的 ZIP/tar.gz、生成 SHA-256 文件并创建 GitHub Release。
- Maven 发行包名称与根目录支持通过 `distribution.version` 覆盖；README 和发行包安装说明补充一键安装、手动安装及 API Key 边界。

### 验证结果

- `mvn clean package "-Ddistribution.version=1.0.0"` 构建成功，ZIP 与 tar.gz 的文件名和包内根目录均正确使用 `1.0.0`。
- Windows 安装器通过 PowerShell 语法解析、本地 ZIP 首次安装及覆盖升级冒烟；测试使用 `-NoPathUpdate`，未改动当前账户 PATH。
- macOS/Linux 安装器通过 WSL `bash -n`、Java 11 拒绝路径、本地 tar.gz 首次安装与覆盖升级、命令软链接及隔离 HOME 下的 shell profile PATH 更新冒烟。
- 首次 `v1.0.0` 标签运行发现 Linux CI 会在 ANSI 样式已初始化时把终端控制码计入表格行长断言；测试现按可见文本宽度判断，不再依赖测试类执行顺序。
- `mvn test -Pquick`：740 项测试通过，0 失败、0 错误；`git diff --check` 通过。
- `v1.0.1` GitHub Release 工作流在线运行成功，正式 Release 已生成，包含 Windows/macOS/Linux 安装脚本、ZIP、tar.gz 及同时覆盖两个归档的 SHA-256 校验文件。

### 遗留与边界

- 本轮按需求暂不增加 `sidekick doctor`，也不提供 Docker 镜像；安装器仍要求系统预先安装 Java 17 或更高版本。
- 线上完整 ZIP 下载验收因当前环境连接 GitHub Release CDN 速度过慢而中止；已清理不完整下载。本地 ZIP/tar.gz 安装、线上 Linux 构建和 Release 资产生成均已分别验证。

## 2026-10-16 — 本机命令发行包第一版

### 已完成

- 增加 Windows `sidekick.cmd` 与 macOS/Linux `sidekick` 启动器，通过脚本自身位置定位 fat JAR，并保留用户执行命令时的当前工作目录作为项目根。
- 接入 Maven Assembly，在常规 `mvn package` 后同时生成 ZIP 与 tar.gz 发行包；包内包含跨平台启动器、`lib/sidekick.jar`、安装说明、README 和 `.env.example`。
- README 增加发行包安装、源码构建和 `sidekick` 命令使用说明。

### 验证结果

- `mvn clean package` 构建成功，生成 `sidekick-1.0-SNAPSHOT.zip` 与 `sidekick-1.0-SNAPSHOT.tar.gz`；归档结构、fat JAR 主类及 tar.gz 中启动器的 `0755` 权限均符合预期。
- 从 ZIP 解压后运行 Windows 启动器，能够跨目录定位并启动 `lib/sidekick.jar`；隔离用户配置后按预期进入应用的“缺少 API Key”退出路径。macOS/Linux 启动器通过 WSL `bash -n` 语法检查。
- `mvn test -Pquick`：740 项测试通过，0 失败、0 错误；`git diff --check` 通过。

### 遗留与边界

- 第一版仍要求用户预先安装 Java 17 或更高版本，暂未提供内置 JRE、平台安装器、自动写入 PATH 或自更新能力。

## 2026-10-09 — 长期记忆容量控制、评分与哈希去重

### 已完成

- 为长期记忆增加 `importance`、`confidence`、`access_count`、`updated_at`、`last_accessed_at` 和 `content_hash`；旧 JSON 自动补齐默认字段并重建哈希索引。
- 正文按 Unicode NFC 归一化后计算 UTF-8 SHA-256，在同一作用域和项目内去重；重复保存保留原记录身份与创建时间，分别取两项评分最大值并刷新更新时间。
- 增加 `Sidekick.memory.max.entries`（默认 1000）和可选的 `Sidekick.memory.max.tokens`，保存及启动加载时按重要性 40%、置信度 30%、有界召回频率 15%、时间新鲜度 15% 的综合分淘汰超限记录。
- 按 ID 读取、搜索返回及模型实际召回时持久化访问统计；列表和候选扫描不计数，模型上下文中因 token 预算未注入的候选不计数。
- `save_memory` 支持可选的 0–1 评分参数，三种 Agent 模式接入带评分保存器；补充九项回归测试，覆盖 Unicode 等价、评分合并、容量淘汰、迁移、召回计数、并发及作用域隔离。
- 更新 README、共享提示词和项目记忆；新增 AGENTS.md，约定每轮代码迭代记录变更、验证后提交并推送 GitHub。

### 验证结果

- 记忆、工具、三种 Agent 与提示词专项回归：89 项测试通过，0 失败、0 错误。
- 随本次 main 推送的既有启动横幅提交：MainInputNormalizationTest 的 28 项测试通过。
- 变更格式检查：`git diff --check` 通过。

### 遗留与边界

- 本轮执行相关专项回归，未运行全量测试。
- 容量上限对整个长期记忆文件生效，旧数据启动迁移也会按上限淘汰；新增记录参与同一排序，单条超过 token 上限时可能立即被淘汰。

## 2026-10-08 — 启动横幅字形优化

- 将启动界面的 SK 标识调整为统一宽度的六行字符画，改善 S 的轮廓和 K 的斜笔画，并对齐右侧产品名称、模型和运行状态。
- 保留原有配色、启动提示和开放式布局。
- 验证：`MainInputNormalizationTest` 的 28 项测试全部通过，`git diff --check` 通过。

## 2026-10-07 — README 精简与重组

### 已完成

- 将 README 从 405 行收敛为 123 行，删除重复的能力罗列、实现细节和长篇环境示例。
- 保留项目介绍、快速开始、常用命令、工具与安全边界、配置位置、Runtime API、测试命令和项目结构等使用入口。
- 统一文档中的用户与项目配置目录为实际使用的 `.sidekick` 路径，并保留 `SideKick.md` 项目记忆约定。

### 验证结果

| 检查 | 结果 |
| --- | --- |
| 文档结构与链接 | 已人工核对 |
| 变更格式检查 | `git diff --check` 通过 |

## 2026-10-06 — 跨平台兼容与运行时可靠性修复

### 已完成

- **Windows 路径与输出**：修复 `file://` 图片引用中的盘符、反斜杠和 UNC 路径解析；代码搜索展示路径统一为 `/`；换行符和项目路径断言改为跨平台写法。
- **Runtime API**：所有响应与事件统一使用 Jackson 生成 JSON，控制字符不再破坏响应；格式错误的请求返回 HTTP 400；turn 执行使用独立有界线程池，队列满时返回 HTTP 429，避免无限创建线程。
- **文件与命令工具**：`read_file` 改为流式读取，整文件输出限制为 60,000 个字符；范围读取不再将整个文件载入内存；命令超时、取消和异常时终止完整进程树，避免 Windows 子进程继续占用工作目录。
- **记忆可靠性**：长期记忆通过同目录临时文件原子替换，删除和清空操作同步化；项目记忆继续以 `SideKick.md` 系列为主并兼容 `PAI.md` 系列，`/init` 生成 `SideKick.md`。
- **测试环境**：测试临时目录、日志和审计输出统一放入 `target/`，修复 Windows 路径围栏、只读数据库和平台换行导致的回归失败；代码搜索黄金数据改用平台无关路径。

### 验证结果

| 检查 | 结果 |
| --- | --- |
| 常规快速回归 | 731 个测试全部通过，0 个失败、0 个错误 |
| 命令工具专项回归 | 19 个测试全部通过，0 个失败、0 个错误 |
| 代码搜索黄金用例 | 1 个测试通过 |
| 变更格式检查 | `git diff --check` 通过 |

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

### 后续处理

本节记录的 Windows 回归失败，以及 Runtime API JSON、文件流式读取、记忆原子保存和接口并发上限，已在同日后续迭代中解决，见上方“跨平台兼容与运行时可靠性修复”。
