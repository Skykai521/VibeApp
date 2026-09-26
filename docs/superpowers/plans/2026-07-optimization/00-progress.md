# 2026-07 全局优化 · 进度总控(所有执行者从这里开始)

> **本文件是整个优化工程的唯一进度权威。**
> 任何模型/任何会话接手开发时,第一步永远是读本文件;每完成一个 Task 或 Phase,最后一步永远是更新本文件。
> 需求与诊断依据:`docs/optimization-review-2026-07.md`(下称"评审文档")。
> 代码基线:`dev` 分支 `be1f944`。各 phase 文档中的 `file:line` 引用基于该基线,**动手前必须用 grep 重新定位**,不要盲信行号。

---

## 1. 接力协议(必须遵守)

### 新会话开工流程

1. 读本文件 §2 状态总表,找到"进行中"或下一个"未开始"且前置已满足的 Phase;
2. 读对应的 `phase-N-*.md` 全文,从第一个未勾选的 Task 开始;
3. 用 grep/read 核实该 Task 引用的代码现状(可能已漂移),如与计划描述不符,先在该 phase 文档的"实施记录"中说明,再决定是否调整方案;
4. 建议用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 技能执行。

### 每完成一个 Task

1. 勾选 phase 文档中该 Task 的所有 checkbox;
2. 按 Task 的"验证"节运行命令,确认通过(**没有跑验证不允许勾选**);
3. 独立 commit(提交规范见 §5);
4. 更新本文件 §2 状态表的"当前位置"列。

### 每完成一个 Phase

1. 运行全量验证:`./gradlew test && ./gradlew :build-engine:test && ./gradlew assembleDebug`;
2. 涉及 UI/设备行为的 phase,按 phase 文档"人工验证清单"在 Android 10+ 真机/模拟器过一遍;
3. 在 phase 文档末尾"实施记录"表中追加一行总结(日期、执行者、偏离说明);
4. 更新本文件 §2 状态表:状态改为 `✅ 已完成`,填写完成日期;
5. commit 消息:`docs: mark optimization phase N complete`。

### 偏离处理

计划不是圣旨,代码现实优先。但**禁止静默偏离**:任何与计划不一致的实现(改了方案、跳过了步骤、发现计划错误),都必须写进该 phase 文档的"实施记录"表,一句话说清"计划怎么说、实际怎么做、为什么"。

---

## 2. 状态总表

> 状态取值:`⬜ 未开始` / `🔵 进行中` / `✅ 已完成` / `⏸ 暂停(备注写原因)`

| Phase | 文档 | 主题 | 前置依赖 | 预估 | 状态 | 当前位置 | 完成日期 |
|-------|------|------|----------|------|------|----------|----------|
| 1 | [phase-1](./phase-1-agent-loop-reliability.md) | Agent Loop 可靠性止血 | 无 | ~1 周 | 🔵 进行中 | 代码完成·9 Task 全审查通过·PR→dev,待真机 5 项验证 | — |
| 2 | [phase-2](./phase-2-context-web-hotfix.md) | Context 与 Web 止血包 | 无 | ~1 周 | 🔵 进行中 | 代码完成·7 Task 全审查通过·整支终审 With fixes(1 回归+1 Minor 已修复复核)·[PR #26](https://github.com/Skykai521/VibeApp/pull/26)→dev,待真机 4 项验证 | — |
| 3 | [phase-3](./phase-3-debug-experience.md) | 调试体验强化(截图/崩溃推送/DebugBridge) | 无 | ~1.5 周 | 🔵 进行中 | 代码完成·7 Task 全独立 commit·`:app`+`:build-engine` 测试全绿+`assembleDebug`·[PR #27](https://github.com/Skykai521/VibeApp/pull/27)→dev,待真机 7 项验证 | — |
| 4 | [phase-4](./phase-4-context-refactor.md) | Context 核心重构(淘汰/持久化/校准/预算) | Phase 2 | ~2 周 | ⬜ 未开始 | — | — |
| 5 | [phase-5](./phase-5-web-search-providers.md) | Web 搜索 Provider 化与内容管理 | Phase 2 | ~1.5 周 | ⬜ 未开始 | — | — |
| 6 | [phase-6](./phase-6-performance-and-cost.md) | 性能与成本(prompt cache/R.class 缓存/Room) | 无硬前置(6.3 与 4.3 有衔接) | ~1.5 周 | ⬜ 未开始 | — | — |
| 7 | [phase-7](./phase-7-plugin-and-engineering-debt.md) | 插件根治与工程还债(ASM/多 Activity/重构) | 无(7.2 依赖 7.1) | ~3 周 | ⬜ 未开始 | — | — |

**推荐执行顺序**:1 → 2 → 3 → 4 → 5 → 6 → 7。Phase 1/2/3 相互独立,可并行;Phase 4/5 依赖 Phase 2 的接口;Phase 6 的 Room 任务(6.3)在 Phase 4.3 之后收益最大但不硬依赖;Phase 7 独立可穿插,但 7.3 建议在 Phase 1 完成后执行(改同一批 gateway 文件)。

### Phase 1 遗留跟进(整支审查产出,2026-07-04)

> Phase 1 代码已完成并通过逐 Task + 整支审查(Opus:Ready to merge)。以下为审查确认的**非阻塞跟进项**,合并后处理:

- **Task 1.8b(建议新增)**:`ModelSummaryStrategy`/`ConversationCompactor`(`ModelSummaryStrategy.kt:36-38` 的 `var apiUrl/token/model`)存在与 Task 1.8 同类的凭证竞态,只是上移一层(压缩摘要路径)。Task 1.8 范围未含,未被其引入/恶化。仿 1.8 把 `compact()`/`callSummarizationAPI` 的 token/apiUrl/model 改为逐调用参数。
- **集成测试补齐(建议)**:Phase 1 最高风险控制流仅由编译+人工审查覆盖。建议补 3 个测试:(a) coordinator retry 环——429→2 次重试→LoopFailed / 401→0 重试 / delay 期间取消可传播;(b) 截断续写——`Completed(truncatedByMaxTokens=true)` 且无 pendingCalls 时注入续写消息并只推进一次迭代;(c) SSE 终止检测——伪 channel 发 delta 后无终止信号收尾→恰好一个 stream_interrupted,错误事件后收尾→零。
- **可选硬化**:`RETRY_DELAYS_MS.getOrElse(...)` 解耦 `MAX_MODEL_RETRIES`;Responses gateway 补 `truncatedByMaxTokens`;分类器把已知瞬时 SSE 错误类型(如 `overloaded_error`)也视为可重试。
- **待真机验证(标 ✅ 前必做)**:见 phase-1 文档"Phase 完成检查"的 5 项人工清单。
- **既有环境问题(与 Phase 1 无关)**:`./gradlew test`(全模块)在 vendored `build-tools/android-common-resources` 因 `getModuleSourceSets()` 符号不匹配编译失败,该模块本分支未碰、早于本分支存在。Phase 1 交付以 `:app:testDebugUnitTest` + `:build-engine:test` + `assembleDebug` 为准(全绿)。

### Phase 2 遗留跟进(整支终审产出,2026-07-04)

> Phase 2 代码已完成并通过逐 Task + 整支终审(Opus:With fixes → 已修复复核 Approved)。合并前回归已在分支内修复;以下为终审分诊为**非阻塞**的跟进项(follow-up/won't-fix),合并后按需处理:

- **已在分支内修复(非遗留,供追溯)**:(1) Task 2.4 ASSISTANT 角色摘要引入的 Anthropic「首条须 user」400 回归 → `AnthropicMessagesAgentGateway.ensureLeadingUserMessage` 守卫(@fe266e0);(2) Responses 诊断 `messageCount` 少报(@3411fb5)。
- **测试补齐(follow-up)**:coordinator 级 Responses 重置(主循环+收尾)无自动化测试(本项目无 coordinator 单测 harness,2.1 仅测纯函数 `selectResponsesInput`);web-trim 仅 happy-path 测试(未测"近期回合不动"/畸形 payload)。
- **死代码(follow-up)**:废弃类 `ConversationContextManager.splitIntoTurns/summarizeTurn` 仍有与 2.4 同类的头部丢弃 bug,本次未动(疑似死代码);若该路径复活需修,或直接删除。
- **cosmetic(won't-fix / 择机)**:`deliveredRangeEnd` 在 char 截断跨行时 `range.end` 至多多算一行(保守、已封顶);`clampFileContent` 两次 `lines()`;2.7 选择器轮询探测 11 次 vs 名义 10(延迟界仍 3s);`EngineCircuitBreaker.blockedUntil` 不清理过期项(仅 3 引擎)。
- **共享接口(Phase 5 复用)**:`WebFailureKind` / `EngineFailure` / `WebSearchFailedException` / `EngineCircuitBreaker` 已就绪,终审确认公共面设计良好。
- **待真机验证(标 ✅ 前必做)**:见 phase-2 文档"Phase 完成检查"的 4 项人工清单。

### Phase 3 遗留跟进(代码完成,2026-07-04)

> Phase 3 全 7 Task 代码完成,分支 `opt/phase-3-debug-experience`,逐 Task 独立 commit,`:app:testDebugUnitTest`(全套件)+`:build-engine:test`+`assembleDebug` 全绿。开工前用 Explore agent 全量核实锚点,修正了若干计划错误(详见 phase-3 文档"实施记录")。以下为合并前后需注意项:

- **必须一起合并的构建产物**:`build-engine/src/main/assets/shadow-runtime.jar` 已随 3.4/3.5 的 shadow-runtime 源码改动重生成并提交(单独 commit)。生成 app 编译链接此 jar,漏合会导致新 runtime API 缺失。
- **计划偏差(已在分支内处理,供追溯)**:(1) `ToolResultContent.content` 由 `String` 改 `List<MessageContent>` 以支持 tool_result 嵌图(3.2);(2) `PluginLaunchProxyActivity` 继承 `ComponentActivity`(Hilt 要求,非计划的 `Activity`)(3.3);(3) DebugBridge 证书校验基于 AOSP testkey 的 DER SHA-256(`a40da80a…bf5dc`),非计划设想的 keystore/keytool(3.6)。
- **共享接口就绪**:`DebugReportProvider`/`DebugReportValidator`(注册表第 3/7 行)已按注册表命名交付,authority `com.vibe.app.debugreport`。
- **非视觉 provider 降级**:capture_screenshot 的图片仅 Anthropic gateway 内联进 tool_result;其它 gateway 忽略 attachments,模型见文本 note(设计如此,无需改)。
- **待真机验证(标 ✅ 前必做)**:见 phase-3 文档"Phase 完成检查"的 7 项人工清单(崩溃推送/截图/后台通知/getIntent/生命周期/安装模式回流/独立模式回归)。

---

## 3. 各 Phase 一句话范围

| Phase | 解决什么 | 评审文档章节 |
|-------|----------|--------------|
| 1 | 模型请求重试、SSE 截断检测、超时分级、快照 NonCancellable、工具超时、edit 语义、参数解析报错、API 单例竞态、日志门控 | §4 P0 全部 + §4-11/12 |
| 2 | OpenAI 绕过压缩、read/build 输出无上限、摘要不验预算、摘要角色、web 结果不裁剪、搜索拦截不可感知、引擎熔断、渲染等待 | §3.3 第一步 + §2.2 B/C1 |
| 3 | 崩溃主动推送、PixelCopy 截图、launch_app 前台限制、getIntent、生命周期补齐、安装模式调试回传通道(DebugBridge) | §1.3 方向一/方向二 |
| 4 | 回合内工具结果淘汰(microcompact)、压缩缓存、结构化持久化、token usage 校准、预算随模型配置 | §3.3 第二/三/四步 |
| 5 | 搜索后端 Provider 化(博查/Tavily/Brave/SearXNG + 内置爬取兜底)、设置 UI、fetch 落盘分页 | §2.2 A/C2/C3 |
| 6 | Anthropic prompt caching、R.class 缓存、Room FTS5 + 大字段外置、会话节流落库 | §4-8/9/10/13 |
| 7 | ASM AndroidX 改写(解锁 Fragment)、插件多 Activity、Gateway 基类合并 + 死代码清理、ScreenRouter 与新模板 | §1.3 方向三 + §4-14/15 |

---

## 4. 共享命名注册表(跨 phase 接口,先到先得,后续 phase 必须沿用)

新建类型统一使用下列名称,避免两个 phase 各造一套:

| 名称 | 定义于 | 被使用于 | 说明 |
|------|--------|----------|------|
| `WebFailureKind` | Phase 2(Task 2.6) | Phase 5 | 枚举:`BLOCKED` / `NO_RESULTS` / `TIMEOUT` / `NETWORK_ERROR`,web 工具结构化失败原因 |
| `EngineCircuitBreaker` | Phase 2(Task 2.7) | Phase 5 | 搜索引擎失败冷却器 |
| `CurrentRunToolResultEvictor` | Phase 4(Task 4.1) | — | 回合内工具结果淘汰(microcompact) |
| `TurnArtifactEntity` / `TurnArtifactDao` | Phase 4(Task 4.3) | Phase 6(6.3) | 回合结构化产物(文件清单/build 状态/错误/plan)Room 表 |
| `TokenRatioCalibrator` | Phase 4(Task 4.4) | — | 基于 API usage 回传的 token 估算校准器 |
| `ContextBudgetResolver` | Phase 4(Task 4.5) | — | 预算解析:PlatformV2 配置 > provider 默认表 |
| `WebSearchProvider` | Phase 5(Task 5.1) | — | 搜索后端接口;实现:`BuiltInSerpProvider` / `BochaSearchProvider` / `TavilySearchProvider` / `BraveSearchProvider` / `SearxngSearchProvider` |
| `WebFetchCache` | Phase 5(Task 5.5) | — | fetch_web_page 落盘缓存(`.web-cache/<hash>.md`) |
| `RClassCache` | Phase 6(Task 6.2) | — | R.class 编译产物缓存(key = 资源输入 hash) |
| `DebugReportProvider` / `DebugReportValidator`(宿主) | Phase 3(Task 3.6/3.7) | — | 安装模式调试回传通道;exported provider + 调用方签名 SHA-256 校验(**不用** signature 权限,证书不同永不匹配);模板侧由 `CrashHandlerApp` 直接上报 |
| `BaseChatCompletionsGateway` | Phase 7(Task 7.3) | — | Kimi/Qwen/DeepSeek gateway 公共基类 |

---

## 5. 提交与分支规范

- 分支:从 `dev` 拉特性分支,命名 `opt/phase-N-<slug>`(如 `opt/phase-1-retry`);一个 phase 可以一个分支,也可以按 Task 拆,合回 `dev` 走 PR(见 `CONTRIBUTING.md`)。
- Commit 前缀沿用仓库习惯:`feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `test:`;每个 Task 至少一个独立 commit,消息里带 Task 编号,如 `fix(agent): add model request retry with backoff (opt task 1.1)`。
- **禁止**把多个 Task 揉进一个 commit——接力者需要靠 commit 历史对齐进度。

## 6. 全局验证命令

| 场景 | 命令 |
|------|------|
| 单元测试 | `./gradlew test` |
| build-engine 测试 | `./gradlew :build-engine:test` |
| 编译健全性 | `./gradlew assembleDebug` |
| 设备验证 | Android 10+ 真机/模拟器,按各 phase"人工验证清单" |

## 7. 背景阅读地图(接手前建议浏览)

| 文档 | 用途 |
|------|------|
| `docs/optimization-review-2026-07.md` | 本工程的需求与全部问题诊断(含 file:line 证据) |
| `docs/architecture.md` | 模块边界与运行时流程 |
| `docs/context-compaction-redesign.md` | 上下文压缩现行设计(Phase 4 的前身) |
| `docs/known-issues/fragment-in-plugin-mode.md` | Fragment 崩溃根因(Phase 7.1 的动机) |
| `docs/superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md` | ASM 改写既有计划(Phase 7.1 直接执行它) |
| `docs/webview-crawler-research.md` | WebView 爬虫调研(Phase 5 背景) |
| `CLAUDE.md` | 仓库约定(SDK 版本、目录、测试命令) |
