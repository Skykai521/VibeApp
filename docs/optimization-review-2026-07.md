# VibeApp 全局优化评审(2026-07)

> 日期:2026-07-03
> 状态:**已细化为实施计划**——执行入口见 [`superpowers/plans/2026-07-optimization/00-progress.md`](./superpowers/plans/2026-07-optimization/00-progress.md)(Phase 1-7 分册 + 进度总控,任何模型/会话接手先读它)
> 基线:`dev` 分支 `be1f944`
> 方法:针对三个用户痛点(插件调试、Web 搜索、上下文压缩)+ 一次全局工程扫描,共四路并行代码调研,所有结论均有 `file:line` 证据支撑。

本文回答三个问题:**应用内调试为什么弱、Web 搜索为什么鸡肋、上下文管理为什么一直做不好**,并给出修复路线;附全局扫描发现的其他 15 个优化点和整体路线图。

---

## 0. 摘要

| 痛点 | 根因(一句话) | 核心对策 |
|------|------|------|
| 应用内调试弱、四大组件不全 | 预览机制是约 390 行的自研 mini-Shadow,只代理单 Activity 且生命周期转发不完整;Service/Receiver/Provider 是零支持,补全等于重写一个插件框架 | **双轨定位**:插件预览只承诺"UI 快速迭代",全功能调试走真实安装 + 新增"调试回传通道"(同签名 ContentProvider 回传 crash/log);插件模式做定向补强(截图、生命周期、主动崩溃推送);Fragment 问题按既有 ASM 改写计划根治 |
| Web 搜索被拦、内容撑爆 context | WebView 爬 SERP 页面 + Jsoup 硬编码选择器,无拦截识别(403/验证码被当"无结果");fetch 的 8K 结果在压缩链中永不裁剪 | 接入**可配置的正式搜索 API**(博查/Tavily/Brave/SearXNG),SERP 爬取降级为兜底;fetch 结果落盘分页读取;补上压缩链的 web 工具裁剪分支 |
| Context 压缩一直不好 | 压缩单位是"USER 回合",而真正的膨胀源是**当前回合内的工具结果**(永远在保留窗口内);OpenAI 路径完全绕过压缩;持久化只存平面文本导致跨回合状态丢失 | 压缩单位改为"消息级 + token 预算",实现回合内工具结果淘汰;修复 OpenAI 旁路;结构化持久化;用 API usage 回传校准 token 估算 |

全局扫描另发现 15 个问题,最高优先级的四个:**模型请求零重试**(一次 429 杀死整轮)、**SSE 中断被当正常完成**(静默截断)、**取消会话丢快照**(finally 未包 NonCancellable)、**`edit_project_file` 零匹配仍返回成功**。

---

## 1. 应用内调试(插件预览 / shadow-runtime)

### 1.1 现状:一个自研的 mini-Shadow

先澄清一个容易误解的事实:虽然包名是 `com.tencent.shadow.core.runtime`,但项目**并没有接入腾讯 Shadow 框架**。`shadow-runtime/` 是一个仅 6 个 Java 文件、约 390 行的自研精简运行时,配合 `app/src/main/kotlin/com/vibe/app/plugin/` 下 4 个宿主类工作。`docs/shadow-plugin-feasibility.md` 中规划的 Loader/Manager/字节码变换均未实施(`BuildMode` 枚举至今只有 `STANDALONE`,见 `build-engine/.../BuildModels.kt:32-34`)。

运行机制(插件预览模式):

1. `PluginManager.launchPlugin`(`PluginManager.kt:49-72`)分配 5 个进程槽位(`:plugin0..4`,LRU 淘汰)之一,启动对应的 `PluginSlotN` 容器 Activity;
2. 容器用 `DexClassLoader(signed.apk) → ShadowBridgeClassLoader → boot` 加载插件类——桥接器只共享 `com.tencent.shadow.core.runtime.*`,androidx/material 全部从插件 dex 自己的副本加载(`PluginResourceLoader.kt:88-109`,注释记录了共享 androidx 导致 `VerifyError` 的历史坑);
3. 反射 `addAssetPath` 构造插件专属 Resources,反射实例化主 Activity,`performCreate` 时 `attachBaseContext(宿主 Activity)` 并**跳过 `super.onCreate`**(插件 Activity 从未被系统 attach,真调会崩,`ShadowActivity.java:53-93`);
4. 生命周期由容器**手动转发**,同进程的 `PluginInspectorService` 通过 AIDL 提供视图树 dump 和 11 种 UI 自动化动作。

### 1.2 支持矩阵与调试信号现状

| 能力 | 现状 | 证据 |
|------|------|------|
| Activity | **部分支持,仅 1 个**。Manifest 解析只取第一个 Activity(`PluginManager.kt:120-129`);只转发 create/resume/pause/stop/destroy/activityResult,**缺 onStart/onSaveInstanceState/onNewIntent/onConfigurationChanged/权限回调**;返回键直接 finish 容器;`getIntent` 返回的是容器 intent | `PluginContainerActivity.kt:141-205`,`ShadowActivity.java:280-283` |
| Service / IntentService | **零支持**。`ShadowService`/`ShadowIntentService` 是全工程无引用的死 stub | `shadow-runtime/` 全目录 grep |
| BroadcastReceiver | **零设计支持**。动态注册"碰巧可用"(落在宿主 Context 上),静态注册完全不触发 | `ShadowActivity.java:61` |
| ContentProvider | **零支持** | Manifest 解析仅用 `GET_ACTIVITIES` |
| Fragment 全家桶 | **崩溃**(`NoSuchMethodError`),根因是类身份分裂 + super.onCreate 被跳过;目前靠系统提示词禁用 | `docs/known-issues/fragment-in-plugin-mode.md` |
| 崩溃捕获 | 双保险(生命周期 try/catch + UncaughtExceptionHandler)写 `crash.log`,但**回传是被动的**:仅 ChatScreen ON_RESUME 时检测文件增长,再等用户手动点"自动修复" | `PluginContainerActivity.kt:319-369`,`ChatViewModel.kt:447-483` |
| 应用日志 | 仅模板 `AppLogger` 显式打点,无 logcat 采集 | `PluginContainerActivity.kt:335-350` |
| 截图 | **未实现**(返回 "screenshot not implemented yet"),agent 只能"摸树"不能"看图" | `PluginInspectorService.kt:82-84` |
| 独立安装模式的崩溃 | 写在生成应用自己的沙箱,**VibeApp 读不到**,只能让用户手动复制粘贴 | 模板 `CrashHandlerApp.java:37-44` |

### 1.3 优化方案

关键判断:**在自研 mini-Shadow 上补全四大组件是不划算的**。Service 需要进程内 AMS 代理、静态 Receiver 需要 Manifest 解析 + 事件总线、ContentProvider 需要 URI 重定向——这等于把腾讯 Shadow 的 Loader 层重写一遍,而腾讯 Shadow 官方对后两者的支持也不完整。四大组件的正确出路不是"在插件模式里模拟系统",而是"让真实系统跑,把调试信号引回来"。

#### 方向一(推荐,解决四大组件):双轨定位 + 安装模式调试回传通道

明确两种运行模式的分工,并补齐安装模式缺失的调试链路:

- **插件预览** = UI 快速迭代:秒级启动、免安装确认、支持 inspect/interact 自动化。承诺范围就是"单 Activity 的 UI 层",不再试图扩展组件支持。
- **安装运行** = 全功能验证:Service、Receiver、Provider、多 Activity、权限、通知……全部由真实系统支持,无需任何模拟。

安装模式目前的唯一缺口是**调试信号回不来**。方案:**调试回传通道(DebugBridge)**

1. 生成的 APK 与 VibeApp 使用同一把 debug 签名(`DebugApkSigner`),因此可以用 `protectionLevel="signature"` 的自定义权限建立可信通道;
2. VibeApp 声明一个受该权限保护的 `DebugReportProvider`(ContentProvider);
3. 模板中已有的 `CrashHandlerApp` / `AppLogger` 增加一条上报路径:崩溃与关键日志通过 `ContentResolver.insert()` 推送给 VibeApp(带 projectId 标识,失败静默降级为本地文件);
4. `read_runtime_log` / `fix_crash_guide` 工具对安装模式同样生效;ChatScreen 的崩溃卡片对安装模式的崩溃同样弹出。

这样"四大组件调试"从一个插件框架难题变成一个模板 + Provider 的小工程(预估 3-5 天),且顺带解决了 1.2 表中最后一行的老问题。Service 等组件的运行状态可通过模板中的 AppLogger 打点回传。

#### 方向二(短期,1-2 周内可做完的定向补强)

按 ROI 排序:

1. **实现截图**:`PixelCopy.request(window, ...)`(API 26+,minSdk 29 满足),Inspector 进程内截取、降采样到 ~720px、压缩 WebP 回传。这是 agent 自测能力的最大单项提升——配合多模态模型,从"摸视图树"升级到"看渲染结果",能发现布局错乱、颜色异常、文字截断这类树上看不出的问题。注意按 provider 能力门控(不支持图片输入的模型继续用视图树)。
2. **崩溃主动推送**:用 `FileObserver` 监听 `logs/crash.log`,插件崩溃立即弹卡片/注入对话,替代现在的 ON_RESUME 被动检测(`ChatViewModel.kt:447-464`)。
3. **补齐生命周期转发**:onStart、onSaveInstanceState/onRestore、onConfigurationChanged、onRequestPermissionsResult;返回键改为先分发给插件(仿 `OnBackPressedDispatcher` 语义),仅默认行为才 finish 容器(`PluginContainerActivity.kt:198-205`)。
4. **`getIntent` 修正**:为插件构造语义正确的 launch intent,而不是暴露容器 intent(`ShadowActivity.java:280-283`)。
5. **`launch_app` 前台限制放宽**:当前硬性要求 VibeApp 在前台(`LaunchAppTool.kt:44-49`),可改为自动把宿主任务拉回前台或允许后台预热,否则后台 agent 无法自测。

#### 方向三(中期,根治 Fragment):落地既有的 ASM 字节码改写计划

`docs/superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md` 已有完整设计:设备端首次插件构建时用 ASM 把 `androidx-classes.jar` 的继承链改写为最终指向 `ShadowActivity`,缓存为 `shadow-androidx-classes.jar`,插件模式的 COMPILE/DEX 使用改写版。收益是一揽子的:Fragment 全家桶、`ViewPager2 + FragmentStateAdapter`、`DialogFragment`、`setSupportActionBar`、`onBackPressed` 等限制同源同解,并可撤掉系统提示词里的大段禁令(`agent-system-prompt.md:15-16`)。预估 3-7 天,是插件模式投入产出比最高的一笔"还债"。

在此基础上,**多 Activity 支持**也变得可行:解析 PluginManifest 全部 activities,`ShadowActivity.startActivity` 拦截插件内显式跳转、转为再次启动容器并携带目标类名(需为 standard launchMode 增设容器,当前 5 个槽位均为 singleTask)。

#### 方向四(评估后不推荐):接入完整腾讯 Shadow 或自研组件代理

完整 Shadow 引入 Loader/Manager 双 APK 动态加载体系,复杂度高,且其核心价值(字节码变换)与方向三重叠;自研 Service/Provider 代理如上所述 ROI 极低。**结论:不做**,四大组件由方向一承接。

---

## 2. Web Search 工具

### 2.1 现状与失败模式

当前链路(`WebSearchExecutor.kt:18-45`):`web_search` 按硬编码顺序 Bing → Baidu → Google,用**隐藏 WebView** 加载 SERP 页面,`onPageFinished` 时立即取 HTML,Jsoup 按引擎特定 CSS 选择器(`li.b_algo` / `div.c-result` / `div.g`)解析出最多 5 条 `title/snippet/url`。`fetch_web_page` 用同一 WebView 机制注入 readability.js + turndown.js 提取正文 Markdown,截断至 8000 字符(`WebConstants.kt:11`)。

三个结构性缺陷:

1. **拦截不可感知**:WebViewClient 只处理了网络层错误(`onReceivedError`),没有 `onReceivedHttpError`——403/429/验证码页被当成正常页面加载成功,解析出 0 条结果,记为 `"no results parsed"` 降级下一个引擎(`WebSearchExecutor.kt:35`,`WebViewContentExtractor.kt:111-122`)。模型永远无法区分"真没结果"和"被拦了",三引擎顺序执行最坏等 60 秒(单引擎超时 20s)。
2. **选择器脆弱 + 时机错误**:SERP 的 DOM 结构随引擎改版随时失效;`onPageFinished` 即取 HTML,JS 后渲染的结果会漏。
3. **内容撑爆 context 的真正原因不在搜索本身**:`web_search` 每次仅 1.5-3KB(title+snippet×5),真正的元凶是 `fetch_web_page` 的 8K 结果(中文约 4000 token)进入历史后**永不裁剪**——压缩链的 `ToolResultTrimStrategy.trimToolPayload` 对 `read_project_file`、`run_build_pipeline` 等都有裁剪分支,唯独没有 web 工具的分支,走 `else -> payload` 原样保留(`ToolResultTrimStrategy.kt:74-93`)。对 Kimi 这类预算 24K token 的 provider,两三次 fetch 就吃掉一半预算。

另外:**零配置**——引擎顺序硬编码、工具无开关、无 API key 接入路径(全 DataStore 与设置 UI grep 无 search 相关项)。

### 2.2 优化方案

#### A. 接入正式搜索 API(首选路径,治本)

SERP 爬取对抗反爬是打不赢的军备竞赛。用户已经在为 LLM 配置 API key,再配一个搜索 key 心智一致。建议做成与模型 provider 同构的可配置项:

| 候选 | 特点 | 适用 |
|------|------|------|
| 博查(Bocha) | 国内直连、中文质量好、按量付费 | 国内用户首选 |
| 智谱 Web Search API | 国内直连,与 GLM 生态一致 | 已用智谱模型的用户 |
| Tavily | 为 LLM 优化(返回可直接投喂的摘要),有免费额度 | 海外用户首选 |
| Brave Search API | 独立索引、免费档 2000 次/月 | 海外备选 |
| SearXNG(自部署 URL) | 免费、聚合多引擎、JSON API | 极客用户 |

注意 Bing Search API 已于 2025-08 退役,不列为候选。设置项:`搜索服务:内置(免费,不稳定)/ 博查 / Tavily / Brave / 自定义 SearXNG`,默认保持现状的内置爬取,配了 key 自动切换。API 返回的结构化 JSON 同时消灭了选择器脆弱性和拦截问题。

#### B. SERP 爬取兜底健壮化(保留为免费降级路径)

1. **拦截识别**:补 `onReceivedHttpError` 捕获 403/429;对返回 HTML 做特征检测(验证码/unusual traffic 关键词),把失败分类为 `BLOCKED / NO_RESULTS / TIMEOUT`;
2. **结构化错误语义**:工具结果告诉模型具体原因和建议("Bing 被验证码拦截,已切换 Baidu"),而不是笼统的 "All search engines failed";
3. **引擎熔断**:被拦的引擎冷却 N 分钟内跳过,避免每次都在死引擎上浪费 20s;
4. **渲染等待**:`onPageFinished` 后轮询目标选择器出现(`evaluateJavascript` 探测)再取 HTML,上限 3s;
5. 可增加对爬虫宽容的端点(如 `html.duckduckgo.com/html`,纯静态 HTML、无 JS challenge)作为第四引擎。

#### C. 内容瘦身(治"撑爆 context")

1. **立即可做**:`ToolResultTrimStrategy.trimToolPayload` 补 `web_search` / `fetch_web_page` 分支(裁剪为 `[Web page: url, N chars — trimmed]` 占位),半天工作量;
2. **fetch 分页化**:`fetch_web_page` 增加 `max_chars`(默认降到 4000)与 `offset` 参数;返回头部附 `总长度/当前区间`,模型按需翻页,而不是一次灌 8K;
3. **落盘 + 按需读取**(推荐):抓取结果写入项目工作区 `\.web-cache/<hash>.md`,工具只返回"标题 + 首屏摘要 + 文件路径",模型需要细节时用已有的 `read_project_file`(支持行区间)分段读——复用现有文件工具链和裁剪机制,让大内容天然纳入既有管理;
4. **可选**:query-focused 摘要——用当前会话的模型(或用户配置的轻量模型)把页面压缩为"与查询相关的 1-2K 摘要"。成本走用户自己的 key,默认关闭。

优先级:C1 > B1/B2 > A > C2/C3 > B3-B5 > C4。其中 C1、B1、B2 合计约 2-3 天,能立刻显著改善体验;A 是中期正解。

---

## 3. Context 压缩与管理

### 3.1 现状架构

- **历史组装**:每轮把**全量** Room 历史(平面 USER/ASSISTANT 文本对)传入 `buildInitialConversation()`(`DefaultAgentLoopCoordinator.kt:616-634`),先做 Phase A 跨回合截断(最近 assistant 4000 字符、次近 1500、更旧 500,`:742-746`);循环内每次迭代(上限 30 次)对 `fullConversation` 跑 Phase B 压缩链(`ConversationCompactor.kt:38-109`):Strategy 1 裁旧回合工具 payload → Strategy 2 结构化摘要 → Strategy 3 模型摘要(仅 QWEN/KIMI)→ 兜底文本截断。
- **预算**:按 provider 硬编码(Anthropic 80K / OpenAI 60K / Qwen 40K / Kimi 24K,`ProviderContextBudget.kt:10-17`),与具体模型无关、不可配置。
- **token 估算**:纯字符启发式(CJK 2 字符/token、其余 4,`ConversationContextManager.kt:122-148`),无校准。
- **持久化**:回合结束只存最终 `content` 文本 + `thoughts`(thinking 文本和 `[Tool] name` 标记),**工具参数、工具结果、reasoningContent 全部丢弃**(`AgentSessionManager.kt:430-451`)。

### 3.2 缺陷清单(12 项,按四类归并)

#### 类别一:压缩根本没生效的路径

- **D1|OpenAI 路径完全绕过压缩(最严重)**:coordinator 把压缩结果放进 `fullConversation` 参数,但 `OpenAiResponsesAgentGateway` 只读 `request.conversation`(本迭代 delta)+ `previousResponseId`(`OpenAiResponsesAgentGateway.kt:57-58`),历史由服务端状态无限累积——客户端压缩形同虚设,每迭代还白算一次。所有 OpenAI 兼容端点(含 Ollama 本地模型)都在此列。
- **D3|Strategy 1 在真实数据流中是死代码**:TOOL payload 只存在于当前回合(旧回合从 Room 加载后是纯文本,没有 TOOL 项),而当前回合永远在保留窗口内不被处理——精心实现的 8 种工具裁剪逻辑实际从不执行。讽刺的是它是唯一有单测的策略。

#### 类别二:压缩单位错位(用户"一直不好"体感的主因)

- **D2|当前回合的工具结果不可压缩**:压缩以 USER 消息切"回合",当前 agent run 的全部工具调用/结果都挂在最后一个回合下,策略 1/2 只处理 `olderTurns`,兜底截断也只砍无 toolCalls 的 assistant 文本且最后回合永不丢(`ConversationCompactor.kt:129-168`)。一次 run 内连读几个大文件 + 多次 build + inspect_ui,context 无界增长直到 provider 报错——`docs/context-compaction-redesign.md` 记录的 Kimi 1.18MB 事故正是这个形态,Phase A 只修了跨回合的一半。
- **D12|上游无节流放大一切**:`read_project_file` 无行数/字节上限(`FileTools.kt:122`,整文件入 payload),单次迭代读几个大文件即可击穿预算。

#### 类别三:估算与预算失准

- **D4|token 估算系统性偏差**:代码/JSON 按 4 字符/token 偏乐观且 `toString()` 转义放大误差;**图片附件完全不计**(gateway 却会把历史所有图片 base64 全量重发,`AnthropicMessagesAgentGateway.kt:281-289`);system prompt(约 12.6KB)+ 工具 schema 不计入;日韩文按非 CJK 低估一半;Anthropic 回传的真实 `usage` 只进诊断从不校准。
- **D9|预算与模型脱钩**:按 provider 写死(Kimi 24K,而 kimi-k2.5 实际窗口大得多;Anthropic 80K vs 实际 200K),不看 `platform.model`,用户不可配置;Anthropic 输出 `maxTokens=16000` 硬编码,Qwen/Kimi/DeepSeek 请求不设 max_tokens。

#### 类别四:信息丢失与实现缺陷

- **D6|压缩丢关键状态**:Room 的 `thoughts` 只记 `[Tool] write_project_file` **不含文件路径**;`currentPlan` 是局部变量回合结束即丢。下一回合模型不知道上回合改了哪些文件,只能重新 list/read,反而进一步撑大 context。
- **D8|Phase A 保头弃尾**:`take(maxChars)` 截掉的是 assistant 尾部——而结论恰在尾部;超大 USER 消息(粘贴长日志)无任何处理路径。
- **D5|模型摘要不验预算即返回**,且压缩结果不回写循环状态——Qwen/Kimi 超限后**每次迭代都重新调一次摘要 API**,又慢又贵。
- **D7|摘要项 role=USER** 产生连续 user 消息,对要求角色交替的 Anthropic Messages API 有 400 风险。
- **D10|摘要 API 直接 `setToken/setAPIUrl` 改单例**,与多会话并发互相覆盖(与 §4 问题 5 同源)。
- **D11|O(n²) 重估算**:兜底截断每步全量重算 token,MB 级会话在设备端显著耗时。

### 3.3 重设计方案

#### 第一步:止血修复(合计约 3-5 天)

1. **修 D1**:OpenAI gateway 改为消费压缩后的 `fullConversation`。务实做法:正常迭代继续用 `previousResponseId`(省流量),一旦压缩被触发就**重置会话链**(丢弃 previousResponseId、全量发送压缩历史、`store` 重新开始);
2. **修 D12**:`read_project_file` 加默认上限(如 2000 行 / 50KB,超出返回前段 + "使用行区间读取剩余部分"提示);build 输出的 `errorMessage` 与单条 message 截断;
3. **修 D5**:模型摘要结果过预算检查,不达标继续走兜底;压缩结果回写循环状态避免重复摘要;
4. **修 D7**:摘要注入改为 assistant 角色或合并进相邻 user 消息;
5. `ToolResultTrimStrategy` 补 web 工具分支(见 §2.2 C1)。

#### 第二步:核心重构——压缩单位从"回合"改为"消息 + token 预算"

这是解决 D2/D3 的根本动作,思路对齐成熟 coding agent 的分层策略:

```
层 1 · 回合内工具结果淘汰(microcompact,新增,解决 D2)
  当前回合内,保留最近 K 次迭代的完整工具结果;
  更旧迭代的大 payload 原位替换为占位符
  (如 [File: path, 213 lines — trimmed, re-read if needed]);
  严格保持 assistant.toolCalls ↔ TOOL 结果的 id 配对,只换内容不删消息。
  这让 Strategy 1 的裁剪逻辑真正跑在它该跑的地方。

层 2 · 跨回合压缩(改造现有 Phase A)
  数据源换成结构化持久化(见下),按"信息价值"生成紧凑摘要,
  替代现在的 take(maxChars) 保头弃尾;超大 USER 消息同样纳入截断。

层 3 · 全量摘要(现有 Strategy 2/3,保留)
  触发阈值前移(预算 70% 即开始层 1,90% 才走层 3),
  摘要结果缓存、增量维护,不再每迭代全量重压。
```

#### 第三步:结构化持久化(落实既有文档的 "Smarter Room Persistence")

回合结束时除 `content` 外,额外落一张结构化表:修改文件清单、build 状态、错误摘要、工具调用序列、当轮 plan。跨回合重建 context 时用它生成确定性的紧凑摘要(几百字节),同时解决 D6(文件清单丢失)和 D8(截断丢结论)——因为不再依赖"截断平面文本"来回忆上文。`currentPlan` 随之持久化,跨回合延续。

#### 第四步:估算校准与预算配置化

- 每次响应用 provider 回传的真实 `usage.inputTokens` 校准"字符/token 比"(按 provider 维护滑动平均),启发式只做首轮冷启动;
- 估算纳入 system prompt、工具 schema、图片(按各家图片计费公式近似);
- `PlatformV2` 增加 `contextWindow` / `maxOutputTokens` 字段,预算 = `contextWindow - 输出预留 - system/工具占用`,设置页可覆盖;硬编码的 `ProviderContextBudget` 降级为默认值表。

#### 与既有文档的关系

`docs/context-compaction-redesign.md` 对"跨回合膨胀"的诊断是准确的,Phase A 是针对性补丁;本方案承接它未解决的另一半:回合内膨胀(D2)、OpenAI 旁路(D1)、持久化结构缺失(D6/D8)。其"Future Iteration Ideas" 中的 Unified Budget Allocation、Smarter Room Persistence、Provider-Specific Token Counting 三条,分别对应本节第二、三、四步,方向一致,本文给出落地顺序。

---

## 4. 其他优化点(全局扫描)

按优先级归类,均有代码证据(详见各条 file:line)。

### P0 · 可靠性与正确性

| # | 问题 | 证据 | 建议 |
|---|------|------|------|
| 1 | **模型请求零重试**:一次 429/5xx/网络抖动直接杀死整轮 agent loop(工具已改的文件留下,对话中断) | `DefaultAgentLoopCoordinator.kt:247-272`;全库无 retry/backoff | gateway 层对可重试错误做指数退避(2-3 次、尊重 Retry-After),迭代失败与整轮失败解耦 |
| 2 | **SSE 中断被当正常完成**:连接断开既不 Completed 也不 Failed,截断文本被标记成功存库 | `OpenAIAPIImpl.kt:332-341`、`AnthropicAPIImpl.kt:132-140`(`readUTF8Line() ?: break`) | 校验是否收到 `message_stop`/`finish_reason`,未收到按 Failed 处理并可重试续传 |
| 3 | **取消会话丢快照**:finally 中快照提交是可取消的挂起调用,`job.cancel()` 后静默丢失,undo 链断裂 | `DefaultAgentLoopCoordinator.kt:522-604`,`AgentSessionManager.kt:178-182` | finally 内 `withContext(NonCancellable)` 包裹快照与索引写入 |
| 4 | **`edit_project_file` 零匹配仍返回成功**:模型以为改上了→白付一次全量构建;`replaceFirst` 无唯一性检查可能改错位置 | `FileTools.kt:240-265` | 0 匹配返回 `isError=true`;返回匹配次数;多处匹配要求更长上下文;补 `replace_all` |
| 5 | **API 单例可变 token/apiUrl 竞态**:四个 gateway 共享同一 `OpenAIAPI` 实例,多会话并发时 A 的 key 可能打到 B 的 endpoint | `NetworkModule.kt:35-45`,`KimiChatCompletionsAgentGateway.kt:56-57` | token/url 改为方法参数,删除可变字段(同修 §3 D10) |
| 6 | **工具参数 JSON 解析失败被静默吞掉**;`stop_reason=max_tokens` 收下但从未消费,截断的参数 JSON 正好落入静默降级 | `AnthropicMessagesAgentGateway.kt:167-186` | 解析失败构造 `isError` 结果明示模型重发;检测 max_tokens 截断并告知或自动续写 |
| 7 | **工具执行无超时**:挂死的工具(跨进程 inspect、build 锁)冻结整轮,用户只能手动取消→触发问题 3 | `DefaultAgentLoopCoordinator.kt:340-359` | 按工具类型包 `withTimeout`,超时产出 isError 让模型改道 |

### P1 · 性能与成本

| # | 问题 | 证据 | 建议 |
|---|------|------|------|
| 8 | **build-engine 无增量编译**:每次全量 AAPT2 link + 全量 javac(含 ~14 个巨型 R.java,分批编译还要 `System.gc()`);`BuildTool` 默认 `clean=true`。agent fix-loop 一轮对话 build 3-5 次,每次都付冷构建成本 | `JavacCompiler.kt:42-66`,`Aapt2ResourceCompiler.kt:34-35`,`BuildTool.kt:39-43` | 第一步做 **R.class 缓存**(以资源输入 hash 为 key,资源未变直接复用,是单次构建最大开销项);第二步资源未变跳过 RESOURCE 阶段;第三步源码 hash 级增量 javac,验证可靠后把 clean 默认翻为 false |
| 9 | **Anthropic 无 prompt caching**:30 次迭代每次全量重发 ~12.6KB 系统提示 + 全部历史,重复计费重复 prefill(缓存命中读取价约为常规 1/10) | 全库无 `cache_control`;`AnthropicMessagesAgentGateway.kt:78-93` | system、tools、对话前缀加 `cache_control` 断点;顺带把 `maxTokens=16000` 配置化 |
| 10 | **Room 大字段 + 无索引搜索 + 全量加载**:`thoughts`/`content` 无界 TEXT;搜索 `LIKE '%q%'` 全表扫描;打开聊天一次拉整史;有 CursorWindow 2MB 崩溃风险 | `MessageV2.kt:29-33`,`MessageV2Dao.kt:13-24` | 大字段外置文件/独立表按需加载;搜索改 FTS5;列表只取摘要列(与 §3 结构化持久化同一工程) |
| 11 | **网络超时一刀切 5 分钟**:配错 endpoint 等 5 分钟才报错;长思考回答超 5 分钟被硬切,叠加问题 2 还被当成功 | `NetworkClient.kt:32-47` | connect 10-20s;socket 按 SSE 事件间隔判活(60-120s);流式禁用整体 requestTimeout |
| 12 | **release 全量网络日志**:每条 SSE chunk 都拼串打印,请求体(含用户对话/生成代码)4000 字符进 logcat,性能与隐私双输 | `NetworkLogcatLogger.kt:152-171`,`OpenAIAPIImpl.kt:496` | `BuildConfig.DEBUG` 或诊断开关门控;SSE 日志只记事件类型 |
| 13 | **会话状态只在结束时落库 + `messageStates` 常驻内存**:进程被杀丢整轮;长期使用内存稳步上涨 | `AgentSessionManager.kt:142-159, 464-469` | 按迭代边界节流落库;会话结束后 LRU 清理 |

### P2 · 工程卫生与产品能力

| # | 问题 | 证据 | 建议 |
|---|------|------|------|
| 14 | **三个 ChatCompletions gateway ~880 行复制粘贴**(ToolCallAccumulator、指令常量、消息构建各一份),流式 bug 要修三遍;另有死代码(`toAgentToolCall`、`EcjCompiler` 空壳、@Deprecated 的 `ConversationContextManager`) | `Kimi/Qwen/DeepSeekChatCompletionsAgentGateway.kt` | 抽 `BaseChatCompletionsGateway`,toolChoice/图片/URL 归一做覆写点;清理死代码 |
| 15 | **模板单一 + 导航层全靠模型即兴发挥**:只有 EmptyActivity 模板;禁多 Activity/Fragment 后,多屏应用的视图切换是构建失败与运行时 bug 高发区 | `assets/templates/` 仅 1 个模板;`agent-system-prompt.md:15-16` | 模板预置极简 `ScreenRouter`(FrameLayout 视图栈 + 返回键处理)写入系统提示;增加"列表-详情""底部导航"等 2-3 个脚手架模板,把常见结构从"生成"变"填空" |

> 工具集本身盘点结论:read(行区间+批量)/write/edit(增量 search-replace)/delete/list(带符号 outline)/grep/build/launch/inspect/interact/图标/计划/web 已相当完整,主要缺口是问题 4 的 edit 语义与缺文件重命名/移动工具,不是"缺工具"。

---

## 5. 路线图

按"止血 → 治本 → 还债"排列,各阶段内部可并行:

### 阶段一:止血(约 2 周,全部是小改动)

| 事项 | 对应 | 预估 |
|------|------|------|
| 模型请求重试 + SSE 完成校验 + 流式超时分级 | §4-1/2/11 | 2-3 天 |
| 快照 NonCancellable + 工具超时 + edit 语义 + 参数解析报错 | §4-3/4/6/7 | 2-3 天 |
| context 止血包:OpenAI 旁路、read 上限、摘要预算检查、web trim 分支 | §3 第一步 | 3-5 天 |
| web 搜索拦截识别 + 结构化错误 + 引擎熔断 + 渲染等待 | §2.2 B | 2-3 天 |
| 插件崩溃主动推送 + PixelCopy 截图 | §1.3 方向二 1/2 | 2-3 天 |
| API 单例竞态 + release 日志门控 | §4-5/12 | 1-2 天 |

### 阶段二:治本(约 3-4 周)

| 事项 | 对应 | 预估 |
|------|------|------|
| 回合内工具结果淘汰(microcompact)+ 压缩结果缓存 | §3 第二步 | 1 周 |
| 结构化持久化(含 Room 大字段外置,与 §4-10 合并做) | §3 第三步 | 1 周 |
| token 估算 usage 校准 + 预算随模型配置 | §3 第四步 | 3-4 天 |
| 正式搜索 API 接入(provider 化配置 + 设置 UI) | §2.2 A | 3-5 天 |
| fetch 落盘分页 + Anthropic prompt caching | §2.2 C、§4-9 | 3-4 天 |
| 安装模式调试回传通道(DebugBridge) | §1.3 方向一 | 3-5 天 |
| R.class 缓存(增量编译第一步) | §4-8 | 3-5 天 |

### 阶段三:还债与能力上限(按需排期)

| 事项 | 对应 | 预估 |
|------|------|------|
| ASM AndroidX 改写落地(解锁 Fragment 全家桶) | §1.3 方向三 | 1 周+ |
| 插件多 Activity 支持 + 生命周期补齐 | §1.3 方向二 3/4、方向三 | 1 周 |
| Gateway 基类重构 + 死代码清理 | §4-14 | 3-4 天 |
| ScreenRouter + 多模板 | §4-15 | 1 周 |
| 资源阶段跳过 + 增量 javac | §4-8 | 1 周+ |

---

## 6. 参考

- `docs/shadow-plugin-feasibility.md` — Shadow 插件化整体可行性(本文 §1 的方案三源自其 Option C)
- `docs/known-issues/fragment-in-plugin-mode.md` — Fragment 崩溃根因与候选修复
- `docs/superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md` — ASM 改写详细计划
- `docs/context-compaction-redesign.md` — 上下文压缩现行设计(本文 §3 承接其未解决部分)
- `docs/webview-crawler-research.md` — WebView 爬虫调研(现有实现即其落地结果,本文 §2 是下一步)
- `docs/plugin-ui-inspection-and-automation.md` — 插件 UI 检查与自动化
- `docs/agent-loop-optimization.md`、`docs/function-calling-agent-loop.md` — Agent loop 既有设计
