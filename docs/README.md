# VibeApp 文档索引

本目录收集 VibeApp 的设计文档、调研笔记、方案规划和已知问题。按主题分类如下。文件位置保持不动以便跨文档引用和 git 历史追溯，本索引只作为导航。

> 约定：标题后带 "（规划中）" 表示仍是调研/方案稿；带 "（历史）" 表示记录性文档；其它默认为已落地或已 review。

---

## 1. 架构与核心

项目整体架构、核心设计原则和入门向的说明。新人先读这一组。

- [architecture.md](./architecture.md) — 架构设计总览
- [ai-strategy.md](./ai-strategy.md) — AI 代码生成策略与核心原则

## 2. 构建链（Build Chain / Build Engine）

端上编译 APK 的那条链路：AAPT2 → Javac → D8 → Sign。

- [build-chain.md](./build-chain.md) — 编译链原理
- [build-engine.md](./build-engine.md) — build-engine 模块实现说明
- [java-xml-build-flow-migration.md](./java-xml-build-flow-migration.md) — 从 CodeAssist 迁移到当前编译链的历史
- [androidx-precompiled-libs.md](./androidx-precompiled-libs.md) — AndroidX / Material 预编译库的集成方案
- [apk-size-optimization.md](./apk-size-optimization.md) — APK 包体积优化分析
- [app-module-cleanup.md](./app-module-cleanup.md) — `app/src/main` 无用代码与资源清单
- [theme-and-performance-optimization.md](./theme-and-performance-optimization.md) — 主题稳定性与构建性能优化

## 3. 插件模式 / Shadow

生成的 App 通过 Tencent Shadow 在宿主进程内以"插件"形式运行的相关设计与问题。

- [shadow-plugin-feasibility.md](./shadow-plugin-feasibility.md) — Shadow 插件框架可行性分析
- [plugin-ui-inspection-and-automation.md](./plugin-ui-inspection-and-automation.md) — 插件 UI 检查与自动化操作方案
- [design-read-app-log.md](./design-read-app-log.md) — `read_app_log` 工具与插件运行时日志
- [known-issues/fragment-in-plugin-mode.md](./known-issues/fragment-in-plugin-mode.md) — **Fragment / FragmentManager 在插件模式下崩溃**（历史，暂未修复）

## 4. Agent Loop / 模型交互

聊天窗的 Agent 主循环：工具调用、上下文管理、计划、诊断等。

- [function-calling-agent-loop.md](./function-calling-agent-loop.md) — Function Calling 与 Agent Loop 设计
- [agent-loop-optimization.md](./agent-loop-optimization.md) — Agent Loop 优化设计
- [context-compaction-redesign.md](./context-compaction-redesign.md) — 上下文压缩重设计
- [plan-mode-research.md](./plan-mode-research.md) — Plan Mode 调研（Superpowers 原理 + VibeApp 集成）
- [smarter-auto-fix-strategy.md](./smarter-auto-fix-strategy.md) — 更智能的自动修复策略（规划中）
- [background-agent-service.md](./background-agent-service.md) — 后台 Agent Service 设计
- [chat-diagnostic-logging.md](./chat-diagnostic-logging.md) — 聊天诊断日志设计
- [debug-diagnostic-viewer.md](./debug-diagnostic-viewer.md) — Debug Diagnostic Viewer

## 5. Agent 工具（Tools）

Agent 可调用的具体工具设计。

- [grep-project-files-tool.md](./grep-project-files-tool.md) — `grep_project_files` 工具
- [jsoup-network-integration.md](./jsoup-network-integration.md) — Jsoup 网络请求能力
- [webview-crawler-research.md](./webview-crawler-research.md) — WebView 端上爬虫调研（规划中）
- [voice-input-research.md](./voice-input-research.md) — ChatScreen 语音输入调研（规划中）

## 6. 生成 App 的能力与视觉

提升生成 App 本身的表现质量：UI 模式、图标、配图、主题等。

- [image-and-icon-enhancement.md](./image-and-icon-enhancement.md) — 图标 / UI 配图 / 网络图片加载综合方案
- [icon-preset-library-strategy.md](./icon-preset-library-strategy.md) — 预置图标库 vs SVG Skill 的策略对比
- [ui-theme-analysis.md](./ui-theme-analysis.md) — VibeApp UI 主题改动评估
- [ui-pattern-manual-smoke.md](./ui-pattern-manual-smoke.md) — UI Pattern Library 手动冒烟用例

## 7. 产品、项目管理与发布

项目管理、产品策略、发布流程。

- [multi-project-management.md](./multi-project-management.md) — 多项目管理设计
- [community-template-marketplace-strategy.md](./community-template-marketplace-strategy.md) — 社区模板市场方案调研（规划中）
- [release-process.md](./release-process.md) — 发布流程

## 8. Superpowers Plans / Specs

按 Superpowers 工作流组织的实施计划和设计稿。命名约定：`YYYY-MM-DD-<slug>.md`。

### Plans（实施计划）

- [2026-03-28-shadow-full-integration.md](./superpowers/plans/2026-03-28-shadow-full-integration.md) — Shadow 集成整体计划
- [2026-03-28-shadow-androidx-on-device-transform.md](./superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md) — 设备端 AndroidX 字节码改写（解决 Fragment 问题的长期方案）
- [2026-04-03-minimax-provider-integration.md](./superpowers/plans/2026-04-03-minimax-provider-integration.md) — MiniMax Provider 接入
- [2026-04-04-conversation-compaction.md](./superpowers/plans/2026-04-04-conversation-compaction.md) — 对话压缩
- [2026-04-04-jsoup-network-integration.md](./superpowers/plans/2026-04-04-jsoup-network-integration.md) — Jsoup 集成
- [2026-04-04-plugin-ui-inspection.md](./superpowers/plans/2026-04-04-plugin-ui-inspection.md) — 插件 UI 检查
- [2026-04-05-agent-auto-test.md](./superpowers/plans/2026-04-05-agent-auto-test.md) — Agent 自动测试
- [2026-04-06-debug-diagnostic-viewer.md](./superpowers/plans/2026-04-06-debug-diagnostic-viewer.md) — Debug Diagnostic Viewer
- [2026-04-06-web-search-tool.md](./superpowers/plans/2026-04-06-web-search-tool.md) — Web Search 工具
- [2026-04-10-webview-content-extractor.md](./superpowers/plans/2026-04-10-webview-content-extractor.md) — WebView 内容抽取
- [2026-04-11-continuous-iteration.md](./superpowers/plans/2026-04-11-continuous-iteration.md) — 持续迭代模式
- [2026-04-11-icon-preset-library.md](./superpowers/plans/2026-04-11-icon-preset-library.md) — 预置图标库
- [2026-04-11-ui-pattern-library.md](./superpowers/plans/2026-04-11-ui-pattern-library.md) — UI Pattern Library

### Specs（设计稿）

- [2026-04-06-web-search-tool-design.md](./superpowers/specs/2026-04-06-web-search-tool-design.md)
- [2026-04-11-continuous-iteration-design.md](./superpowers/specs/2026-04-11-continuous-iteration-design.md)
- [2026-04-11-ui-pattern-library-design.md](./superpowers/specs/2026-04-11-ui-pattern-library-design.md)

## 9. 已知问题（Known Issues）

不好修但需要记下来的历史遗留问题。

- [fragment-in-plugin-mode.md](./known-issues/fragment-in-plugin-mode.md) — 插件模式下 Fragment / FragmentManager 会 crash
