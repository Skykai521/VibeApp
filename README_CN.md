# VibeApp（意造）

> 用一句话，造一款真正属于你的 Android App。
> *Describe it. Build it. Install it. All on your phone.*

[![License](https://img.shields.io/badge/license-GPL%203.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010.0%2B-green.svg)](https://android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-29-brightgreen.svg)]()
[![Status](https://img.shields.io/badge/status-In%20Development-orange.svg)]()

[**English**](README.md) | 中文

<p align="center">
  <img src="docs/assets/banner.png" alt="VibeApp Banner" width="1850"/>
</p>

---

## ✨ 是什么 | What is VibeApp

VibeApp 是一个**完全开源**的 Android 应用，它让任何人都可以通过自然语言描述，**在手机上直接生成、编译、安装**一款真正的原生 Android App——无需电脑，无需编程基础，无需关注代码实现。

你只需要告诉它你想要什么，它就帮你造出来。

**VibeApp** is a fully open-source Android app that allows anyone to generate, compile, and install a native Android app directly on their phone using natural language — no PC, no coding skills, no cloud required.

### Demo


|                | 视频                                                                                             | 备注                               |
|----------------|------------------------------------------------------------------------------------------------|----------------------------------|
| Pomodoro timer | <video src="https://github.com/user-attachments/assets/ae3ffc67-9ec4-4eee-9f97-fce75a7491bd"/> | 模型：kimi-k2.5。视频存在剪辑，省略了一些模型思考的时间 |


## 🤔 为什么做这件事 | Why

市面上已经有很多 AI App Builder，但它们有一个共同的问题：**生成的不是真正的 App**。

|      | 其他 AI Builder     | VibeApp             |
|------|-------------------|---------------------|
| 输出物  | Web App / PWA / 需要云编译 | **原生 APK**          |
| 编译方式 | 云端                | **设备端本地编译**         |
| 数据隐私 | 代码上传到服务器          | **代码不离开你的手机**       |
| 源码导出 | 大多不支持             | **一键导出源码**          |
| 技术门槛 | 需要部署/配置环境         | **仅需配置 AI API key** |

我们相信，AI 时代不会消灭 App，而是会让更多人**第一次成为 App 的创造者**。

---

## 🚀 功能特性 | Features

### 核心能力

- **💬 对话式创作** — 用自然语言描述需求，多轮对话持续迭代
- **📱 设备端全链路编译** — AAPT2 + JavacTool + D8 + 打包/签名，完整编译链跑在你的手机上
- **🔁 自动错误修复** — 编译失败自动将错误喂给 AI 修复。
- **📂 多项目管理** — 同时管理多个 App 项目，带版本快照和编译缓存
- **🧠 多模型支持** — Claude、GPT、Gemini、Qwen，以及 OpenAI 兼容的本地 Ollama
- **📤 灵活导出** — 直接安装 APK、导出完整源码

### 代码生成策略（三重保障）

AI 生成代码的稳定性是产品的核心，VibeApp 采用三重保障机制：

1. **模板约束** — AI 不从零生成，而是在预定义骨架内填空，最大程度降低结构性错误
2. **严格 System Prompt** — 明确白名单（允许使用的标准 SDK 类）
3. **自动修复循环** — 编译失败时，将错误日志清洗后喂给 AI 自动修复，覆盖绝大多数常见错误场景

---

## 🏗️ 架构设计 | Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Presentation Layer                                           │
│ Compose Screens + ViewModels                                 │
│ chat / home / setup / settings / start                       │
├──────────────────────────────────────────────────────────────┤
│ Feature Layer                                                │
│ Agent Loop Coordinator + Project Manager + Project Init      │
│ Agent Tools (read/write/list files, run build, rename, icon) │
├──────────────────────────────────────────────────────────────┤
│ Data Layer                                                   │
│ Room + DataStore + Repository + Network API clients          │
│ OpenAI / Anthropic / Google / Qwen                           │
├──────────────────────────────────────────────────────────────┤
│ Build Engine Module (`build-engine`)                         │
│ RESOURCE → COMPILE → DEX → PACKAGE → SIGN                   │
│ AAPT2     JavacTool   D8    ApkBuilder   ApkSigner           │
├──────────────────────────────────────────────────────────────┤
│ Device Filesystem                                             │
│ /files/projects/{projectId}/app + generated source + APK     │
└──────────────────────────────────────────────────────────────┘
```

当前主链路：

```
ChatScreen
  → ChatViewModel
  → AgentLoopCoordinator / ProjectInitializer / ProjectManager
  → Repository + API Client / Workspace FS
  → BuildPipeline.run()
  → signed.apk → PackageInstaller
```

> 完整分层说明、模块职责和核心时序见 [docs/architecture.md](docs/architecture.md)

---

## 🔧 编译链工作原理 | How the Build Chain Works

```
用户描述需求
     ↓
AI 生成 Java 源码 + XML 布局（System Prompt 严格约束）
     ↓
AAPT2（编译 `res/` + 链接 Manifest + 生成 `R.java` + `generated.apk.res`）
  ├─ 失败 → 错误清洗 → AI 修复 → 最多重试 3 次
  └─ 成功 ↓
JavacTool 编译（业务源码 + `R.java` → `.class`）
     ↓
D8 转换（`.class` → `classes.dex`）
     ↓
APK 打包（`generated.apk.res` + `classes.dex` → `generated.apk`）
     ↓
ApkSigner（V1 + V2 签名 → `signed.apk`）
     ↓
PackageInstaller 引导用户安装 ✅
```

### 编译链技术栈

| 组件 | 作用 | 说明 |
|------|------|------|
| **AAPT2** | `res/` + Manifest → `R.java` + `generated.apk.res` | 先完成资源编译和链接，再交给 Java 编译阶段 |
| **JavacTool** | Java → `.class` | 编译业务源码和 AAPT2 生成的 `R.java` |
| **D8** | `.class` → `.dex` | Android 官方 DEX 编译器 |
| **ApkBuilder + ApkSigner** | 打包 + 签名 | 产出最终 `signed.apk` |

---

## 📱 快速开始 | Quick Start

### 环境要求

- Android 10.0 (API 29) 及以上
- AI API Key（Claude / GPT-4o / Qwen 任选其一）或本地 Ollama 服务

### 安装
[从 Release 页面下载最新 APK](https://github.com/Skykai521/VibeApp/releases)

### 源码构建
```bash
git clone https://github.com/Skykai521/VibeApp.git
cd VibeApp
./gradlew assembleDebug
```

### 首次使用

1. 打开 VibeApp → 设置 → 配置你的 AI API Key
2. 点击「新建项目」
3. 用自然语言描述你想要的 App
4. 等待自动生成 → 编译 → 安装

---

## 📁 项目结构 | Project Structure

```
VibeApp/
├── app/                                 # Android app module
│   ├── src/main/kotlin/com/vibe/app/
│   │   ├── presentation/                # Compose UI、导航、ViewModel、主题
│   │   │   ├── common/
│   │   │   ├── icons/
│   │   │   ├── theme/
│   │   │   └── ui/
│   │   │       ├── chat/
│   │   │       ├── home/
│   │   │       ├── main/
│   │   │       ├── migrate/
│   │   │       ├── setting/
│   │   │       ├── setup/
│   │   │       └── startscreen/
│   │   ├── feature/                     # 核心业务编排
│   │   │   ├── agent/                   # Agent loop、gateway、tool registry
│   │   │   │   ├── loop/
│   │   │   │   ├── tool/
│   │   │   │   └── service/
│   │   │   ├── project/                 # ProjectManager / Workspace abstraction
│   │   │   ├── projecticon/             # 启动图标生成
│   │   │   └── projectinit/             # 模板工程初始化、构建入口
│   │   ├── plugin/                      # 插件运行时宿主
│   │   │   ├── PluginContainerActivity  # 代理 Activity（5 个进程隔离槽位）
│   │   │   ├── PluginManager            # 槽位分配、LRU 淘汰
│   │   │   └── PluginResourceLoader     # DexClassLoader + AssetManager 资源加载
│   │   ├── data/                        # 持久化、网络、DTO、repository
│   │   │   ├── database/
│   │   │   │   ├── dao/
│   │   │   │   └── entity/
│   │   │   ├── datastore/
│   │   │   ├── dto/
│   │   │   ├── model/
│   │   │   ├── network/
│   │   │   └── repository/
│   │   ├── di/                          # Hilt modules
│   │   └── util/                        # 通用工具与扩展
│   ├── src/main/res/                    # UI 资源、多语言文案
│   ├── src/main/assets/                 # android.jar、模板与静态资源
│   └── schemas/                         # Room schema snapshots
├── build-engine/                        # 设备端构建引擎
│   └── src/main/java/com/vibe/build/engine/
│       ├── apk/                         # APK 打包
│       ├── compiler/                    # JavacCompiler / Ecj compatibility shim
│       ├── dex/                         # D8 转 dex
│       ├── internal/                    # workspace、logger、binary resolver
│       ├── model/                       # BuildResult / BuildStage / CompileInput
│       ├── pipeline/                    # BuildPipeline / DefaultBuildPipeline
│       ├── resource/                    # AAPT2 资源编译与链接
│       └── sign/                        # Debug 签名
├── shadow-runtime/                      # 插件运行时类（ShadowActivity 等）
├── build-tools/                         # 打包进应用的编译工具链依赖
│   ├── android-stubs/
│   ├── common/
│   ├── eclipse-standalone/
│   ├── javac/
│   ├── jaxp/
│   ├── kotlinc/
│   ├── logging/
│   ├── manifmerger/
│   └── project/
├── docs/                                # 文档
│   ├── architecture.md                  # 完整架构说明
│   └── assets/
├── .github/                             # Issue template / CI
├── CONTRIBUTING.md                      # 贡献指南与分支策略
├── LICENSE
├── README.md                            # English README
└── README_CN.md                         # 中文 README
```

---

## 🗺️ 开发路线图 | Roadmap

### Phase 1 — MVP ✨ 跑通全链路

> 目标：用户输入一句话 → 得到一个可安装的 APK

- [x] 接入 Claude / OpenAI / Qwen API，实现基础代码生成
- [x] 集成编译模块（JavacTool + D8 + AAPT2）
- [x] 实现单 Activity + View 体系的应用生成
- [x] 自动修复循环
- [x] APK 签名 + PackageInstaller 引导安装
- [x] 支持生成应用图标
- [x] 基础 UI：对话界面 + 编译进度

### Phase 2 — 体验优化 🎨

> 目标：让生成过程可见、可控、可迭代

- [x] 多项目管理 + 版本快照
- [x] 多模型切换支持（GPT-4o / Qwen / Ollama）
- [x] 插件系统 — 生成的应用可直接在 VibeApp 内运行，无需安装（基于 Shadow 方案，5 个进程隔离槽位）
- [ ] XML 静态预览（无需编译的即时渲染）
- [ ] 编译缓存 + 增量编译
- [ ] 多轮对话迭代优化
- [ ] 支持 AI 多模态

### Phase 3 — 生态扩展 🌍

> 目标：支持更复杂的 App 和社区生态

- [ ] Kotlin + Jetpack Compose 支持
- [ ] 支持完整编译流程，支持引入第三方库
- [ ] 插件系统增强 — 插件模式下支持 AndroidX/Material Components
- [ ] 支持本地 termux，git
- [ ] 社区模板市场
- [ ] 国际化（i18n）

---

## 🙏 致谢 | Acknowledgments

VibeApp 站在以下优秀开源项目的肩膀上：

| 项目 | 贡献 |
|------|------|
| [gpt_mobile](https://github.com/Taewan-P/gpt_mobile) | AI Chat UI 参考 |
| [CodeAssist](https://github.com/tyron12233/CodeAssist/) | 设备端完整 Android IDE，验证了全链路可行性 |
| [Shadow](https://github.com/Tencent/Shadow) | 腾讯插件化框架 — 启发了宿主委托模式，实现生成应用免安装直接在 VibeApp 内运行 |

---

## 🤝 参与贡献 | Contributing

我们欢迎任何形式的贡献！请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解详情。

**贡献方向：**

- 🐛 Bug 报告和功能建议
- 🤖 改进 AI 代码生成的 Prompt 模板
- 📱 扩展支持的 App 类型和 UI 组件
- ⚡ 改善编译链的稳定性和速度
- 📖 完善文档和示例

---

## 📄 许可证 | License

本项目采用 [GPL-3.0 License](LICENSE) 开源协议。

---

## 💡 名字的由来

**VibeApp**，中文名**意造**。

「Vibe」来自 Vibe Coding——用自然语言驱动 AI 写代码的方式。
「意造」取自「用意念造出一个东西」，两个字传递了想法（意）和创造（造）。

> 普通人第一次感受到「我造了一款真正的 App」——这是 VibeApp 存在的全部意义。

---

<p align="center">
  Made with ❤️ for everyone who ever had an app idea but didn't know how to build it.
</p>
