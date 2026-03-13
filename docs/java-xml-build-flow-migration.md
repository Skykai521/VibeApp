# CodeAssist 编译链迁移说明

## 目标

本文基于当前仓库源码，说明如果你要把这里的 `JDT/Java 编译 + AAPT2 + D8 + APK 打包/签名` 迁移到另一个工程里，应该迁移哪些 module，它们各自负责什么，以及在“只支持 Java 编译 + XML 布局文件编译、不支持 Kotlin”这个前提下，哪些部分可以直接裁掉。

## 先说结论

这个仓库里真正串起 APK 构建流程的主入口是：

- `build-tools/build-logic/src/main/java/com/tyron/builder/compiler/AndroidAppBuilder.java`

它的任务顺序大致是：

1. `CleanTask`
2. `CheckLibrariesTask`
3. `ManifestMergeTask`
4. `IncrementalAapt2Task`
5. `GenerateViewBindingTask`
6. `MergeSymbolsTask`
7. `IncrementalKotlinCompiler`
8. `IncrementalJavaTask`
9. `IncrementalD8Task` 或 `R8Task`
10. `PackageTask`
11. `ZipAlignTask`
12. `SignTask`

但是有一个非常关键的事实：

- 当前源码里 Java 主编译任务 `IncrementalJavaTask` 实际走的是 `JavacTool`，不是 Eclipse JDT。
- 仓库里确实带了 JDT/ECJ 相关能力，主要在 `build-tools:eclipse-standalone` 和 `build-tools/build-logic/libs/ecj.jar`，但它们不是当前 APK 主编译链的默认入口。

所以迁移时要分成两种理解：

- 如果你要“按当前源码的真实主链迁移”，那应该迁的是 `JavacTool + AAPT2 + D8 + 打包/签名`。
- 如果你明确要求新工程必须保留 Eclipse JDT/ECJ，那么你需要额外迁入 JDT 相关 module，并自己把 Java 编译任务改造成 ECJ/JDT 版本。

## 针对你这个目标工程的推荐最小链路

因为你的目标工程只需要：

- Java 编译
- XML 布局文件编译
- 不需要 Kotlin

所以推荐保留的最小任务链是：

1. `CleanTask`
2. `CheckLibrariesTask`
3. `ManifestMergeTask`
4. `IncrementalAapt2Task`
5. `MergeSymbolsTask`
6. `IncrementalJavaTask` 或你自己替换成 `EcjJavaTask`
7. `IncrementalD8Task`
8. `PackageTask`
9. `ZipAlignTask` 可选
10. `SignTask`

建议直接删除的任务：

- `IncrementalKotlinCompiler`
- `GenerateViewBindingTask`
- `GenerateFirebaseConfigTask`
- `CrashlyticsTask`
- `InjectLoggerTask`
- `R8Task`（如果你不需要混淆压缩，只保留 D8 即可）

## 必迁 module

下面是按“尽量少改代码、尽量复用现有实现”的角度给出的迁移清单。

| Module | 是否建议迁移 | 作用 | 说明 |
| --- | --- | --- | --- |
| `build-tools:build-logic` | 必迁，但建议裁剪后迁 | 主编排层和工具适配层 | APK 构建任务、AAPT2 适配、D8 任务、打包、签名、清理逻辑都在这里 |
| `build-tools:project` | 必迁 | 项目/模块模型 | `AndroidModule`、`JavaModule`、缓存、源码/资源/库目录约定都在这里，绝大多数任务直接依赖这些接口 |
| `build-tools:logging` | 必迁 | 编译日志和诊断抽象 | `ILogger`、`DiagnosticWrapper` 被 Java/AAPT2/D8/签名任务统一使用 |
| `build-tools:manifmerger` | 必迁 | Manifest 合并 | `ManifestMergeTask` 直接依赖它输出合并后的 `AndroidManifest.xml` |
| `common` | 必迁 | 通用工具 | `BinaryExecutor`、`Decompress`、`Cache`、字符串/文件工具被多个构建任务依赖 |
| `build-tools:javac` | 推荐迁移 | 当前 Java 主编译实现依赖 | 如果你复用 `IncrementalJavaTask`，这个 module 基本要一起带走 |

## 条件必迁 module

这些 module 不是“你一定要迁”，而是取决于你要不要保持源码现状不动。

| Module | 什么时候要迁 | 作用 | 说明 |
| --- | --- | --- | --- |
| `build-tools:eclipse-standalone` | 你真的要保留 JDT/ECJ 时 | 提供 Eclipse JDT Core 和平台依赖 | 当前主编译链并不直接用它，但如果你要自己做 `EcjJavaTask`，它是最直接的内部来源 |
| `build-tools:jaxp:xml` | 基本要迁 | XML/JAXP 兼容层 | `build-tools:manifmerger` 和 `build-tools:build-logic` 都依赖它 |
| `build-tools:jaxp:jaxp-internal` | 基本要迁 | JAXP 内部实现补齐 | 同上，和 manifest/xml 解析链一起工作 |
| `build-tools:kotlinc` | 只在你保留现有源码结构时需要 | 提供 Kotlin/IntelliJ 打包类 | 虽然你不编 Kotlin 源码，但 `build-tools:project` 里有 `org.jetbrains.kotlin.com.intellij.*` 依赖，`build-tools:build-logic` 里也有 Kotlin 源文件和 `kotlin.io.FilesKt` 调用；不改源码就得带上它 |

## 不建议迁移的 module

在你的目标场景下，下面这些 module 可以不迁，或者迁了也应该尽量删掉引用：

- `kotlin-completion`
- `build-tools:viewbinding-lib`
- `build-tools:viewbinding-inject`
- `build-tools:xml-repository`
- `build-tools:codeassist-builder-plugin`
- `build-tools:lint`
- 所有 `build-tools:builder-*`
- `java-completion`
- `xml-completion`
- `layout-preview`
- `dependency-resolver`

原因很简单：

- 它们属于 IDE、补全、预览、Gradle/AGP 仿真、ViewBinding、Lint 体系。
- 它们不是 `Java/XML -> class/R.java -> dex -> apk` 这条最小构建链的核心闭包。

## 建议你从 `build-tools:build-logic` 里实际迁出的类

不要整包照搬 `AndroidAppBuilder`，而是建议迁出一个“精简版构建器”。

### 编排骨架

- `com.tyron.builder.compiler.Builder`
- `com.tyron.builder.compiler.BuilderImpl`
- `com.tyron.builder.compiler.Task`
- `com.tyron.builder.compiler.BuildType`
- `com.tyron.builder.BuildModule`

### Java 编译

- `com.tyron.builder.compiler.incremental.java.IncrementalJavaTask`

如果你坚持用 JDT/ECJ，则把这一块换成你自己的 `EcjJavaTask`，不要继续用 `IncrementalJavaTask`。

### 资源编译

- `com.tyron.builder.compiler.manifest.ManifestMergeTask`
- `com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task`
- `com.tyron.builder.compiler.resource.AAPT2Compiler`
- `com.android.tools.aapt2.Aapt2Jni`
- `com.tyron.builder.compiler.symbol.MergeSymbolsTask`
- `com.tyron.builder.compiler.symbol.SymbolLoader`
- `com.tyron.builder.compiler.symbol.SymbolWriter`

### DEX 转换

- `com.tyron.builder.compiler.dex.DexDiagnosticHandler`
- `com.tyron.builder.compiler.incremental.dex.IncrementalD8Task`

如果新工程只是普通 Java 工程而不是 Android Module，也可以参考：

- `com.tyron.builder.compiler.dex.JavaD8Task`

### 打包与签名

- `com.tyron.builder.compiler.apk.PackageTask`
- `com.tyron.builder.compiler.apk.ZipAlignTask`
- `com.tyron.builder.compiler.apk.SignTask`
- `com.tyron.builder.compiler.ApkSigner`

### 依赖和清理

- `com.tyron.builder.compiler.CleanTask`
- `com.tyron.builder.compiler.java.CheckLibrariesTask`

## 每个核心 module 的职责

### `build-tools:build-logic`

它是整个迁移的核心。

你真正要的东西基本都在这里：

- 构建任务抽象：`Task`、`BuilderImpl`
- Java 编译任务
- AAPT2 编译/链接
- `R.txt -> R.java`
- D8 dexing
- APK 打包
- zipalign
- APK 签名

但是它现在掺了很多你不需要的东西：

- Kotlin 增量编译
- ViewBinding
- Firebase/Crashlytics
- Debug 注入

所以这个 module 应该“裁剪后迁移”，不要直接整体复用。

### `build-tools:project`

这个 module 不是业务功能，而是“构建任务运行所依赖的数据模型”。

它提供的核心能力是：

- `JavaModule` / `AndroidModule` 抽象
- 源码目录、资源目录、manifest、assets、jniLibs、build 目录定位
- 库列表和库索引
- 任务级缓存
- 模块配置读取（`app_config.json`）

如果不迁它，你就得自己重写一套兼容 `Task` 的 module API。

### `build-tools:logging`

它把不同工具链的输出统一成：

- `ILogger`
- `DiagnosticWrapper`

这能让 `Javac`、`AAPT2`、`D8`、`zipalign` 用同一套日志接口输出错误和警告。

### `build-tools:manifmerger`

即使你只需要 XML 布局编译，这个 module 也仍然很重要，因为：

- `AAPT2 link` 需要 manifest
- 包名、`minSdk`、`targetSdk`、版本号都要从 manifest/配置进入资源链接阶段

### `build-tools:javac`

如果你按当前源码复用 Java 编译，这个 module 要迁。

它不是单纯的 JDK `javac` 依赖声明，而是带了一些兼容文件系统和编译器访问的补丁类。`IncrementalJavaTask` 虽然直接调用 `JavacTool`，但整个 module 设计就是围绕这套 Java 编译路径来的。

### `build-tools:eclipse-standalone`

只有在你真的要保留 JDT/ECJ 时才建议迁。

它的作用不是“当前主编译链必须依赖它”，而是：

- 提供 `org.eclipse.jdt.core`
- 带上 Eclipse runtime/filesystem/text 等平台依赖
- 为你自己实现 ECJ/JDT 编译任务提供现成运行时

换句话说，它更像“JDT 能力包”，不是当前 APK 主链的必需包。

## 你还必须一起带走的运行时资源

这部分不是 Gradle module，但不带走的话，构建链跑不起来。

### AAPT2 / zipalign 二进制

当前仓库把它们放在：

- `app/src/main/jniLibs/*/libaapt2.so`
- `app/src/main/jniLibs/*/libzipalign.so`

`Aapt2Jni` 和 `ZipAlignTask` 会通过 Android `Context` 的 `nativeLibraryDir` 去找它们。

### Android 编译基础包

当前仓库通过 `BuildModule` 从 assets 解压：

- `app/src/main/assets/rt.zip`
- `app/src/main/assets/lambda-stubs.zip`

它们分别用于：

- 提供 `android.jar` / bootstrap classes
- 提供 lambda stubs

### 测试签名证书

当前仓库通过 `BuildModule` + `Decompress` 从 assets 解压：

- `app/src/main/assets/testkey.pk8.zip`
- `app/src/main/assets/testkey.x509.pem.zip`

### 签名器 jar

仓库里还有：

- `build-tools/build-logic/libs/apksigner.jar`

但要注意，当前 `ApkSigner` 类看起来只是在组装命令参数，并没有真正调用 `apksigner` 执行签名；也就是说，你迁移这部分时，最好顺手把它补成真正可执行的实现，而不要原样照抄后默认它已经完整可用。

## 如果你坚持保留 Eclipse JDT，应该怎么做

如果你新工程明确要的是 `Eclipse JDT + D8 + AAPT2 + ApkSigner`，那建议这样处理：

1. 仍然迁移上面列出的构建主链 module。
2. 额外迁移 `build-tools:eclipse-standalone`。
3. 把 `IncrementalJavaTask` 替换成你自己的 `EcjJavaTask`。
4. `EcjJavaTask` 的输入输出接口尽量保持和现有任务一致：
   - 输入：`getJavaFiles()`、`getResourceClasses()`、`getLibraries()`、`getBootstrapJarFile()`、`getLambdaStubsJarFile()`
   - 输出：`build/bin/java/classes`
5. 后续 `IncrementalD8Task`、`PackageTask`、`SignTask` 就可以不改，继续吃这套 class 输出。

这样做的好处是：

- JDT 只替换 Java 编译步骤
- AAPT2、R.java、D8、打包、签名可以继续复用现成逻辑

这样做的代价是：

- 你要自己补一层 ECJ/JDT 编译 task
- 不能直接把当前 `IncrementalJavaTask` 当成“JDT 版本”

## 对你的目标工程，最稳妥的迁移方案

如果你的目标只是“另一个工程能完成 Java + XML 布局编译，并最终产出 APK”，我建议采用下面这个版本，而不是追求把仓库里所有相关 module 一次搬空。

### 方案 A：最贴近当前源码，改动最少

迁移：

- `build-tools:build-logic`
- `build-tools:project`
- `build-tools:logging`
- `build-tools:manifmerger`
- `build-tools:javac`
- `common`
- `build-tools:jaxp:xml`
- `build-tools:jaxp:jaxp-internal`

然后裁掉：

- Kotlin 任务
- ViewBinding
- Firebase/Crashlytics
- XML repository

优点：

- 复用率最高
- D8/AAPT2/打包/清理逻辑基本不用重写

缺点：

- 仍然会带一些历史包袱
- `build-tools:project` 对 Kotlin/IntelliJ 打包类有耦合

### 方案 B：你真的要 JDT

在方案 A 基础上，再加：

- `build-tools:eclipse-standalone`

然后自己实现：

- `EcjJavaTask`

并替换：

- `IncrementalJavaTask`

优点：

- 满足你“必须 JDT”这个目标

缺点：

- 这一步不是直接复用当前主链，而是“在当前主链上替换 Java 编译器”

## 最后给你的迁移建议

如果你问的是“应该迁哪些 module”，我的最终建议是：

### 必迁

- `build-tools:build-logic`
- `build-tools:project`
- `build-tools:logging`
- `build-tools:manifmerger`
- `common`

### 大概率要迁

- `build-tools:javac`
- `build-tools:jaxp:xml`
- `build-tools:jaxp:jaxp-internal`

### 只有在你强制要求 JDT 时才迁

- `build-tools:eclipse-standalone`

### 尽量不要迁

- `build-tools:kotlinc`：除非你先不改源码结构，只是为了让现有 `project/build-logic` 编过去
- `build-tools:viewbinding-lib`
- `build-tools:viewbinding-inject`
- `build-tools:xml-repository`
- 所有 completion / preview / lint / builder-* / AGP 模拟相关 module

## 一句话总结

如果你要的是“能落地的最小迁移”，不要把这件事理解成“迁整个 CodeAssist 的构建系统”，而应该理解成：

- 从 `build-tools:build-logic` 抽出 Java/AAPT2/D8/APK 核心任务
- 用 `build-tools:project` 提供模块模型
- 用 `build-tools:manifmerger + jaxp` 解决 manifest 和 XML
- 用 `common + logging` 补足通用能力
- 如果必须是 JDT，再单独把 `build-tools:eclipse-standalone` 接入到新的 Java 编译任务里

