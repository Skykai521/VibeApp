# 编译链原理 | Build Chain

## 概述

VibeApp 的核心技术挑战是在 Android 设备上完成完整的 APK 编译流程。这通常需要一台装有 Android SDK 和 JDK 的电脑，但通过精心选择纯 Java 实现的编译工具，我们可以在手机上完成全部步骤。

## 编译流水线

```
    Source Code (.java + .xml)
              │
    ┌─────────▼───────────┐
    │    1. PreCheck      │  < 0.1s
    │    黑白名单扫描       │
    └─────────┬───────────┘
              │
    ┌─────────▼──────────┐
    │    2. JavacTool    │  ~2-5s
    │    .java → .class  │
    └─────────┬──────────┘
              │
    ┌─────────▼───────────┐
    │    3. D8            │  ~1-3s
    │    .class → .dex    │
    └─────────┬───────────┘
              │
    ┌─────────▼───────────┐
    │    4. AAPT2         │  ~1-2s
    │    资源编译 + 打包    │
    └─────────┬───────────┘
              │
    ┌─────────▼───────────┐
    │    5. ApkSigner     │  < 1s
    │    V1 + V2 签名      │
    └─────────┬───────────┘
              │
    ┌─────────▼───────────┐
    │   6. Install        │
    │   PackageInstaller  │
    └─────────────────────┘
```

典型总耗时：5-12 秒（视设备性能和代码量）

## 各组件详解

### 1. PreCheck — 代码预检

**目的**：在编译前快速拦截明显不合法的代码，避免浪费编译时间。

**白名单机制**：
- 定义允许使用的 Android SDK 类和方法
- 例如：`android.widget.TextView`, `android.view.View`, `android.os.Bundle`
- 存储在 `assets/prompts/whitelist.json`

**黑名单机制**：
- 禁止使用第三方库（Phase 1）
- 禁止危险 API（如 `Runtime.exec()`, 反射等）
- 禁止网络请求相关类（Phase 1 限制）

**实现方式**：
```kotlin
class PreChecker(
    private val whitelist: Set<String>,
    private val blacklist: Set<String>
) {
    fun check(sourceCode: String): PreCheckResult {
        val imports = extractImports(sourceCode)
        val violations = imports.filter { it in blacklist || it !in whitelist }
        return if (violations.isEmpty()) PreCheckResult.Pass
               else PreCheckResult.Fail(violations)
    }
}
```

### 2. JavacTool — Java 编译

**选型原因**：
- 纯 Java 实现（不依赖 native 代码）
- 可以作为 JAR 直接嵌入 Android App
- 支持增量编译

**android.jar 的来源**：
- 从 Android SDK 提取的 `android.jar`（仅含 API stub，不含实现）
- 打包在 App 的 assets 中，首次运行解压到内部存储
- 约 30-40MB（根据 targetSdk 版本）

### 3. D8 — DEX 转换

**选型原因**：
- Google 官方 DEX 编译器
- 取代了旧的 dx 工具
- 纯 Java 实现
- 支持 Java 8 语法脱糖（desugaring）

**调用方式**：
```kotlin
// D8 接受 .class 文件，输出 .dex
D8Command.builder()
    .addProgramFiles(classFiles)
    .setOutput(outputDir, OutputMode.DexIndexed)
    .setMinApiLevel(26)
    .build()
    .let { D8.run(it) }
```

### 4. AAPT2 — 资源编译

**特殊性**：AAPT2 是 native binary（C++ 实现），不是纯 Java。

**解决方案**：
- 预编译各 ABI 的 AAPT2 二进制文件
- 打包在 `jniLibs/` 目录
- 运行时通过 `ProcessBuilder` 调用

**支持的 ABI**：
| ABI | 覆盖设备 |
|-----|---------|
| arm64-v8a | 绝大多数现代手机 |
| armeabi-v7a | 旧款手机 |
| x86_64 | 模拟器 / ChromeOS |

**AAPT2 两步流程**：
```bash
# Step 1: 编译资源文件为 .flat
aapt2 compile -o compiled_res/ res/layout/activity_main.xml

# Step 2: 链接资源 + 生成 R.java + 打包
aapt2 link -o output.apk \
    -I android.jar \
    --manifest AndroidManifest.xml \
    compiled_res/*.flat
```

### 5. APK 签名

使用 `apksigner`（纯 Java 实现）：

```kotlin
// V1 (JAR signing) + V2 (APK Signature Scheme v2)
val signerConfig = ApkSigner.SignerConfig.Builder(
    "VibeApp",
    privateKey,
    listOf(certificate)
).build()

ApkSigner.Builder(listOf(signerConfig))
    .setInputApk(unsignedApk)
    .setOutputApk(signedApk)
    .setV1SigningEnabled(true)
    .setV2SigningEnabled(true)
    .build()
    .sign()
```

**签名密钥管理**：
- 默认使用 VibeApp 内置的 debug keystore
- 用户可在设置中导入自己的 keystore
- 密钥安全存储在 Android Keystore 中

## 自动修复循环

编译失败时，进入自动修复流程：

```
编译失败
    │
    ▼
错误日志清洗
    │  - 去除绝对路径
    │  - 提取行号 + 错误类型
    │  - 聚合同类错误
    │  - 限制总长度（避免 token 浪费）
    ▼
构造修复 Prompt
    │  - 原始代码 + 清洗后的错误信息
    │  - 明确指出需要修复的文件和行号
    ▼
AI 生成修复代码
    │
    ▼
重新编译
    │
    ├── 成功 → 继续后续流程
    └── 失败 → 重试（最多 3 次）
              └── 3 次均失败 → 报告用户，展示错误详情
```

## 性能优化策略

### 编译缓存
- 未修改的 .class 文件不重新编译（基于文件 hash）
- 资源文件增量编译（AAPT2 支持）

### 内存管理
- ECJ 编译在独立进程中运行（避免 OOM 影响主 App）
- D8 使用流式处理，避免一次性加载所有 .class

### 首次运行优化
- android.jar 异步解压，显示进度条
- AAPT2 binary 权限设置（chmod +x）在安装时完成

## 已知限制

1. **不支持 Kotlin**（Phase 1）— kotlinc 需要额外的编译器集成
2. **不支持第三方库**（Phase 1）— 需要预编译 DEX 机制
3. **不支持 Jetpack Compose**（Phase 1）— 需要 Kotlin 编译器插件
4. **资源限制** — 复杂项目可能受手机内存限制
5. **编译速度** — 比 PC 上的 Gradle 构建慢，但对于单 Activity 项目可接受
