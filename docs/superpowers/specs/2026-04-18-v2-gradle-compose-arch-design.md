# VibeApp v2.0 架构升级：Kotlin + Jetpack Compose + Gradle + 进程内运行

> 设计文档 | 2026-04-18 | 目标分支：`v2-arch`
>
> 参考项目：[termux/termux-app](https://github.com/termux/termux-app) · [tyron12233/CodeAssist](https://github.com/tyron12233/CodeAssist) · [Tencent/Shadow](https://github.com/Tencent/Shadow)

---

## 目录

1. [概述与目标](#1-概述与目标)
2. [顶层架构](#2-顶层架构)
3. [`:build-runtime` 模块](#3-build-runtime-模块)
4. [`:build-gradle` 模块与 Gradle 集成](#4-build-gradle-模块与-gradle-集成)
5. [项目模板与默认依赖](#5-项目模板与默认依赖)
6. [Agent 改造](#6-agent-改造)
7. [`:plugin-host` 与 Shadow 进程内运行](#7-plugin-host-与-shadow-进程内运行)
8. [老项目归档与升级路径](#8-老项目归档与升级路径)
9. [代码库变更清单](#9-代码库变更清单)
10. [分阶段开发计划](#10-分阶段开发计划)
11. [风险登记](#11-风险登记)
12. [Future Work](#12-future-work)
13. [开放问题](#13-开放问题)
14. [术语表](#14-术语表)

---

## 1. 概述与目标

### 1.1 背景

VibeApp v1.x 在设备上通过 `build-engine` 直接驱动 `AAPT2 → Javac → D8 → ApkBuilder → ApkSigner`，完成"端上 Java+XML Android 应用"的生成与构建。这套链路秒级冷构建、小 APK、零外部依赖，是产品核心差异化能力。

但明确的限制也在 `docs/build-chain.md` "已知限制" 中列出：不支持 Kotlin、不支持三方库、不支持 Jetpack Compose。随着 AI 代码生成能力的发展，用户越来越希望"能做真实 Android 应用"，而不是"能做受限的 demo 应用"。

v2.0 是一次断层式架构升级：**把 VibeApp 从"受限 APK 生成器"重定位为"端上完整 Android 开发环境"**。生成的应用使用业界主流技术栈（Kotlin + Compose），构建走真 Gradle（支持完整 Maven 依赖 + AAR + transitive），并且支持在 VibeApp 进程内直接运行（通过 Tencent/Shadow）。

### 1.2 目标（v2.0）

1. 生成的应用**完整支持 Kotlin + Jetpack Compose**，默认强制此栈。
2. 端上跑**真 Gradle**（由 VibeApp 锁定版本），天然支持 Maven Central / Aliyun 镜像、transitive 依赖、AAR。
3. 提供可选的"**在 VibeApp 内运行**"模式，借助 Tencent/Shadow 官方 Gradle 插件完成 Javassist 变换；同一项目同时产出标准 APK 与 plugin APK。
4. `build-engine` 彻底退役；老 Java+XML 项目降级为"只读归档 + 导出源码 zip"。
5. 完整构建链离线可重复：首次下载 ~180MB（压缩） / ~500MB（解压）的构建环境到 `filesDir/usr/`，后续构建不再依赖网络（除 Maven 依赖解析）。
6. 发行渠道仅限 GitHub Release（不考虑 Play Store 合规）。

### 1.3 非目标（v2.0 不做）

- 多 Gradle 版本共存。
- 交互式 shell / 终端 UI（`:build-runtime` 为后续 git/logcat 预留通用 API，但 v2.0 不向用户暴露）。
- 用户在 VibeApp 内编写自定义 Gradle 插件 / buildSrc / convention plugin（用户可引入三方插件，但不可自建）。
- NDK / C++ 源码编译（只吃预编译 `.so`）。
- Play Store 分发适配。
- 多模块项目模板（用户可手动 `include(":lib")`，Gradle 能跑，但 agent 不主动生成）。
- Kotlin incremental 的 VibeApp 自建缓存层（依赖 Gradle 原生 incremental）。

### 1.4 顶层原则

- **单引擎路线**：删除 `build-engine`，Gradle 成为唯一路径。简化心智负担，避免长期维护两套状态机。
- **Fail fast, fail clearly**：bootstrap 状态、Gradle daemon 状态、构建错误都向用户/agent 以结构化形式透出；任何隐藏态都必须有显式恢复入口。
- **通用底座**：`:build-runtime` 不面向 Gradle 一类客户设计，而是通用"端上 native 进程 + stdio 流"抽象，供 v2.x 的 git、logcat、SDK manager 等复用。
- **版本单一锁**：v2.0 所有 VibeApp 管辖下的项目使用同一套 Kotlin / Gradle / AGP / Compose 版本组合。用户不能切换 Gradle 版本。

---

## 2. 顶层架构

### 2.1 模块图

```text
┌───────────────────────────────────────────────────────────────────────┐
│                            VibeApp (app module)                        │
│   presentation/   feature/agent/   feature/project/   data/            │
└────────────────────┬──────────────────────────────────────────────────┘
                     │
   ┌─────────────────┼───────────────────────────┐
   ▼                 ▼                           ▼
┌──────────────┐ ┌──────────────────────┐ ┌──────────────────────────┐
│ :build-gradle│ │ :build-runtime       │ │ :plugin-host (Shadow)    │
│              │ │                      │ │                          │
│ - Tooling    │ │ - NativeProcess      │ │ - PluginRunner           │
│   API client │ │ - RuntimeBootstrapper│ │ - PluginSession          │
│ - IPC w/     │ │ - ProcessLauncher    │ │ - PluginProcessSlotMgr   │
│   GradleHost │ │ - BootstrapManifest  │ │ - Proxy activities (x4)  │
│ - BuildEvent │ │ - MirrorSelector     │ │ - PluginProcessService   │
│   清洗/聚合  │ │                      │ │                          │
└───────┬──────┘ └──────────┬───────────┘ └───────────┬──────────────┘
        │                   │                         │
        │ uses              │ hosts                   │ loads
        ▼                   ▼                         ▼
┌────────────────────────────────────────────┐  ┌──────────────────────┐
│ On-device bootstrap FS  (filesDir/usr)     │  │ Plugin APK           │
│   opt/jdk-17/   opt/gradle-8.10/           │  │ (Shadow-transformed) │
│   opt/android-sdk-34/                      │  │                      │
│                                            │  │ per process slot:    │
│ filesDir/.gradle/  (GRADLE_USER_HOME)      │  │   :plugin1..plugin4  │
│   caches/  daemon/  init.d/                │  │                      │
└────────────────────────────────────────────┘  └──────────────────────┘

[DELETED] :build-engine (整个 Gradle 子模块)
[DELETED] build-tools/{javac, kotlinc, android-common-resources,
                       build-logic, project, jaxp, logging, ...}
```

### 2.2 模块职责

| 模块 | 职责 | 对外 API 核心类 |
|---|---|---|
| `:build-runtime` | 端上 native 进程底座：bootstrap 文件系统、exec wrapper、env 构建、stdio 流协议、manifest 下载/校验/解压、断点续传、镜像切换 | `NativeProcess`、`ProcessLauncher`、`RuntimeBootstrapper`、`BootstrapManifest`、`MirrorSelector` |
| `:build-gradle` | Gradle Tooling API 封装：启动/维护 `GradleHost` 子进程、JSON IPC、build event 清洗聚合、错误结构化、daemon 生命周期管理 | `GradleBuildService`、`BuildInvocation`、`BuildEvent`、`BuildDiagnostic` |
| `:plugin-host` | Shadow 宿主能力：代理 Activity / Service、进程槽位分配、PluginRunner/Session API、crash 隔离 | `PluginRunner`、`PluginSession`、`PluginProcessSlotManager` |
| `app/feature/agent/tool` | AgentTool 对接新服务层 | 见 [§6.1](#61-agent-tool-矩阵) |
| `app/feature/project` | 项目 metadata、模板生成、升级迁移、老项目只读视图 | `ProjectTemplateGenerator`、`LegacyProjectGuard` |
| `app/feature/projectinit` | 新模板初始化（重写） | `GradleProjectInitializer` |
| `:build-engine` | **删除** | — |

依赖方向：`app → :build-gradle → :build-runtime`；`app → :plugin-host → :build-runtime`；`:build-gradle` 与 `:plugin-host` 不直接依赖对方。

### 2.3 版本矩阵锁

VibeApp v2.0 发版时冻结：

| 组件 | 版本 | 分发方式 |
|---|---|---|
| JDK | Temurin 17.0.13 LTS（ARM64 / ARMv7 / x86_64） | Bootstrap Tier-1 下载 |
| Gradle | 8.10.2 | Bootstrap Tier-1 下载 |
| Android Gradle Plugin | 8.7.2 | Maven 镜像解析 |
| Kotlin | 2.1.0 | Maven 镜像解析 |
| Compose Compiler | 2.1.0（Kotlin 2.x 内置 `kotlin-compose` plugin） | Maven 镜像解析 |
| Compose BOM | 2024.11.00 | Maven 镜像解析 |
| Android compileSdk | 34 | Bootstrap Tier-1 下载 |
| Android targetSdk（生成的 app） | 34 | — |
| Android minSdk（生成的 app） | 24 | — |
| Shadow Gradle Plugin | 官方最新稳定（Phase 5 锁定具体版本） | Maven 镜像解析 |

> 注：**VibeApp 自身**的 `compileSdk=36`、`targetSdk=28`（见 `app/build.gradle.kts`）与生成应用的 SDK 约束解耦。生成应用对 SDK 的约束更保守，以最大化兼容性。

**关于 `targetSdk=28`（Termux 模式，承重决策）**：VibeApp 自身的 `targetSdk` 固定在 28，**不能升级**。原因在 Phase 1d 的实测中暴露：Android 从 API 29 起把应用放进 `untrusted_app_29+` SELinux 域，拒绝 `execute_no_trans` on `app_data_file`——即内核拒绝 exec 任何解析到 `filesDir`/`cacheDir` 的路径（包括指向 `/system/bin/` 的符号链接）。这直接击穿"下载 JDK/Gradle 到 `filesDir/usr/opt/` 然后 exec"的核心前提。`proot`、`memfd_create`、`ptrace` 路径改写都无法绕过（SELinux 检查在 syscall 早期发生，早于这些机制）。唯一干净的工程解是落入 `untrusted_app_27/_28` 域，即 `targetSdk ≤ 28`。Termux / CodeAssist / Pydroid 等同类应用都做了同样的选择。代价：Android 10+ 安装时会弹一次"旧版本应用"警告；永久无法上架 Google Play——但发行渠道本就仅限 GitHub Release，这不是约束。**`compileSdk` 保持 36**，所以代码里依然可以使用任何现代 API。`:build-runtime` 模块的 instrumented-test APK 通过 `android.testOptions.targetSdk = 28` 继承同一个 SELinux 域，保证测试环境与生产一致。

Kotlin / Compose Compiler / AGP / Gradle 的版本兼容矩阵由 JetBrains / AGP release note 约束，未来升级必须整组同步。

---

## 3. `:build-runtime` 模块

### 3.1 定位

**通用"端上 native 进程运行时"抽象**，不是"Gradle 启动器"。

v2.0 的消费者只有 `:build-gradle` 与 `:plugin-host`（后者读取 bootstrap 里的 `aapt2` 等工具），但 API 设计面向后续扩展：git、logcat、ADB-over-USB bridge、SDK manager、任意命令行工具都将复用本模块。

### 3.2 文件系统布局

所有端上构建相关资源集中到 `filesDir/usr/`（镜像 Termux 的 `$PREFIX` 约定，便于后续引入 Termux 生态二进制）：

```text
filesDir/
├── usr/                              # $PREFIX
│   ├── bin/                          # 默认 PATH 根
│   │   ├── java        → ../opt/jdk/bin/java          (symlink)
│   │   ├── gradle      → ../opt/gradle/bin/gradle     (symlink)
│   │   ├── aapt2       → ../opt/android-sdk/build-tools/34.0.0/aapt2
│   │   ├── d8          → ../opt/android-sdk/build-tools/34.0.0/d8
│   │   └── (future: git, logcat-bridge, ...)
│   ├── lib/
│   │   ├── libtermux-exec.so         # LD_PRELOAD shebang 修正器
│   │   └── libzstd.so                # 解压依赖（某些 ABI 可能需要）
│   ├── opt/
│   │   ├── jdk-17.0.13/              # Temurin JDK（按 ABI）
│   │   ├── gradle-8.10.2/            # Gradle distribution
│   │   ├── android-sdk/              # 最小 SDK
│   │   │   ├── platforms/android-34/android.jar
│   │   │   ├── build-tools/34.0.0/{aapt2, d8, lib/apksig.jar, ...}
│   │   │   └── licenses/             # 预接受（避免 AGP 报 license 错）
│   │   └── vibeapp-gradle-host/      # GradleHost 自带的 runner jar（见 §4）
│   │       └── vibeapp-gradle-host.jar
│   ├── etc/                          # 预留配置
│   └── tmp/                          # TMPDIR 指向此处
├── projects/                         # 已有：用户项目目录
└── .gradle/                          # GRADLE_USER_HOME（全项目共享）
    ├── caches/modules-2/             # Maven 依赖缓存
    ├── daemon/                       # Gradle Daemon PID / 日志
    └── init.d/
        └── vibeapp-init.gradle.kts   # VibeApp 注入脚本（见 §4.4）
```

关键设计决策：

| 决策 | 选择 | 理由 |
|---|---|---|
| JDK 分发形态 | 预解压（下载后一次性解压到 `opt/jdk-17.0.13/`） | 每次 exec 不能再解包；磁盘占用可接受 |
| `GRADLE_USER_HOME` 位置 | `filesDir/.gradle/`（不放在 `usr/` 内） | 用户触发"清理构建缓存"时只动 `.gradle`，不动 bootstrap |
| Android SDK 最小化 | `platforms/android-34` + `build-tools/34.0.0` + `licenses/` | AGP 不需要完整 SDK，省 200MB+ |
| 按 ABI 打包 JDK | arm64-v8a / armeabi-v7a / x86_64 三份 manifest artifact | 手机端 ABI 确定，按需下载一份 |

### 3.3 Bootstrap 分层下载模型

三层结构：

| Tier | 内容 | 来源 | 体积（压缩） | 触发 |
|---|---|---|---|---|
| **0** | `libbuildruntime.so`、`libtermux-exec.so`（所有 ABI） | APK `jniLibs/` | ~2MB | 安装即有 |
| **1** | JDK 17 + Gradle 8.10 + minimal Android SDK-34 + GradleHost runner jar | GitHub Release assets | ~180MB（解压后 ~500MB） | 首次构建 |
| **2**（v2.0 不做） | 额外 SDK 平台、NDK、extra build-tools、git、logcat | 同上 | — | 按需 |

每个 Tier-1 组件是独立 artifact，独立哈希，独立重试：

```text
bootstrap-manifest-v2.0.0.json      (GitHub Release 根)
├── jdk-17.0.13-arm64-v8a.tar.zst       (~83MB, sha256:...)
├── jdk-17.0.13-armeabi-v7a.tar.zst     (~76MB, sha256:...)
├── jdk-17.0.13-x86_64.tar.zst          (~85MB, sha256:...)
├── gradle-8.10.2-noarch.tar.zst        (~68MB, sha256:...)
├── android-sdk-34-minimal.tar.zst      (~95MB, sha256:...)
└── vibeapp-gradle-host-v2.0.0.jar      (~2MB, sha256:...)
```

- **压缩格式**：`zstd`（比 gzip 小 ~25%、解压快 2-3x）。Android 14+ 可直接链接 `libzstd.so`；更低 SDK 通过 JNI binding 使用。
- **完整性**：每 artifact SHA-256；manifest 本身用 Ed25519 签名，pubkey embedded in APK。
- **断点续传**：HTTP `Range` header；temp 文件每 1MB 写盘一次；异常退出可续传。
- **镜像策略**：见 [§3.5](#35-镜像策略)。

### 3.4 Bootstrap 生命周期状态机

```text
                     ┌──────────────┐
                     │ NotInstalled │◄─────┐
                     └──────┬───────┘      │
                            │ trigger      │
                            ▼              │ delete usr/
                     ┌──────────────┐      │
                     │ Downloading  │      │
                     │ [component,  │      │
                     │  progress]   │      │
                     └──┬───────┬───┘      │
          fail / abort │       │ all done  │
                       │       ▼           │
                       │  ┌──────────┐     │
                       │  │ Verifying│     │
                       │  └────┬─────┘     │
                       │       │ OK        │
                       │       ▼           │
                       │  ┌──────────┐     │
                       │  │ Unpacking│     │
                       │  └────┬─────┘     │
                       │       │           │
                       │       ▼           │
                       │  ┌──────────┐     │
                       │  │Installing│     │
                       │  │(symlink, │     │
                       │  │ chmod,   │     │
                       │  │ licenses)│     │
                       │  └────┬─────┘     │
                       │       │           │
                       ▼       ▼           │
                  ┌────────┐ ┌───────┐     │
                  │ Failed │ │ Ready │     │
                  │[reason]│ │[v=x.y]│     │
                  └────┬───┘ └───┬───┘     │
                       │         │         │
                       │         │ smoke   │
                       │         │ test    │
                       │         │ fail    │
                       │         ▼         │
                       │    ┌──────────┐   │
                       │    │Corrupted │   │
                       │    └────┬─────┘   │
                       │         │         │
                       └────►────┴────►────┘  (user "重建构建环境")
```

- 中断（系统杀进程、网络断）：状态落 DataStore，下次启动自动 resume。
- 升级（VibeApp 自身升级后 manifest version 变）：只下载 hash 变化的组件。
- `Ready` 状态下任何 smoke test（`java -version`、`gradle --version`）失败 → 落 `Corrupted` → UI 展示"构建环境损坏 [重建]"按钮。

状态持久化：DataStore key `bootstrap_state` 存 JSON，包含当前阶段、已完成组件列表、已下载字节数。

### 3.5 镜像策略

两级镜像，自动切换：

| 优先级 | 域名 | 用途 |
|---|---|---|
| 1 | GitHub Release CDN（`github.com/.../releases/download/`） | 主镜像 |
| 2 | Aliyun OSS 或清华源（由 VibeApp 维护的 `mirror.vibeapp.xyz/releases/`） | Fallback |

切换规则：
- 每个 artifact 下载时先尝试主镜像，失败（超时 / HTTP 4xx-5xx）自动切到 fallback，不需要用户干预。
- 首次下载命中 fallback 后，后续整个下载会话都用 fallback。
- 用户可在 Settings 看到"当前镜像：主 / 备"状态，但不提供手动覆盖。

### 3.6 `NativeProcess` API（Kotlin）

```kotlin
interface NativeProcess {
    val pid: Int
    val events: Flow<ProcessEvent>
    suspend fun awaitExit(): Int
    fun signal(signum: Int = SIGTERM)
    fun writeStdin(bytes: ByteArray)   // 预留给 git / interactive 场景
}

sealed interface ProcessEvent {
    data class Stdout(val bytes: ByteArray) : ProcessEvent
    data class Stderr(val bytes: ByteArray) : ProcessEvent
    data class Exited(val code: Int) : ProcessEvent
}

class ProcessLauncher @Inject constructor(
    private val bootstrap: RuntimeBootstrapper,
    private val envBuilder: ProcessEnvBuilder,
) {
    suspend fun launch(
        executable: String,                   // "java" / "gradle" / 绝对路径
        args: List<String>,
        cwd: File,
        env: Map<String, String> = emptyMap(),
        stdin: Flow<ByteArray>? = null,
    ): NativeProcess
}
```

内部实现：
- `ProcessEnvBuilder` 构造基线 env：`PATH`、`LD_LIBRARY_PATH`、`LD_PRELOAD=$PREFIX/lib/libtermux-exec.so`、`JAVA_HOME`、`ANDROID_HOME`、`GRADLE_USER_HOME`、`TMPDIR`、`HOME`。用户传入的 `env` 合并时以用户为准。
- 通过 JNI 调用 `fork()` + `execve()`（非 `ProcessBuilder`，因为需要精细控制 argv/envp/close_fds）。
- stdout/stderr 通过 pipe 读回，Kotlin 侧以 `Flow<ProcessEvent>` 暴露，背压由 Flow 控制。

### 3.7 exec wrapper：`libtermux-exec.so`

来源：termux/termux-exec（GPLv3），抽取 `exec.c`、`path.c` 两个文件到 `:build-runtime/src/main/cpp/`，CMake 构建产出 `libtermux-exec.so`。

通过 `LD_PRELOAD` 注入到每个子进程，拦截 `execve` 并处理：

- **Shebang 修正**：`#!/usr/bin/env bash` → 重写为 `$PREFIX/bin/env $PREFIX/bin/bash`。Android 根文件系统没有 `/usr/bin/env`，不处理则所有脚本失败。
- **路径规范化**：`/system/bin/*` 保持原样；其他绝对路径参考 `$PREFIX` 映射。
- **信号传递**：子进程间的 `SIGTERM` 能正确回传到 VibeApp 应用层（否则 Gradle 取消构建时 Worker 进程会变僵尸）。
- **子进程继承**：`LD_PRELOAD` 通过 env 继承，Java → Gradle launcher → Worker 链条自动覆盖。

**实现状态（截至 Phase 1d）**：`libtermux-exec.so` 为 ~170 LoC 的 clean-room C 实现，位于 `build-runtime/src/main/cpp/termux_exec/`。范围保守：只重写 `execve()`，且只处理 `#!/usr/bin/env <interp>` 这一种 shebang 形式。解释器路径解析优先读取 `VIBEAPP_USR_PREFIX` 环境变量（由 `ProcessEnvBuilder` 注入 `fs.usrRoot.absolutePath`），缺省才回退到编译期 `VIBEAPP_PREFIX` 宏 `/data/user/0/com.vibe.app/files/usr`。运行时 prefix 解析确保在不同 app 包名下（包括 instrumented test 的 `.test` APK）都能正确工作。`LD_PRELOAD` 由 `ProcessEnvBuilder` 在每次 `launch()` 时注入到子进程。

**作用域（重要）**：`libtermux-exec.so` 只能拦截**来自后代进程的 execve**——即那些通过 LD_PRELOAD 在进程启动时加载了它的进程的 children。典型场景：我们启动 `java` 进程，java 的 envp 里带 LD_PRELOAD，java 的 dynamic linker 在启动时加载 libtermux-exec.so，随后 java/gradle 执行的任何 `execve`（worker JVM、kotlinc、R8、shell 脚本等）都被拦截。这刚好覆盖了 Phase 2+ 的实际调用模式。

**不能做的事**：`libtermux-exec.so` 无法拦截 VibeApp 自己 `ProcessLauncher.launch` 那层的首次 execve——我们的 `process_launcher.c` 在 libc 链接期就把 `execve` 符号绑到了 libc，Android 的 `System.loadLibrary` 使用 `RTLD_LOCAL` 加载 JNI 库，符号不会 interpose 到 libbuildruntime.so 的查找作用域。实践中这不是问题：Phase 2 启动 Gradle 的方式是 `ProcessLauncher.launch("$PREFIX/opt/jdk/bin/java", ...)`——exec 一个真二进制，不存在 shebang；`java` 的后代进程才会有 shebang 需求，而那时 LD_PRELOAD 正常工作。

**已知验证局限**：`ShebangInstrumentedTest` 故意不测试"用 `ProcessLauncher.launch` 直接 exec 一个 `#!/usr/bin/env sh` 脚本"的场景——这个场景不代表真实调用链路，且受限于上述 `RTLD_LOCAL` 特性。后代 shebang 重写的覆盖测试在 Phase 2 的 Gradle 测试中自然发生（gradle daemon 跑 kotlinc/R8 时如遇 `#!/usr/bin/env sh` 脚本会验证到）。

**覆盖测试**：`:build-runtime` 的 `ShebangInstrumentedTest` 有 2 个 on-device 测试——`direct_binary_exec_unaffected_by_preload`（保证 LD_PRELOAD 对直接二进制 exec 透明）和 `toybox_env_shows_LD_PRELOAD_and_VIBEAPP_USR_PREFIX`（证明环境变量正确注入到子进程）。Phase 1d 后又加入 `targetSdk=28` 这一承重决策，SELinux 的 exec-from-filesDir 限制解除——这是 Phase 2 能真正 exec downloaded JDK 的前置条件。

### 3.8 进程生命周期与 Daemon 管理

| 事件 | 行为 |
|---|---|
| App 切到后台 | 子进程**不杀**；用户期望 Gradle 继续完成 |
| App 被系统杀死（OOM / LMKD / swipe 清理） | 子进程随父进程 `SIGKILL` 死亡（Android 机制） |
| App 重启 & 发现 `.gradle/daemon/*.pid` 残留 | 扫描 PID 是否存活；死进程清理 PID 文件；**v2.0 不做跨会话 daemon 复活** |
| 用户"停止构建" | `SIGTERM` → 5s 后 `SIGKILL` → UI 状态回滚 |
| 用户"清理构建缓存" | Kill 所有子进程 → 删除 `filesDir/.gradle/caches/` → 保留 `usr/` |
| 用户"重建构建环境" | Kill 所有 → 删除 `usr/` 与 `.gradle/` → 状态回到 `NotInstalled` |

Gradle Daemon 的 idle timeout 通过 `org.gradle.daemon.idletimeout=600000`（10 分钟）控制。

### 3.9 测试策略

| 层级 | 测试 | 工具 |
|---|---|---|
| 纯单元测试 | 状态机迁移、manifest 解析、hash 校验、mirror 切换逻辑 | JUnit |
| Instrumented（真机/模拟器） | `java -version`、`gradle --version`、shebang 修正、信号传递、`LD_PRELOAD` 正确挂载 | AndroidX Test |
| 下载链路 | mock HTTP server + 故障注入：断网 / truncation / hash 不匹配 / ABI 错位 | MockWebServer |
| 跨 SDK | min 24 / target 34 / host OS 36 三档真机 + 模拟器 | CI matrix |

---

## 4. `:build-gradle` 模块与 Gradle 集成

### 4.1 为什么需要 `GradleHost` 中间进程

直接让 VibeApp ART 进程 embed Gradle Tooling API 有四个问题：

| 问题 | 说明 |
|---|---|
| ART ≠ Hotspot | Tooling API 传递依赖（`sun.misc.Unsafe`、`java.lang.invoke`、Ivy resolver 等）在 ART 上运行不全 |
| `LD_PRELOAD` 继承 | 从 ART 直接 `ProcessBuilder.start("java")` 的子进程拿不到 `libtermux-exec.so`，shebang 修正失效 |
| OOM 风险 | Gradle 客户端 + resolver 吃 300-500MB 堆，挤压 app 进程会被 LMKD 干掉 |
| 类加载冲突 | Tooling API 带自己的 Kotlin stdlib 版本，与 app dex 冲突 |

**结论**：另起轻量 Java 进程 `GradleHost`（跑在 bootstrap 的 Hotspot JDK 里）承载 Tooling API，通过 IPC 与 app 通信。

### 4.2 三层进程拓扑

```text
┌─────────────────────────────────────────────────────────────────┐
│ VibeApp 进程（ART）                                               │
│                                                                 │
│   GradleBuildService (Kotlin)                                   │
│     │                                                           │
│     │ 通过 :build-runtime 的 ProcessLauncher spawn               │
│     │ JSON-line IPC over stdin/stdout                           │
└──────┬──────────────────────────────────────────────────────────┘
       │ spawn (idle 10min 后自毁)
       ▼
┌─────────────────────────────────────────────────────────────────┐
│ GradleHost 进程（Hotspot JDK 17，长驻）                           │
│                                                                 │
│   - Tooling API Client (gradle-tooling-api:8.10.x)              │
│   - BuildEventDispatcher（ProgressListener 订阅者）             │
│   - 动作：跑 gradle task、复用 daemon                            │
└──────┬──────────────────────────────────────────────────────────┘
       │ Tooling API daemon protocol (Unix domain socket)
       ▼
┌─────────────────────────────────────────────────────────────────┐
│ Gradle Daemon 进程（同 Hotspot JDK，按项目分组）                  │
│                                                                 │
│   - 真正的 Gradle 8.10 运行时                                    │
│   - Kotlin / Java / Compose / AGP 编译、AAPT2、R8、签名            │
│   - Worker API 子进程（kotlinc、javac、R8、AAPT2 ...）            │
└─────────────────────────────────────────────────────────────────┘
```

生命周期：

| 层 | 生命周期 | 复用规则 |
|---|---|---|
| `GradleBuildService` | 跟 VibeApp 进程 | 单例 |
| `GradleHost` | 首次构建 lazy spawn；idle 10 min 自退 | 同会话内所有项目构建复用 |
| Gradle Daemon | Tooling API 自动管理；`org.gradle.daemon.idletimeout=10min` | 同项目的连续构建复用；切项目可能重新拉一个 |

### 4.3 IPC 协议（`GradleBuildService` ↔ `GradleHost`）

采用 JSON-line（每行一个事件），简单、易 debug、不需要额外依赖。

**VibeApp → Host（请求）**：

```jsonc
{"type":"RunBuild","buildId":"b-42","projectPath":"/data/.../projects/p7","tasks":[":app:assembleDebug"],"args":["--no-configuration-cache"]}
{"type":"CancelBuild","buildId":"b-42"}
{"type":"ShutdownHost"}
{"type":"WarmUp","projectPath":"/data/.../projects/p7"}
```

**Host → VibeApp（事件流）**：

```jsonc
{"type":"HostReady","hostVersion":"2.0.0","gradleVersion":"8.10.2","jdkVersion":"17.0.13"}
{"type":"BuildStart","buildId":"b-42","ts":1700000000}
{"type":"TaskStart","buildId":"b-42","path":":app:compileDebugKotlin"}
{"type":"Diagnostic","buildId":"b-42","severity":"ERROR","file":"app/src/main/kotlin/.../MainActivity.kt","line":42,"column":8,"message":"Unresolved reference: Button","source":"KOTLIN_COMPILER"}
{"type":"TaskFinish","buildId":"b-42","path":":app:compileDebugKotlin","outcome":"FAILED","durationMs":3412}
{"type":"BuildFinish","buildId":"b-42","success":false,"durationMs":15234,"failureSummary":"Execution failed for task ':app:compileDebugKotlin'."}
{"type":"Log","buildId":"b-42","level":"LIFECYCLE","text":"> Task :app:compileDebugKotlin"}
```

**完整事件 schema**（Kotlin 端建模）：

```kotlin
sealed interface BuildEvent {
    val buildId: String

    data class BuildStart(override val buildId: String, val ts: Long) : BuildEvent
    data class TaskStart(override val buildId: String, val path: String) : BuildEvent
    data class TaskFinish(
        override val buildId: String,
        val path: String,
        val outcome: TaskOutcome,
        val durationMs: Long,
    ) : BuildEvent
    data class Diagnostic(
        override val buildId: String,
        val severity: Severity,
        val source: DiagnosticSource,
        val file: String?,
        val line: Int?,
        val column: Int?,
        val message: String,
    ) : BuildEvent
    data class Log(
        override val buildId: String,
        val level: LogLevel,
        val text: String,
    ) : BuildEvent
    data class BuildFinish(
        override val buildId: String,
        val success: Boolean,
        val durationMs: Long,
        val failureSummary: String?,
    ) : BuildEvent
}

enum class TaskOutcome { SUCCESS, FAILED, SKIPPED, UP_TO_DATE, FROM_CACHE }
enum class Severity { ERROR, WARNING, INFO }
enum class LogLevel { LIFECYCLE, INFO, DEBUG, WARN, QUIET }
enum class DiagnosticSource {
    DEPENDENCY_RESOLUTION,
    KOTLIN_COMPILER,
    JAVA_COMPILER,
    KSP,
    AAPT2,
    R8,
    GRADLE,   // 脚本/配置错误
    UNKNOWN,
}
```

### 4.4 `init.gradle.kts`（VibeApp 注入）

路径：`filesDir/.gradle/init.d/vibeapp-init.gradle.kts`。每次 Gradle invocation 自动加载。完整样例：

```kotlin
// VibeApp v2.0 injected init script
// 不要手动编辑 —— VibeApp 每次启动会覆盖

import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// 1) 强制 Maven 仓库顺序：优先阿里云镜像
allprojects {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
}

// 2) 强制 JVM target = 17，防止用户/agent 误降级
allprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            // Kotlin 2.x 新 API
            // jvmTarget set via android { compileOptions } 由 AGP 统一
        }
    }
}

// 3) 构建事件导出：VibeApp GradleHost 会通过 Tooling API 接收
//    这里是"带内"兜底，防止 Tooling API 丢某些事件。写入 build/vibeapp-events.jsonl。
gradle.taskGraph.whenReady {
    val logFile = gradle.rootProject.layout.buildDirectory
        .file("vibeapp-events.jsonl").get().asFile
    logFile.parentFile.mkdirs()
    // ... writer 实现略
}

// 4) 禁用 Gradle 问卷 / 自动更新检查 / 匿名统计，避免网络干扰
gradle.startParameter.isOffline = false  // Maven 依赖仍需下载；不是真离线
System.setProperty("org.gradle.welcome", "never")

// 5) Gradle 问题打印：完整 stacktrace，便于 diagnostic 解析
gradle.startParameter.showStacktrace =
    org.gradle.api.logging.configuration.ShowStacktrace.ALWAYS
```

### 4.5 Gradle 启动后烟雾测试

`GradleHost` 启动后立即跑一次"空 dummy project `:help`"：

```text
dummy project 内容：
  settings.gradle.kts
    rootProject.name = "vibeapp-smoke-test"
  build.gradle.kts
    (空，或只 apply application false)

执行 :help
```

检查项：

| 项 | 通过标准 |
|---|---|
| `java -version`（在 smoke test 启动前由 ProcessLauncher 跑一次）| 输出 `17.0.13` |
| `gradle --version` | 输出 `Gradle 8.10.2` |
| `aapt2 version` | 输出 `Android Asset Packaging Tool (aapt) 2.19` 之类 |
| Dummy `:help` | exit 0 且 `< 60s` |

任一失败 → 标记 bootstrap `Corrupted` → 引导用户"重建构建环境"。

### 4.6 `GradleBuildService` API（app 侧）

```kotlin
interface GradleBuildService {
    val hostState: StateFlow<GradleHostState>

    suspend fun warmUp(projectPath: File)                          // 可选预热
    fun invoke(
        projectPath: File,
        tasks: List<String>,
        args: List<String> = emptyList(),
    ): BuildInvocation
}

interface BuildInvocation {
    val buildId: String
    val events: Flow<BuildEvent>        // 合并：TaskStart/Finish/Diagnostic/Log/BuildFinish
    suspend fun awaitFinish(): BuildResult
    fun cancel()
}

sealed interface GradleHostState {
    object Stopped : GradleHostState
    object Starting : GradleHostState
    data class Ready(val hostVersion: String) : GradleHostState
    data class Failed(val reason: String) : GradleHostState
}

data class BuildResult(
    val buildId: String,
    val success: Boolean,
    val durationMs: Long,
    val diagnostics: List<BuildDiagnostic>,
    val outputArtifacts: List<File>,    // assembleDebug → app-debug.apk
)
```

Task 白名单（v2.0）：

```kotlin
object TaskWhitelist {
    val ALLOWED = setOf(
        ":app:assembleDebug",
        ":app:assembleDebugPlugin",      // Shadow 插件变换产物
        ":app:clean",
        ":app:dependencies",
        ":help",
        "help",
    )
}
```

白名单外的调用直接拒绝（不是 Gradle 拒绝，是 `GradleBuildService` 在 app 侧拒绝），避免 agent 误触发 `:uninstall*`、`:publish*` 等敏感任务。

### 4.7 Build Diagnostic 清洗管道

详见 [§6.4](#64-build-diagnostic-清洗管道细节)。

---

## 5. 项目模板与默认依赖

### 5.1 新建项目的产物结构

```text
filesDir/projects/{projectId}/
├── settings.gradle.kts
├── build.gradle.kts                  (root)
├── gradle.properties
├── local.properties                  (sdk.dir 指向 $PREFIX/opt/android-sdk)
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/vibe/generated/p{projectId}/
│       │   ├── MainActivity.kt
│       │   ├── App.kt
│       │   └── ui/theme/
│       │       ├── Color.kt
│       │       ├── Theme.kt
│       │       └── Type.kt
│       └── res/
│           ├── values/{strings, themes, colors}.xml
│           ├── mipmap-anydpi-v26/ic_launcher.xml
│           └── drawable/ic_launcher_foreground.xml
└── README.md                         (简介 + "如何导出到电脑上构建")
```

### 5.2 `gradle/libs.versions.toml`

版本锁 single source：

```toml
[versions]
agp = "8.7.2"
kotlin = "2.1.0"
composeBom = "2024.11.00"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
lifecycleCompose = "2.8.7"
navigationCompose = "2.8.4"
shadow = "v2.0.0-snapshot"           # Phase 5 锁定具体版本

[libraries]
androidx-core-ktx            = { module = "androidx.core:core-ktx",                      version.ref = "coreKtx" }
androidx-activity-compose    = { module = "androidx.activity:activity-compose",          version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycleCompose" }
androidx-navigation-compose  = { module = "androidx.navigation:navigation-compose",      version.ref = "navigationCompose" }
compose-bom                  = { module = "androidx.compose:compose-bom",                version.ref = "composeBom" }
compose-ui                   = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview   = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3            = { module = "androidx.compose.material3:material3" }
compose-material-icons-ext   = { module = "androidx.compose.material:material-icons-extended" }

[plugins]
android-application = { id = "com.android.application",                    version.ref = "agp" }
kotlin-android      = { id = "org.jetbrains.kotlin.android",               version.ref = "kotlin" }
kotlin-compose      = { id = "org.jetbrains.kotlin.plugin.compose",        version.ref = "kotlin" }
shadow-plugin       = { id = "com.tencent.shadow.plugin.build_host.v2",    version.ref = "shadow" }
```

出厂工具箱为 **9 个 library 坐标 + 4 个 plugin 坐标**。Agent 可通过 `AddDependencyTool` 新增任意 Maven 坐标。

### 5.3 `settings.gradle.kts` 模板

```kotlin
pluginManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "{{PROJECT_NAME}}"
include(":app")
```

### 5.4 根 `build.gradle.kts` 模板

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.shadow.plugin) apply false
}
```

### 5.5 `app/build.gradle.kts` 模板

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.shadow.plugin)   // Shadow 官方插件：生成 :app:assembleDebugPlugin
}

android {
    namespace = "{{PACKAGE_NAME}}"
    compileSdk = 34

    defaultConfig {
        applicationId = "{{PACKAGE_NAME}}"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.ext)
}

shadow {
    isPlugin = true
    // v2.0 默认配置，Phase 5 根据实际情况调整
}
```

### 5.6 `gradle.properties` 模板

```properties
# JVM heap for Gradle daemon
org.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8
# Gradle parallel execution: 单项目无并行收益，但多模块扩展时有用
org.gradle.parallel=true
# Daemon idle timeout 10 min（同 init.gradle.kts 里兜底）
org.gradle.daemon.idletimeout=600000
# AndroidX enforcement
android.useAndroidX=true
android.nonTransitiveRClass=true
# Kotlin code style
kotlin.code.style=official
# 关闭 build scan 上报
org.gradle.welcome=never
```

### 5.7 `AndroidManifest.xml` 模板

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".App"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.App">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### 5.8 `MainActivity.kt` / `App.kt` 模板

```kotlin
// MainActivity.kt
package {{PACKAGE_NAME}}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import {{PACKAGE_NAME}}.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface {
                    Greeting()
                }
            }
        }
    }
}

@Composable
fun Greeting() {
    Text("Hello, {{PROJECT_NAME}}!")
}
```

```kotlin
// App.kt
package {{PACKAGE_NAME}}

import android.app.Application

class App : Application()
```

### 5.9 Maven 依赖策略

- **默认开放**：用户/agent 可通过 `AddDependencyTool` 引入任意 Maven 坐标，Gradle 正常解析 transitive。
- **镜像优先**：`init.gradle.kts` 强制阿里云镜像优先，自动 fallback 到 Google / Maven Central。
- **缓存位置**：`filesDir/.gradle/caches/modules-2/`。
- **水位保护**：`ProjectCleaner` 定期扫描，>2GB 触发 LRU 清理；用户在 Settings 可见"缓存占用 X MB [清理]"。
- **依赖白名单 / 沙盒**：v2.0 不做，未来考虑。

### 5.10 Gradle Wrapper

`gradle-wrapper.properties` 的 `distributionUrl` 指向本地：

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=file\:{{BOOTSTRAP_GRADLE_DIST_PATH}}
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

其中 `{{BOOTSTRAP_GRADLE_DIST_PATH}}` 在模板生成时注入实际路径（类似 `/data/user/0/com.vibe.app/files/usr/opt/gradle-8.10.2/`）。

用户导出源码 zip 后在外部环境运行 `./gradlew`，wrapper 自动 fallback 到网络拉 8.10.2。

---

## 6. Agent 改造

### 6.1 Agent Tool 矩阵

| 工具 | v1.x | v2.0 |
|---|---|---|
| `read_project_file` | ✅ | ✅ 基准路径从 `projects/{id}/app/` 改为 `projects/{id}/`（项目根） |
| `write_project_file` | ✅ | ✅ 同上 |
| `list_project_files` | ✅ | ✅ 增加 `glob` 过滤（如 `**/*.kt`） |
| `clean_build_cache` | ✅ | ❌ 删除；合并到 `run_gradle(":app:clean")` |
| `run_build_pipeline` | ✅（调 `build-engine`） | ❌ 删除 |
| `rename_project` | ✅ | ✅ |
| `update_project_icon` | ✅ | ✅ |
| `run_gradle` | — | **NEW**：调用 `GradleBuildService.invoke()`，参数为白名单内 task + args |
| `get_build_diagnostics` | — | **NEW**：取最近一次构建的清洗后 diagnostics |
| `add_dependency` | — | **NEW**：原子修改 `libs.versions.toml` + `app/build.gradle.kts` |
| `remove_dependency` | — | **NEW**：反向操作 |
| `run_in_process` | — | **NEW**：触发 Shadow 插件模式构建并在 `:pluginN` 槽位运行 |
| `install_apk` | — | **NEW**：调 `:app:assembleDebug` 后跳系统安装器 |
| `export_project_source` | — | **NEW**：导出项目 zip（legacy 项目也复用） |

### 6.2 工具签名（精选）

```kotlin
@Serializable
data class RunGradleArgs(
    val tasks: List<String>,        // 必须在 TaskWhitelist.ALLOWED 内
    val args: List<String> = emptyList(),
)

@Serializable
data class RunGradleResult(
    val buildId: String,
    val success: Boolean,
    val durationMs: Long,
    val artifactPaths: List<String>,     // 构建产物（如 app-debug.apk）
    val diagnosticsSummary: String,      // 若失败：清洗后 diagnostics 的 markdown 摘要
)

@Serializable
data class AddDependencyArgs(
    val alias: String,                   // libs.versions.toml 的 library 别名
    val coordinate: String,              // "io.coil-kt:coil-compose:2.5.0"
    val configuration: String = "implementation",  // implementation / ksp / etc.
)

@Serializable
data class AddDependencyResult(
    val success: Boolean,
    val versionCatalogChanged: Boolean,
    val buildScriptChanged: Boolean,
    val errorMessage: String?,
)

@Serializable
data class GetBuildDiagnosticsArgs(
    val buildId: String? = null,         // null = 最近一次
    val maxEntries: Int = 30,
    val severities: List<String> = listOf("ERROR"),
)
```

### 6.3 Agent System Prompt 骨架（重写后）

路径：`app/src/main/assets/agent-system-prompt.md`（保留路径，内容完全重写）。

```markdown
You are the VibeApp Kotlin+Compose coding agent. You build Android apps by
writing Kotlin source files and modifying `build.gradle.kts` / `libs.versions.toml`.

## Toolchain (FIXED — do not suggest changing)
- Language: Kotlin {{KOTLIN_VERSION}}
- UI: Jetpack Compose BOM {{COMPOSE_BOM_VERSION}}
- Gradle: {{GRADLE_VERSION}}, AGP {{AGP_VERSION}}, JDK 17
- All activities MUST extend `androidx.activity.ComponentActivity`.
- All UI MUST use `@Composable` + `setContent { ... }`.
- No Java source. No XML layouts. No `AndroidView` unless the user
  explicitly asks for interop with a non-Compose widget.

## Package Constraints (FIXED)
- Package name: `{{PACKAGE_NAME}}`
- Source root:  `app/src/main/kotlin/{{PACKAGE_PATH}}/`

## Tools
- `read_project_file(path)`        — read any file under the project root
- `write_project_file(path, ...)`  — write / overwrite
- `list_project_files(glob?)`      — list files, optional glob
- `add_dependency(alias, coord)`   — add a Maven dep (ALWAYS use this;
                                     NEVER edit build.gradle.kts by hand
                                     to add deps)
- `remove_dependency(alias)`
- `run_gradle(tasks)`              — invoke Gradle; whitelisted tasks only
- `get_build_diagnostics()`        — fetch structured errors from last build
- `run_in_process()`               — build :app:assembleDebugPlugin and run
                                     inside VibeApp
- `install_apk()`                  — build :app:assembleDebug and prompt
                                     install
- `rename_project(new_name)`
- `update_project_icon(...)`

## Workflow
1. Read/write source files via `read_project_file` / `write_project_file`.
2. Add any Maven dependency via `add_dependency` (NEVER edit `.kts` by hand
   to add deps).
3. Run `:app:assembleDebug` via `run_gradle` when ready.
4. On build failure: FIRST call `get_build_diagnostics` — do NOT guess
   errors from memory.
5. On build success: propose `run_in_process` or `install_apk`.

## Constraints
- Do NOT introduce kapt. Use KSP.
- Do NOT enable minify in debug.
- Do NOT downgrade Kotlin / AGP / Compose versions.
- Keep a single Activity unless multi-Activity is essential to the app
  idea.
- Do not use `BuildConfig` for runtime-configurable values — prefer
  `data/` or DataStore.

## Diagnostic Handling
Build diagnostics are pre-filtered by severity and deduped. You'll see
errors grouped by source (Kotlin compiler, AAPT2, dependency resolution,
etc.) with `file:line:column` attribution. Fix the root cause — don't
"catch and ignore".
```

变量 `{{KOTLIN_VERSION}}` 等在 `DefaultAgentLoopCoordinator` 加载时从 `libs.versions.toml` / `BuildConfig` 注入。

### 6.4 Build Diagnostic 清洗管道（细节）

```text
Tooling API structured events (via IPC)
    ↓
DiagnosticIngest
    ├─ filter: severity == ERROR（WARNING 不给 agent，除非显式查询）
    ├─ dedupe: key = (file, line, column, source, message_hash)
    ├─ sort: 按因果前置优先级
    │        1) DEPENDENCY_RESOLUTION (引不到库就没必要看后面)
    │        2) GRADLE (脚本/配置错误)
    │        3) KOTLIN_COMPILER (compileDebugKotlin)
    │        4) JAVA_COMPILER (compileDebugJavaWithJavac)
    │        5) KSP
    │        6) AAPT2 (link/process)
    │        7) R8 / D8
    └─ truncate: 保留前 N=30 条
    ↓
DiagnosticFormatter
    ├─ 按 source 分组（保留顺序）
    ├─ 每条："`file:line:column` [source] message"
    ├─ 附加前后 2 行源码上下文（可选，受 token 预算约束）
    └─ 以 Markdown 渲染
    ↓
Persist: filesDir/projects/{id}/build-diagnostics-{buildId}.json
    ↓
Injected into agent context as tagged block:
    <build-diagnostics build-id="b-42">
    ### DEPENDENCY_RESOLUTION
    - ...
    ### KOTLIN_COMPILER
    - `app/src/main/kotlin/.../MainActivity.kt:42:8` Unresolved reference: Button
    ...
    </build-diagnostics>
```

Agent 可通过 `get_build_diagnostics` 工具显式重拉（比如 context compaction 后丢了）。

### 6.5 Build Diagnostic 数据结构

```kotlin
@Serializable
data class BuildDiagnostic(
    val severity: Severity,
    val source: DiagnosticSource,
    val file: String?,          // 相对项目根
    val line: Int?,
    val column: Int?,
    val message: String,
    val contextLines: List<String> = emptyList(),  // 源码上下文（2 行 before + 2 after）
)

@Serializable
data class DiagnosticReport(
    val buildId: String,
    val buildSuccess: Boolean,
    val totalDiagnostics: Int,
    val truncated: Boolean,
    val entries: List<BuildDiagnostic>,
    val generatedAt: Long,
)
```

---

## 7. `:plugin-host` 与 Shadow 进程内运行

### 7.1 重定位：直接使用官方 Gradle 插件

**完全抛弃** `docs/shadow-plugin-feasibility.md` 中的"generate final code + 端上 ASM 改写 AndroidX"方案。原方案的前提是"端上跑不了 Gradle plugin"；v2.0 该前提不再成立。

新方案：项目模板直接 apply Shadow 官方 Gradle 插件（`com.tencent.shadow.plugin.build_host.v2`），由 Gradle 在构建期应用 16 个 Javassist 变换。

**原 feasibility 文档应标记为 `superseded`**，不再作为实现指南。

### 7.2 双产物构建

同一项目通过两个 Gradle task 产出两种 APK：

| Task | 产物 | 用途 |
|---|---|---|
| `:app:assembleDebug` | 标准 APK（未变换） | 安装到设备 / 导出 APK |
| `:app:assembleDebugPlugin` | Shadow 变换后的 plugin APK | 在 VibeApp 内运行 |

Agent 的 `run_in_process` → 触发 `:app:assembleDebugPlugin` → 拿到产物路径 → 交给 `PluginRunner`。

### 7.3 `:plugin-host` 模块设计

```text
app (VibeApp) ─► :plugin-host
                     │
                     ├─ PluginRunner              (业务入口)
                     ├─ PluginSession             (一次运行的生命周期)
                     ├─ PluginProcessSlotManager  (槽位分配 / LRU)
                     ├─ PluginCrashCollector      (crash 日志采集)
                     └─ 代理组件
                        ├─ PluginDefaultProxyActivity[1..4]
                        ├─ PluginSingleTaskProxyActivity[1..4]
                        └─ PluginProcessService[1..4]
```

### 7.4 4 个进程槽位

VibeApp `AndroidManifest.xml` 静态声明 4 个进程槽位（`:plugin1` ~ `:plugin4`）。每个槽位配两个代理 Activity（`standard` + `singleTask` launch mode）+ 一个宿主 Service。

清单片段：

```xml
<!-- Plugin process slot 1 -->
<activity
    android:name=".shadow.PluginDefaultProxyActivity1"
    android:process=":plugin1"
    android:exported="false"
    android:launchMode="standard"
    android:theme="@style/Theme.PluginContainer" />
<activity
    android:name=".shadow.PluginSingleTaskProxyActivity1"
    android:process=":plugin1"
    android:exported="false"
    android:launchMode="singleTask" />
<service
    android:name=".shadow.PluginProcessService1"
    android:process=":plugin1" />

<!-- 重复 slot 2/3/4 -->
```

### 7.5 `PluginRunner` / `PluginSession` API

```kotlin
interface PluginRunner {
    suspend fun run(
        projectId: String,
        apkPath: File,
        config: PluginLaunchConfig = PluginLaunchConfig(),
    ): PluginSession

    fun runningSessions(): List<PluginSession>
    fun findByProjectId(projectId: String): PluginSession?
}

interface PluginSession {
    val projectId: String
    val slot: Int
    val state: StateFlow<PluginSessionState>
    suspend fun stop()
    suspend fun restart()
}

sealed interface PluginSessionState {
    object Idle : PluginSessionState
    data class Starting(val progress: Int) : PluginSessionState
    data class Running(val pid: Int, val slot: Int, val startedAt: Long) : PluginSessionState
    data class Crashed(val exitReason: String, val crashLog: String?) : PluginSessionState
    object Stopped : PluginSessionState
}

class PluginProcessSlotManager {
    fun acquire(projectId: String): Int            // 返回 1..4；全占则 LRU 驱逐
    fun release(slot: Int)
    fun currentAllocation(): Map<Int, String?>     // slot → projectId
}
```

### 7.6 "Run in App" UX

构建成功后的 chat bubble：

```text
┌───────────────────────────────────────────────────────┐
│ ✓ 构建成功 (:app:assembleDebugPlugin, 12.3s)           │
│                                                        │
│ [在 VibeApp 内运行]  [安装到设备]  [导出 APK]           │
└───────────────────────────────────────────────────────┘
```

点击"在 VibeApp 内运行"后：

- 启动 PluginContainerActivity（实际是 `PluginDefaultProxyActivity{slot}`）
- Shadow runtime 接管 lifecycle
- 用户代码在 `:pluginN` 进程跑起来
- VibeApp 主 UI 显示浮层：

```text
┌─────────────────────────────────────────┐
│ ● 正在运行: MyApp (项目 p7)              │
│ [返回]  [重启]  [停止]  [切换到应用]      │
└─────────────────────────────────────────┘
```

- 同时最多 4 个 plugin session 并存。第 5 次启动：提示"请先停止一个正在运行的应用"，列出当前在跑的 4 个。

三个动作（在 VibeApp 内运行 / 安装到设备 / 导出 APK）同层平级，无默认 CTA。

### 7.7 Crash 隔离

- Plugin 进程崩溃 → VibeApp 主进程不受影响。`PluginSession.state` 转 `Crashed`。
- UI 显示："应用已崩溃 [重启] [查看崩溃日志]"。
- 崩溃日志采集：`:build-runtime` 的 `PluginCrashCollector` 通过 `ActivityManager.getHistoricalProcessExitReasons()` 获取最近一次 `:pluginN` 的退出原因 + logcat tail，落盘 `filesDir/projects/{id}/crash-{ts}.log`。
- v2.0 不做崩溃符号化（crash 堆栈就是 plugin APK 里的 Kotlin stack）。

---

## 8. 老项目归档与升级路径

### 8.1 Room DB Migration

`ProjectEntity` 新增列（Room 层用 `String`，domain 层用 `enum`；`ProjectMapper` 负责转换）：

```kotlin
// data/database 层
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    // ... 其他已有字段 ...
    @ColumnInfo(name = "engine", defaultValue = "LEGACY")
    val engine: String = "LEGACY",   // "LEGACY" | "GRADLE_V2"
)

// data/model 层（domain）
enum class ProjectEngine { LEGACY, GRADLE_V2 }

data class ProjectMetadata(
    val id: String,
    val name: String,
    val engine: ProjectEngine,
    // ...
)
```

Migration `17 → 18`（具体版本号视仓库 Room 当前版本而定）：

```kotlin
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN engine TEXT NOT NULL DEFAULT 'LEGACY'")
    }
}
```

新建项目在 `ProjectManager.createProject()` 时写 `engine = "GRADLE_V2"`。

### 8.2 UI 过滤与动作

`LegacyProjectGuard`：

```kotlin
class LegacyProjectGuard @Inject constructor(...) {
    fun isLegacy(project: ProjectMetadata): Boolean = project.engine == ProjectEngine.LEGACY

    fun availableActions(project: ProjectMetadata): Set<ProjectAction> =
        if (isLegacy(project)) setOf(ProjectAction.ExportSource, ProjectAction.Delete)
        else ProjectAction.entries.toSet()
}

enum class ProjectAction {
    Chat, Build, RunInProcess, InstallApk, ExportApk,
    ExportSource, Rename, ChangeIcon, Delete,
}
```

Legacy 项目 UI 表现：

- 列表中有 "v1 已归档" badge（灰色）。
- 点击进入的详情页只显示源码目录树 + 两个按钮："导出源码 zip"、"删除项目"。
- chat 入口、所有 build/run 按钮 — 全部隐藏。

### 8.3 升级弹窗

首次启动 v2.0 时：

```text
┌────────────────────────────────────────────────────┐
│ VibeApp v2.0 架构升级                               │
│                                                    │
│ 全新构建引擎：Kotlin + Jetpack Compose + Gradle     │
│                                                    │
│ 你有 N 个使用旧引擎的项目。它们已被归档：            │
│   • 无法继续编辑 / 构建                            │
│   • 可以导出源码 zip                                │
│                                                    │
│ 新建的项目将使用 v2 引擎。                          │
│                                                    │
│ 首次构建需下载约 180MB 的构建环境。                  │
│                                                    │
│ [我知道了]                                          │
└────────────────────────────────────────────────────┘
```

DataStore flag `v2_migration_dialog_shown = true` 后不再弹。

---

## 9. 代码库变更清单

### 9.1 删除

| 路径 | 操作 |
|---|---|
| `build-engine/` （整个 Gradle module） | 删除 |
| `settings.gradle.kts` 里 `include(":build-engine")` | 删除 |
| `build-tools/javac/` | 删除 |
| `build-tools/kotlinc/` | 删除（预埋的 kotlin-compiler-embeddable 不再需要） |
| `build-tools/android-common-resources/` | 删除 |
| `build-tools/android-stubs/` | 删除 |
| `build-tools/build-logic/` | 删除 |
| `build-tools/project/` | 删除 |
| `build-tools/jaxp/`、`logging/`、`manifmerger/`、`snapshots/` | 删除 |
| `build-tools/common/` | 扫描是否被 app 引用，评估后删除 |
| `app/src/main/assets/templates/EmptyActivity/` | 删除（替换为 Kotlin+Compose 模板） |
| `app/src/main/assets/androidx-classes.jar.zip` | 删除（由 Gradle 拉真 AAR） |
| `app/src/main/kotlin/com/vibe/app/di/BuildEngineModule.kt` | 删除 |

### 9.2 新增

| 路径 | 内容 |
|---|---|
| `:build-runtime/` | 新 Gradle module，含 `src/main/cpp/` (libtermux-exec), `src/main/kotlin/` (Kotlin API) |
| `:build-gradle/` | 新 Gradle module，含 `src/main/kotlin/` (GradleBuildService), `src/main/java/` (GradleHost runner，输出 jar 打包进 bootstrap) |
| `:plugin-host/` | 新 Gradle module，含 Shadow 集成 + 代理组件 |
| `app/src/main/assets/templates/KotlinComposeApp/` | 新模板目录（见 §5） |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/` | 新 feature：bootstrap 下载 UI / 状态展示 |
| `app/src/main/kotlin/com/vibe/app/feature/project/legacy/` | 新 feature：legacy 项目只读视图 + 导出 |
| `app/src/main/kotlin/com/vibe/app/di/{BuildRuntimeModule, BuildGradleModule, PluginHostModule}.kt` | 新 Hilt module |

### 9.3 重写

| 路径                                                                           | 说明                                             |
| ---------------------------------------------------------------------------- | ---------------------------------------------- |
| `app/src/main/assets/agent-system-prompt.md`                                 | 完全重写（Kotlin+Compose+Gradle+新工具矩阵）              |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectTools.kt`        | 重写；按 §6.1 新工具矩阵重构                              |
| `app/src/main/kotlin/com/vibe/app/feature/projectinit/ProjectInitializer.kt` | 改为 `GradleProjectInitializer`：生成完整 Gradle 项目结构 |
| `app/src/main/kotlin/com/vibe/app/data/database/ProjectEntity.kt`            | 新增 `engine` 字段                                 |
| `docs/architecture.md`                                                       | 全面更新，反映新模块划分                                   |
| `docs/build-chain.md`                                                        | 全面重写，从 Javac/D8 改为 Gradle-based                |
| `docs/build-engine.md`                                                       | 删除或改标记为 "removed in v2.0"                      |
| `docs/shadow-plugin-feasibility.md`                                          | 标记为 `superseded`，指向本文档                         |

---

## 10. 分阶段开发计划

### Phase 0：Foundation（1-2 周）

**目标**：v2-arch 分支与三个新模块骨架建立。

任务拆解：

- [ ] `git checkout -b v2-arch`（从 `main` 拉）
- [ ] 新建 `:build-runtime` Gradle module（空的，可编译）
- [ ] 新建 `:build-gradle` Gradle module（空的，可编译）
- [ ] 新建 `:plugin-host` Gradle module（空的，可编译）
- [ ] `settings.gradle.kts` 加 include
- [ ] 建立三个 module 的 Hilt stub module（空 provide，让 DI 图完整）
- [ ] 写模块间依赖方向 lint 规则（app → :build-gradle, :plugin-host, :build-runtime；:build-gradle, :plugin-host → :build-runtime；无反向）
- [ ] CI pipeline 包含新模块的 assemble

**验收**：v2-arch 分支能编过且 `:app:assembleDebug` 仍能出产物（旧 build-engine 尚未删）。

### Phase 1：`:build-runtime` 可用（1-2 周）

**目标**：在手机上点一个 debug 按钮，完成 bootstrap Tier-1 下载 + 解压 + `java -version` 返回 17.0.x。

任务拆解：

- [ ] 实现 `BootstrapManifest` 数据类 + JSON 解析 + Ed25519 签名校验
- [ ] 实现 `MirrorSelector`（主/备切换）
- [ ] 实现分组件下载（HTTP Range 续传、SHA-256 校验）
- [ ] 实现 `zstd` 解压（优先系统 `libzstd.so`，fallback JNI binding）
- [ ] 实现 `RuntimeBootstrapper` 状态机（§3.4）+ DataStore 持久化
- [ ] 在 VibeApp 端打一个临时 debug 页面，显示状态、触发下载
- [ ] 抽取 `libtermux-exec.c` 源码到 `:build-runtime/src/main/cpp/`，CMake 出产物
- [ ] 实现 `ProcessLauncher` JNI 层（`fork()` + `execve()`）
- [ ] 实现 `NativeProcess` Kotlin 层 + `Flow<ProcessEvent>`
- [ ] 实现 `ProcessEnvBuilder`（PATH、LD_LIBRARY_PATH、LD_PRELOAD、JAVA_HOME、TMPDIR 等）
- [ ] 验证 `java -version` 在 arm64/armv7/x86_64 三档设备输出正确
- [ ] 验证 shebang 修正（跑一个内置的 sh 脚本）
- [ ] Instrumented 测试：下载中断、truncation、hash 不匹配各自的恢复路径

**验收**：点一个 debug 按钮，30s-2min 内完成 180MB 下载，`java -version` + `gradle --version` 都返回正确。

### Phase 2：GradleHost + 空 Compose 项目能出 APK（2-3 周）

**目标**：写死一个最小 Compose 项目源码，GradleHost 跑 `:app:assembleDebug` 产出能装的 APK。

任务拆解：

- [ ] 写 `GradleHost` Java runner（`vibeapp-gradle-host.jar`），主函数解析 stdin 请求、调 Tooling API、序列化事件到 stdout
- [ ] 把 `vibeapp-gradle-host.jar` 打包进 bootstrap manifest
- [ ] 实现 `GradleBuildService`（app 侧）：启动 / ping / invoke / cancel / shutdown
- [ ] 实现 IPC 协议序列化/反序列化（`kotlinx.serialization`）
- [ ] 在 app 代码里硬编码一个最小 Compose 项目目录（或放 test resource），触发 `:app:assembleDebug`
- [ ] 实现 `init.gradle.kts` 注入逻辑
- [ ] 实现 Gradle 启动后烟雾测试（§4.5）
- [ ] daemon 生命周期管理：idle 10min 回收
- [ ] 构建产物定位（`app/build/outputs/apk/debug/app-debug.apk`）
- [ ] 产物自动装到设备验证能启动

**验收**：手机上触发一次构建，产出的 `app-debug.apk` 装上能打开且显示 Compose UI。

### Phase 3：完整项目模板 + Maven 依赖 + Agent 工具链（2-3 周）

**目标**：用户在 Chat 输入"做一个计数器应用" → agent 新建项目、改 .kts、跑构建、装 APK。

任务拆解：

- [ ] 实现 `GradleProjectInitializer`：从 `templates/KotlinComposeApp/` 生成完整项目（§5）
- [ ] 实现模板变量替换：`{{PROJECT_NAME}}`、`{{PACKAGE_NAME}}`、`{{PACKAGE_PATH}}`、`{{BOOTSTRAP_GRADLE_DIST_PATH}}`
- [ ] 实现 `AddDependencyTool`：原子修改 `libs.versions.toml` + `app/build.gradle.kts`（失败回滚）
- [ ] 实现 `RemoveDependencyTool`
- [ ] 改造 `read/write/list_project_files` 的 path 基准
- [ ] 实现 `RunGradleTool`（调 `GradleBuildService.invoke`），含白名单校验
- [ ] 实现 `InstallApkTool`（调 `Intent.ACTION_VIEW` + `REQUEST_INSTALL_PACKAGES`）
- [ ] 实现 `ExportProjectSourceTool`（zip 打包项目 + `README.md` 说明如何外部构建）
- [ ] 端到端链路测试：Chat 新建项目 → agent 写代码 → 构建 → 装 APK

**验收**：e2e 场景"一个带按钮的计数器应用"能完整走通。

### Phase 4：构建错误回灌 Agent + 系统 prompt 重写（1-2 周）

**目标**：用户 prompt 引入一个不存在的 API → agent 构建失败 → 拿到清洗后错误 → 自动修复 → 再次构建成功。

任务拆解：

- [ ] 实现 `DiagnosticIngest`（filter / dedupe / sort / truncate）
- [ ] 实现 `DiagnosticFormatter`（Markdown 渲染 + 源码上下文）
- [ ] 实现 `GetBuildDiagnosticsTool`
- [ ] 持久化 `build-diagnostics-{buildId}.json`
- [ ] 实现 build 失败自动回灌 agent：下一轮 turn 前把 `<build-diagnostics>` 块插入用户消息
- [ ] 重写 `agent-system-prompt.md`（§6.3）
- [ ] 配置 Prompt 变量注入 pipeline（`DefaultAgentLoopCoordinator` 加载时替换）
- [ ] 覆盖测试：各类错误源（Kotlin、KSP、AAPT2、dependency resolution）的清洗正确性
- [ ] 离线回归 dataset：30 个已知失败 case 看 diagnostic 清洗质量

**验收**：故意让 agent 写错（调不存在的 API、缺依赖、typo），agent 能在 2-3 轮内修好。

### Phase 5：`:plugin-host` + Shadow 集成（2-3 周）

**目标**：构建成功后点"在 VibeApp 内运行" → 应用在 `:plugin1` 进程启动；撑满 4 槽位验证 LRU。

任务拆解：

- [ ] 集成 Shadow `dynamic.host` 库到 VibeApp 主 APK
- [ ] 在 `AndroidManifest.xml` 声明 4 组代理组件（§7.4）
- [ ] 构建 Shadow Runtime + Loader APK（从 Shadow 样例 fork 或定制）
- [ ] 把 Runtime/Loader APK 打包进 VibeApp assets
- [ ] 实现 `PluginProcessSlotManager`（LRU 分配 / 释放）
- [ ] 实现 `PluginRunner` / `PluginSession`（基于 Shadow `PluginManager`）
- [ ] 实现 `PluginCrashCollector`（`ActivityManager.getHistoricalProcessExitReasons()`）
- [ ] 在项目模板 `app/build.gradle.kts` 加 Shadow plugin apply
- [ ] 验证 `:app:assembleDebugPlugin` task 能正常产出 plugin APK
- [ ] 实现 `RunInProcessTool`
- [ ] 实现 chat bubble 三个 CTA + 浮层 UI
- [ ] e2e：触发 run_in_process → plugin APK → 启动 → 用户交互 → 停止
- [ ] 跑满 4 槽位 + 第 5 次启动的 LRU 驱逐提示
- [ ] Crash 测试：plugin 代码故意抛异常 → VibeApp 主 UI 不受影响

**验收**：至少 4 个简单 Compose 应用能并行在 VibeApp 内运行。

### Phase 6：老项目归档 + 升级弹窗 + 清理旧代码（1 周）

**目标**：装有 v1 项目的设备装 v2.0 → 首启弹窗 → 老项目只读 + 导出源码 → v1 代码全部移除。

任务拆解：

- [ ] 实现 Room Migration 17 → 18（`engine` 列）
- [ ] 首次启动时把所有 `projects/*` 扫描一遍，标记 `engine = "LEGACY"`
- [ ] 实现 `LegacyProjectGuard` 和 UI 过滤
- [ ] 实现 legacy 项目只读详情页 + 导出源码
- [ ] 实现首启 v2.0 升级弹窗 + DataStore flag
- [ ] 删除 `:build-engine` 整个模块 + settings 引用
- [ ] 删除 `build-tools/*` 中不再用的子目录（见 §9.1）
- [ ] 删除 `templates/EmptyActivity/`
- [ ] 删除 `androidx-classes.jar.zip` 与 `BuildEngineModule.kt`
- [ ] 扫描 `app/` 里对删除类的残留引用，清理
- [ ] 更新 `docs/architecture.md` / `docs/build-chain.md` / `docs/build-engine.md`
- [ ] `docs/shadow-plugin-feasibility.md` 标记 superseded

**验收**：在装有 v1 项目的设备上覆盖安装 v2.0，弹窗 + 只读视图正确；`git ls-files | grep build-engine` 无输出。

### Phase 7：体积 / 性能 / 崩溃率打磨 + 发版（2 周）

**目标**：v2.0.0 正式版质量达标。

任务拆解：

- [ ] 体积基线：APK < 100MB（除 bootstrap assets），bootstrap Tier-1 < 200MB 压缩
- [ ] 性能基线：Hello Compose 冷启动 daemon 后第一次 assembleDebug < 90s；暖构建 < 20s
- [ ] 4GB RAM 低端机真机回归（vivo / OPPO / Xiaomi 各 1 台）
- [ ] 启用 Crashlytics 或等价崩溃收集
- [ ] 接入 Firebase Performance（可选）
- [ ] 发版前 48h 冷静期内部测试
- [ ] 升级 README / 官网介绍
- [ ] GitHub Release 发布 + manifest + assets 上传
- [ ] 阿里云镜像 sync

**验收**：v2.0.0 tag，release notes 完善，GitHub Release 资产齐全，镜像可访问。

---

## 11. 风险登记

### 11.1 高风险

| #   | 风险                                                                            | 概率  | 影响         | 缓解                                                         |
| --- | ----------------------------------------------------------------------------- | --- | ---------- | ---------------------------------------------------------- |
| R1  | Android 14+/15 对 `exec from filesDir` 的 SELinux 规则进一步收紧，Termux 现有方案失效         | 中   | 方案不可用      | 跟进 termux-app 社区；必要时回退到 proot-distro；最坏情况在 APK 里预打更多 `.so` |
| R2  | Tooling API 与 Gradle 8.10 daemon 在 ART 生成的 JDK fork 上 IPC 不稳定（classloader 冲突） | 中   | Phase 2 卡壳 | Phase 2 首周做专项验证；失败则退路是自己写 `init-script` + CLI 结构化输出        |
| R3  | Shadow 官方 Gradle 插件对 AGP 8.7 + Kotlin 2.1 + Compose compiler 的组合有未知兼容问题       | 中   | Phase 5 卡壳 | Phase 5 初期做集成实验；必要时 pin 插件版本 + 补 patch；最坏情况 fork Shadow    |
| R4  | Gradle 冷启动在 4GB RAM 低端机被 LMKD 杀死                                              | 中   | 低端机不可用     | 在最低档硬件上跑基准；必要时把 daemon heap 压到 384MB、禁用 parallel workers   |

### 11.2 中风险

| #   | 风险                                              | 缓解                                                         |
| --- | ----------------------------------------------- | ---------------------------------------------------------- |
| R5  | 阿里云 / 清华镜像对 Maven 覆盖不全，少数 AAR 拉不到               | `init.gradle.kts` 里 fallback 到 Maven Central 重试；UI 透出镜像命中率 |
| R6  | 国内用户首次 180MB 下载慢 / 失败 / 放弃                      | 双镜像自动切换 + Range 续传 + Settings 里"仅 Wi-Fi 下载"选项              |
| R7  | KSP / Compose compiler 在 ARM32 真机上 bug 多于 ARM64 | Phase 7 把 ARM32 作为 best-effort；先稳 ARM64 / x86_64           |
| R8  | 用户引入含 native `.so` 的 AAR，无法跨 ABI                | v2.0 单 ABI 产物（按运行设备 ABI）；README 声明限制                       |
| R9  | Plugin APK 体积膨胀（Shadow 变换带来冗余）                  | 基准测试后评估；v2.1 再做精简                                          |

### 11.3 低风险

| #   | 风险                                   | 缓解                                 |
| --- | ------------------------------------ | ---------------------------------- |
| R10 | GPLv3 下二次分发被严查                       | 自身 GPLv3 OK；维护 NOTICE 文件           |
| R11 | `.gradle` 缓存膨胀 > 2GB                 | `ProjectCleaner` LRU + Settings 透出 |
| R12 | Bootstrap manifest 签名 private key 泄漏 | CI 环境离线签名；key 脱离 repo              |

---

## 12. Future Work

`:build-runtime` 的 `ProcessLauncher` 已为后续扩展铺好底座。v2.x 计划但 v2.0 不做：

- **Git 支持**：加 `git` 二进制到 bootstrap manifest + `GitService` 包装。
- **Logcat**：`logcat` 二进制 + stdio 流接入 + UI 视图；用户可查看 plugin 进程输出。
- **NDK / C++ 源码编译**：长期内不做。短期考虑增加 clang 二进制以支持"user 有 C 代码的库"。
- **多模块项目**：用户手动 `include(":lib")` 可工作，但 agent 默认不生成 `:lib`。
- **Kotlin incremental 本地加速**：依赖 Gradle 原生；v2.x 视瓶颈而定。
- **Per-project keystore**：替代默认 debug signing（需要 UI 管理密钥）。
- **自定义 Gradle plugin 开发**：长期不做。
- **Play Store 合规分发**：长期不做。

---

## 13. 开放问题

以下问题 v2.0 暂定默认值，后续可迭代：

| # | 问题 | v2.0 默认 |
|---|---|---|
| Q-A | Gradle daemon idle timeout 是否允许用户配置 | 固定 10min；不暴露 |
| Q-B | 是否默认启用 Gradle configuration cache | 否（先关；Phase 7 基准后再评估） |
| Q-C | AGP namespace 与 applicationId 用户能否改 | 不允许改；模板生成时固定 |
| Q-D | 生成 APK 用 debug signing 还是 per-project keystore | debug signing；per-project 放 v2.1 |
| Q-E | 用户在 `build.gradle.kts` 里 apply 三方 plugin（如 Hilt） | 允许但 agent 不主动用；用户自担风险 |
| Q-F | `run_in_process` 失败时是否自动降级到"安装到设备" | 否；用户手动选择 |

---

## 14. 术语表

| 术语                       | 含义                                                                     |
| ------------------------ | ---------------------------------------------------------------------- |
| **Bootstrap**            | VibeApp 首次构建前需要下载到 `filesDir/usr/` 的 JDK / Gradle / Android SDK 等工具链资源 |
| **GradleHost**           | 运行在 bootstrap JDK 中的 Java 子进程，承载 Tooling API Client                    |
| **Gradle Daemon**        | Gradle 官方常驻守护进程；每个项目一组                                                 |
| **NativeProcess**        | `:build-runtime` 提供的端上 native 进程抽象                                     |
| **Plugin APK**           | 通过 Shadow 官方 Gradle 插件变换后的 APK；可在 VibeApp 进程槽位内运行                      |
| **Plugin Session**       | 一次进程内运行的生命周期对象                                                         |
| **Process Slot**         | VibeApp manifest 静态声明的 `:pluginN` 进程位；v2.0 共 4 个                       |
| **Legacy Project**       | v1.x 创建的 Java+XML 项目；v2.0 中只读归档                                        |
| **Tier-0/1/2 Bootstrap** | Bootstrap 资源的分层；Tier-0 随 APK、Tier-1 首次构建下载、Tier-2 v2.0 不做              |

---

*本文档为 v2.0 架构升级的 authoritative design spec。实现中任何与本文档冲突的决定，必须先在此更新。*
