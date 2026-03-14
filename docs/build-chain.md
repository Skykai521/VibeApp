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
    ┌─────────▼───────────┐
    │    2. AAPT2         │  ~1-2s
    │ res → R.java + ap_  │
    └─────────┬──────────┘
              │
    ┌─────────▼──────────┐
    │    3. JavacTool    │  ~2-5s
    │ .java → .class     │
    └─────────┬───────────┘
              │
    ┌─────────▼───────────┐
    │    4. D8            │  ~1-3s
    │    .class → .dex    │
    └─────────┬───────────┘
              │
    ┌─────────▼───────────┐
    │    5. Package       │  < 1s
    │ generated.apk       │
    └─────────┬───────────┘
              │
    ┌─────────▼───────────┐
    │    6. ApkSigner     │  < 1s
    │    signed.apk       │
    └─────────────────────┘
```

典型总耗时：5-12 秒（视设备性能和代码量）

## 各组件详解

### 1. PreCheck — 代码预检

**目的**：在编译前快速拦截明显不合法的代码，避免浪费编译时间。

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

### 2. AAPT2 — 资源编译

`build-engine` 当前的资源阶段由

- `Aapt2ResourceCompiler`

负责，顺序是：

1. 编译 `src/main/res/`
2. 链接 `AndroidManifest.xml`
3. 生成 `build/gen/R.java`
4. 生成 `build/bin/generated.apk.res`
5. 生成 `build/bin/res/R.txt`

这一步必须在 Java 编译之前完成，因为业务源码通常会引用 `R`。

### 3. JavacTool — Java 编译

**选型原因**：
- 纯 Java 实现（不依赖 native 代码）
- 可以作为 JAR 直接嵌入 Android App
- 支持增量编译

**平台类库来源**：
- 当前实现通过 `BuildModule` 提供 `rt.jar`
- `rt.jar` 和 `lambda-stubs.jar` 都从 `build-engine/src/main/assets/` 解压到应用私有目录
- 它们分别用于 Android API stub 和 Java 8 lambda 兼容支持

`build-engine` 当前使用：

- `JavacCompiler`

编译业务源码和上一步生成的 `R.java`，输出目录为：

- `build/bin/java/classes`

### 4. D8 — DEX 转换

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

当前实现类：

- `D8DexConverter`

输入：

- `build/bin/java/classes/**/*.class`

输出：

- `build/bin/classes.dex`

### 5. APK 打包

当前实现类：

- `AndroidApkBuilder`

输入：

- `build/bin/generated.apk.res`
- `build/bin/classes.dex`

输出：

- `build/bin/generated.apk`

### 6. APK 签名

当前实现类：

- `DebugApkSigner`

默认使用内置测试签名：

- `testkey.pk8.zip`
- `testkey.x509.pem.zip`

### AAPT2 运行方式

AAPT2 仍然是 native binary（C++ 实现），不是纯 Java。

当前方案：

- 预编译各 ABI 的 AAPT2 二进制
- 打包在 `jniLibs/`
- 通过 `Aapt2Jni` 调用

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

签名实现走 `apksig`（纯 Java 实现）：

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
- 默认使用 `testkey.pk8.zip + testkey.x509.pem.zip`
- 也可以通过 `CompileInput.signingConfig` 传入自定义 `pk8/pem` 路径

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
- 当前 `build-engine` 以稳定的全量构建为主
- 增量能力主要仍保留在 `build-tools/build-logic` 旧任务体系中

### 内存管理
- JavacTool、D8、apksig 都以内嵌方式运行
- AAPT2 通过 JNI/native binary 调用

### 首次运行优化
- `rt.jar` 与 `lambda-stubs` 从 assets 解压到应用私有目录
- AAPT2 从 `jniLibs` 加载

## 已知限制

1. **不支持 Kotlin**（Phase 1）— kotlinc 需要额外的编译器集成
2. **不支持第三方库**（Phase 1）— 需要预编译 DEX 机制
3. **不支持 Jetpack Compose**（Phase 1）— 需要 Kotlin 编译器插件
4. **资源限制** — 复杂项目可能受手机内存限制
5. **编译速度** — 比 PC 上的 Gradle 构建慢，但对于单 Activity 项目可接受
