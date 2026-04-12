# Smarter Auto-Fix 方案调研

> 日期：2026-04-11  
> 对应 roadmap：Phase 3 — Smarter auto-fix

## 1. 目标

这一项的目标不是“让模型更会写代码”这么抽象，而是更具体地提升两件事：

- **更广的错误覆盖面**：资源错误、Java 编译错误、DEX 错误、模板约束错误、部分运行时约束错误
- **更高的一次成功率**：第一次 build 就成功，或者第一次 fix 就成功

在 VibeApp 现有架构下，最有效的方向不是换更大的模型，也不是上复杂服务器，而是：

**把 build / fix 流程里的结构化信号提纯，再把修复任务收窄给模型。**

## 2. 先说结论

### 推荐方案

做一个三层式 auto-fix 系统：

1. **Preflight Checker（构建前约束检查）**
2. **Build Failure Analyzer（构建失败结构化归因）**
3. **Repair Loop Memory（修复回合记忆与策略控制）**

这三层都可以完全本地实现，不需要训练新模型，也不需要后端。

如果只做最关键的一版，我建议优先顺序是：

1. **先做 Build Failure Analyzer**
2. **再做 Preflight Checker**
3. **最后补 Deterministic Quick Fix 和回合记忆**

原因很简单：当前系统已经能拿到大量诊断信息，但还没有把它组织成模型最容易修的输入。

## 3. 当前现状梳理

从代码看，现有基础其实已经不错：

- `JavacCompiler` 用 `DiagnosticListener` 收集编译诊断
- `Aapt2ResourceCompiler` 已经从 AAPT2 JNI 取日志
- `D8DexConverter` 已经接了 `DiagnosticsHandler`
- `RecordingLogger` 已经把日志结构化成：
  - `stage`
  - `level`
  - `message`
  - `sourcePath`
  - `line`
- `ChatDiagnosticLogger` 已经能记录 agent loop / build result / tool result

也就是说，**底层诊断采集不是短板**。

真正的短板在上层：

### 3.1 错误被“压扁”后再喂给模型

当前手动 build 失败路径里，`ChatViewModel.buildBuildErrorMessage()` 只把错误变成一段字符串：

- 先取 `result.errorMessage`
- 否则拼接所有 `ERROR` 级日志的 `message`

这里会丢失重要上下文：

- 失败发生在哪个 stage
- 哪些错误是主因，哪些是连锁报错
- 错误对应的文件路径和行号
- 应该先读哪些文件

### 3.2 `run_build_pipeline` 返回的是“原始错误列表”，不是“修复任务”

`BuildTool` 现在把 `BuildResult.toFilteredJson()` 直接回给模型。  
这比纯字符串好一些，但仍然偏原始：

- 没有主错误排序
- 没有归类
- 没有针对性 hint
- 没有“建议下一步读取哪些文件”

模型仍然需要自己做很多归纳工作。

### 3.3 缺少“已知约束”的静态守门

当前 system prompt 里有很多硬约束，例如：

- 必须继承 `ShadowActivity`
- 不能用 lambda
- 不要用 `setSupportActionBar()`
- 不要用 `Theme.Material3.*`
- 不要引入未打包组件

这些约束很多其实不需要等到 build 失败后才发现。  
**它们更适合在 build 前做轻量静态检查。**

### 3.4 缺少“修复记忆”

当前 fix loop 基本是：

1. build
2. 失败
3. 把错误回灌模型
4. 模型改文件
5. build

但系统没有明确告诉模型：

- 上一轮修的是哪几个文件
- 哪个错误签名还没消失
- 哪种修复尝试已经失败过

这会导致模型重复走老路。

## 4. 根因判断

如果用一句话概括当前 auto-fix 的问题：

**现在是“把诊断文本交给模型自己做编译器前端”，而不是“先把诊断整理成可执行修复任务，再让模型改代码”。**

这也是为什么同一个模型有时能修好，有时会反复绕圈。

## 5. 备选技术方案对比

## 方案 A：只调 prompt

### 做法

- 强化 system prompt 的 fix 规则
- 加一些“看到 build error 先 read file 再 edit”的提示
- 增加常见错误案例

### 优点

- 成本最低
- 改动小

### 缺点

- 提升有限
- prompt 越长，上下文越重
- 不能解决错误归因和连锁报错问题

### 结论

**值得做，但不能作为主方案。**

---

## 方案 B：结构化诊断分析器 + 定向修复上下文

### 做法

- 将 `BuildResult` 转成结构化 `BuildFailureReport`
- 只保留最关键的 1~3 个主错误
- 给出分类、文件、行号、候选读取范围、修复 hint

### 优点

- 对所有模型都有效
- 能明显减少无关上下文
- 实现成本可控

### 缺点

- 需要维护错误归类规则

### 结论

**这是最核心、最应该优先做的方案。**

---

## 方案 C：加一层本地静态约束检查（类似轻量 lint）

### 做法

- 在 build 前扫描 Java/XML/Manifest
- 提前发现 VibeApp 已知不兼容模式

### 优点

- 对“首次 build 成功率”提升很大
- 很多错误可以在 AAPT2/Javac 之前截住

### 缺点

- 规则需要逐步积累
- 只能覆盖已知模式

### 结论

**非常值得做，尤其适合 VibeApp 这种约束明确的生成环境。**

---

## 方案 D：训练专门的修复模型 / 云端 reranker

### 做法

- 收集错误与修复数据
- 训练专门模型或加服务器侧 rerank / patch 评估

### 优点

- 理论上上限高

### 缺点

- 多 provider 架构下收益不稳定
- 成本和复杂度高
- 与当前“本地 / 多模型 / 低成本”方向不匹配

### 结论

**现阶段不推荐。**

## 6. 推荐架构

## 6.1 Layer 1: Build Failure Analyzer

这是 Phase 3 最值得先做的部分。

### 目标

把原始 `BuildResult` 转成一个更适合模型消费的对象，例如：

```json
{
  "status": "FAILED",
  "failedStage": "COMPILE",
  "summary": "Java compile failed: 2 primary errors in MainActivity.java",
  "primaryErrors": [
    {
      "category": "java_cannot_find_symbol",
      "message": "cannot find symbol class MaterialSwitch",
      "sourcePath": "src/main/java/com/vibe/generated/p123/MainActivity.java",
      "line": 42,
      "symbol": "MaterialSwitch",
      "severity": "ERROR",
      "hint": "Bundled library does not include MaterialSwitch. Replace with a supported widget."
    }
  ],
  "secondaryErrorCount": 6,
  "suggestedReads": [
    {
      "path": "src/main/java/com/vibe/generated/p123/MainActivity.java",
      "startLine": 30,
      "endLine": 60
    }
  ]
}
```

### 为什么有效

模型最擅长的是**局部决策**，不是从几十条原始编译日志里自己做归并和主因分析。

### 需要做什么

新增一个 analyzer 层，例如：

- `build-engine/.../diagnostic/BuildFailureAnalyzer.kt`
- 或 `app/.../feature/agent/build/BuildFailureAnalyzer.kt`

职责：

1. 识别失败 stage
2. 按 stage 做错误归类
3. 合并重复错误
4. 过滤连锁错误
5. 输出主错误摘要
6. 给出定向读取建议

## 6.2 Layer 2: Preflight Checker

### 目标

在真正 build 前，先做一轮**VibeApp 约束静态检查**。

这不是通用 Android lint，而是一个很轻的、面向当前模板和运行环境的 checker。

### 第一版建议覆盖的规则

#### Java / Activity 规则

- `Activity` 未继承 `ShadowActivity`
- 使用 lambda / method reference
- 调用 `setSupportActionBar()`
- 新增多个自定义 Activity

#### XML / Theme 规则

- 使用 `Theme.Material3.*`
- 使用 `Theme.AppCompat.*`
- 使用未打包组件，如 `MaterialSwitch` / `BottomAppBar`
- 可明显识别的资源引用错误模式

#### Manifest 规则

- 改坏 package
- 非必要新增复杂组件声明
- manifest 结构错误的高频模式

### 输出形式

它不一定要直接改代码。第一版只要输出一个“synthetic build diagnostics” 就够了：

```json
{
  "status": "PRECHECK_FAILED",
  "summary": "2 compatibility violations found before build",
  "violations": [...]
}
```

然后 agent 先修这些问题，再走真实 build。

### 为什么有效

这会显著提升“第一轮 build 成功率”，因为很多错误根本不该进入编译器阶段。

## 6.3 Layer 3: Repair Loop Memory

### 目标

让模型知道：

- 上轮修了什么
- 哪个错误还在
- 哪个错误已经消失
- 哪些文件改了但没有效果

### 建议记录的信息

每轮 fix 后，保存一个 `RepairAttempt`：

```json
{
  "iteration": 4,
  "errorSignatures": [
    "COMPILE:MainActivity.java:cannot find symbol:MaterialSwitch"
  ],
  "filesEdited": [
    "src/main/java/.../MainActivity.java"
  ],
  "resolvedSignatures": [],
  "persistedSignatures": [
    "COMPILE:MainActivity.java:cannot find symbol:MaterialSwitch"
  ]
}
```

下轮给模型的提示不再是泛泛的“继续修”，而是：

- 上轮修改了 `MainActivity.java`
- 同一个 `MaterialSwitch` 错误仍然存在
- 优先重新检查该文件对应行附近，而不是重写其他文件

### 为什么有效

这能明显减少反复大改、误改和无效重写。

## 7. 关键设计：错误分类目录

建议维护一份本地 `ErrorPatternCatalog`，可以是 Kotlin 规则，也可以是 JSON 规则。

## 7.1 AAPT2 高优先级分类

建议首批支持：

- `aapt_unknown_view_or_attr`
- `aapt_style_parent_invalid`
- `aapt_resource_not_found`
- `aapt_manifest_structure_error`
- `aapt_expected_color_or_drawable`
- `aapt_namespace_or_at_symbol_error`

这些都很适合做高置信度 hint，因为 Android 官方 AAPT2 文档本身就覆盖了多类典型错误模式。

## 7.2 Javac 高优先级分类

建议首批支持：

- `java_cannot_find_symbol`
- `java_package_not_found`
- `java_incompatible_types`
- `java_method_not_found`
- `java_non_static_reference`
- `java_unreported_exception`
- `java_public_class_filename_mismatch`
- `java_override_contract_error`
- `java_missing_r_import`

## 7.3 D8 高优先级分类

建议首批支持：

- `d8_duplicate_class`
- `d8_desugar_or_min_api_issue`
- `d8_invalid_bytecode_or_classpath`

虽然 D8 错误没有 Javac 那么常见，但一旦发生，对模型来说往往更难，需要明确归类。

## 8. 关键设计：从“日志”变成“修复任务”

我建议把 `run_build_pipeline` 的失败输出改造成两层：

### 8.1 原始层

保留当前：

- `status`
- `errorMessage`
- `logs`

用于调试和回退。

### 8.2 分析层

新增：

- `failedStage`
- `summary`
- `primaryErrors`
- `secondaryErrorCount`
- `suggestedReads`
- `repairHints`

模型优先读分析层；只有分析层不足时，再回看原始日志。

## 9. Deterministic Quick Fix：哪些值得自动修

第一版不要做“自动改一切”。  
只做**高置信度、低破坏性**的 deterministic fix。

### 候选场景

- `Theme.Material3.*` 改回允许的主题父类
- `Theme.AppCompat.*` 改回允许的主题父类
- 明确不支持的控件替换建议
- 明显错误的 `@android:` / `@` 引用写法

### 不建议第一版自动改的场景

- 大段 Java 逻辑修复
- 多文件联动重构
- 自动补全业务代码

原因很简单：  
deterministic fix 应该是“挡刀”，不是另一个半吊子代码生成器。

## 10. Prompt 怎么改才有意义

Prompt 仍然要改，但应该**建立在结构化诊断之上**。

建议在 fix 回合加入一个小而强的 repair block，而不是继续堆很多静态规则：

```text
[Repair Context]
Failed stage: COMPILE
Primary error: cannot find symbol class MaterialSwitch
Target file: src/main/java/.../MainActivity.java:42
Suggested action: replace MaterialSwitch with a bundled widget
Do not rewrite unrelated files.
Read only the target file slice first, then patch minimally, then rebuild.
```

这比把几十条错误原样贴进去更有效。

## 11. 与现有代码的最小接入点

## 11.1 `BuildResult` 出口

当前最自然的切入点是 `BuildTool` 和 `ChatViewModel`：

- `BuildTool`：给 agent 的 `run_build_pipeline`
- `ChatViewModel`：手动 build 失败后的自动修复入口

两条路径都应该统一走 `BuildFailureAnalyzer`，避免出现：

- agent 模式拿到的是 A 结构
- 手动 build 模式拿到的是 B 字符串

## 11.2 诊断日志系统

`ChatDiagnosticLogger` 已经在记录 build / tool / model trace。  
可以顺手增加：

- 归类后的错误类别
- 规范化 error signature
- 是否重复出现
- 哪一轮消失

这会直接服务后续优化。

## 11.3 agent loop

`DefaultAgentLoopCoordinator` 不一定要大改。  
最小版本只需要：

- 在 `run_build_pipeline` 工具结果里返回更好的分析层
- 在下一轮 prompt 里插入 repair context

先把输入变聪明，通常就已经能显著提升输出。

## 12. 度量指标

这一项一定要带指标做，否则会陷入“感觉变聪明了”的主观判断。

建议至少追以下指标：

- **First build success rate**：首次 build 成功率
- **First fix success rate**：第一次修复后 build 成功率
- **Median fix iterations**：build 成功前的中位修复轮数
- **Repeated-error rate**：同一错误签名连续出现 2 次以上的比例
- **Top error families**：最常见错误类别分布
- **Context bytes per failed build**：每次失败给模型喂了多少诊断上下文

这些数据现有 diagnostic 系统已经有基础承接能力。

## 13. 实施路线

## Phase A：先把失败输入变聪明

范围：

- `BuildFailureAnalyzer`
- `run_build_pipeline` 返回 `failedStage + primaryErrors + suggestedReads`
- 手动 build 失败路径统一复用 analyzer

预期收益：

- 立刻提升 fix loop 收敛速度
- 明显减少模型误读日志

## Phase B：加本地约束检查

范围：

- `PreflightChecker`
- 覆盖 10~20 条 VibeApp 高频兼容性规则

预期收益：

- 提升首次 build 成功率
- 减少低价值 build 往返

## Phase C：加修复记忆与低风险自动修

范围：

- `RepairAttemptLedger`
- `ErrorSignature`
- 少量 deterministic quick fix

预期收益：

- 降低“同错反复出现”
- 提升难错的收敛效率

## 14. 不建议现在做的事

当前阶段不建议优先做：

- 训练专门的 patch 模型
- 服务器侧 rerank / patch sandbox
- 大量 few-shot 填进 system prompt
- 自动修所有错误类别

这些要么太重，要么不稳定，要么和 VibeApp 的本地多模型架构不匹配。

## 15. 最终建议

如果要用一句话概括这项 roadmap 该怎么做：

**不要把“Smarter auto-fix”理解成“让模型更自由地试错”，而要把它做成“让系统先把错误压缩成最小、最准、最可执行的修复任务”。**

对应到具体实现，就是：

1. **BuildFailureAnalyzer** 作为核心
2. **PreflightChecker** 作为首次 build 成功率放大器
3. **RepairLoopMemory** 作为收敛效率放大器

这是当前代码基础上最简单、最务实、收益也最大的路径。

## 16. 推荐落地形态

建议最终落成 4 个清晰模块：

- `BuildFailureAnalyzer`
- `ErrorPatternCatalog`
- `PreflightChecker`
- `RepairAttemptLedger`

它们都可以是纯本地 Kotlin 组件，不需要服务端。

## 17. 参考资料

### 本仓库现有实现（结论主要基于这些）

- `build-engine/src/main/java/com/vibe/build/engine/compiler/JavacCompiler.kt`
- `build-engine/src/main/java/com/vibe/build/engine/resource/Aapt2ResourceCompiler.kt`
- `build-engine/src/main/java/com/vibe/build/engine/dex/D8DexConverter.kt`
- `build-engine/src/main/java/com/vibe/build/engine/internal/RecordingLogger.kt`
- `app/src/main/kotlin/com/vibe/app/feature/agent/tool/BuildTool.kt`
- `app/src/main/kotlin/com/vibe/app/feature/agent/tool/AgentToolExtensions.kt`
- `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt`
- `app/src/main/assets/agent-system-prompt.md`

### 外部官方资料

- Java `DiagnosticListener` / `JavaCompiler` / `Diagnostic`  
  https://docs.oracle.com/en/java/javase/19/docs/api/java.compiler/javax/tools/DiagnosticListener.html  
  https://docs.oracle.com/en/java/javase/22/docs/api/java.compiler/javax/tools/JavaCompiler.html  
  https://docs.oracle.com/javase/8/docs/api/javax/tools/Diagnostic.html
- Android AAPT2 官方文档  
  https://developer.android.com/studio/command-line/aapt2
- Android lint 官方文档（这里主要借鉴“构建前静态结构检查”的思路，不是直接照搬 Gradle lint）  
  https://developer.android.com/studio/write/lint.html
