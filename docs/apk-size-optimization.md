# APK 包大小优化分析报告

> 当前 release APK 大小：**119.9 MB**（解压后 127.87 MB）
>
> 分析日期：2026-04-04

## 当前包体积构成

| 类别 | 大小 (MB) | 占比 | 说明 |
|------|-----------|------|------|
| **DEX 文件** | 92.69 | 72.5% | 8 个 DEX 文件，未开启 R8 混淆/压缩 |
| **Native 库 (.so)** | 15.34 | 12.0% | libaapt2.so 为主，包含 5 种 ABI 架构 |
| **Assets** | 13.85 | 10.8% | rt.zip、androidx-classes.jar.zip 等构建引擎运行时 |
| **Resources** | 2.21 | 1.7% | resources.arsc + res/ |
| **其他** | 3.78 | 3.0% | javac 编译器资源、XML Schema 等 |

### DEX 文件明细（92.69 MB）

| 文件 | 大小 (MB) |
|------|-----------|
| classes.dex | 28.53 |
| classes2.dex | 12.20 |
| classes5.dex | 10.24 |
| classes6.dex | 9.74 |
| classes7.dex | 8.82 |
| classes8.dex | 8.56 |
| classes4.dex | 7.42 |
| classes3.dex | 7.17 |

### Native 库明细（15.34 MB）

| 架构 | 大小 (MB) | 包含库 |
|------|-----------|--------|
| arm64-v8a | 8.11 | libaapt2.so (7.97) + libzipalign.so (0.11) + 其他 |
| armeabi | 7.18 | libaapt2.so (7.09) + libzipalign.so (0.09) |
| x86_64 | 0.02 | libandroidx.graphics.path.so + libdatastore |
| x86 | 0.02 | libandroidx.graphics.path.so + libdatastore |
| armeabi-v7a | 0.01 | libandroidx.graphics.path.so + libdatastore |

### Assets 明细（13.85 MB）

| 文件 | 大小 (MB) | 用途 |
|------|-----------|------|
| rt.zip | 7.21 | Java Runtime 类库（构建引擎编译用） |
| androidx-classes.jar.zip | 4.70 | AndroidX 编译类（构建引擎编译用） |
| androidx-res-compiled.zip | 1.26 | AndroidX 编译资源 |
| jsoup.jar.zip | 0.40 | Jsoup HTML 解析库（插件运行时用） |
| shadow-runtime.jar | 0.01 | Shadow 插件运行时 |
| 其他（模板、prompt 等） | 0.27 | 项目模板 + agent prompt |

---

## 优化方案

### 方案 1：开启 R8 混淆和代码压缩

**预期收益：25-35 MB（DEX 从 92 MB 降至约 55-65 MB）**
**实现复杂度：中高**

当前 `app/build.gradle.kts` 中 `isMinifyEnabled = false`。开启后 R8 会：
- 移除未使用的类和方法（Tree Shaking）
- 混淆类名/方法名（缩短标识符）
- 优化字节码

主要工作量在于编写和调试 ProGuard keep 规则：
- build-engine 模块通过反射调用 javac 编译器，相关类必须 keep
- Ktor 网络库使用 Kotlin Serialization，需要 keep 序列化相关类
- Room/Hilt 已有部分规则，但可能需要补充
- Shadow 插件运行时涉及反射加载，需要精细配置

**风险**：不当的 keep 规则可能导致运行时 crash，特别是 build-engine 中大量使用 `javax.tools.*` 和 `com.sun.tools.javac.*` 反射调用。需要充分测试编译和插件加载流程。

**预计工作量**：2-3 天（含测试）

---

### 方案 2：移除不需要的 ABI 架构

**预期收益：7-8 MB**
**实现复杂度：低**

当前包含 5 种 ABI：`arm64-v8a`、`armeabi`、`x86_64`、`x86`、`armeabi-v7a`。

- `armeabi`（32 位旧架构）：可以考虑移除，但 libaapt2.so 只有 arm64-v8a 和 armeabi 两个版本。如果目标用户设备均为 64 位，可只保留 arm64-v8a。
- `x86` / `x86_64`：主要用于模拟器，生产用户不需要。但 libaapt2 未提供 x86 版本，这些架构下只有极小的 AndroidX 库（0.02 MB），影响可忽略。
- `armeabi-v7a`：同上，极小。

**推荐方案**：在 `build.gradle.kts` 中添加 ABI 过滤，仅保留 `arm64-v8a` + `armeabi`（兼容 32 位设备），或仅 `arm64-v8a`（放弃 32 位）。

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a", "armeabi")
}
```

仅保留 `arm64-v8a` 可省约 **7.2 MB**（移除 armeabi 的 libaapt2.so + libzipalign.so）。

**风险**：低。绝大多数现代 Android 设备为 arm64-v8a。32 位设备市场份额已极低（<5%）。

**预计工作量**：0.5 天

---

### 方案 3：开启资源压缩（Resource Shrinking）

**预期收益：0.5-1 MB**
**实现复杂度：低**

当前 `isShrinkResources = false`。开启后 R8 会移除未引用的资源文件。

注意：此选项依赖 `isMinifyEnabled = true`，需要与方案 1 配合使用。

**风险**：构建引擎模板中引用的资源可能被误删。需要在 `res/raw/keep.xml` 中声明保留规则。

**预计工作量**：与方案 1 一起实施，额外 0.5 天

---

### 方案 4：压缩 Assets 中的构建引擎运行时

**预期收益：2-4 MB**
**实现复杂度：中**

当前 `rt.zip`（7.21 MB）和 `androidx-classes.jar.zip`（4.70 MB）是构建引擎的核心依赖。可以优化：

**4a. 精简 rt.zip（Java Runtime）**
- 当前包含完整 Java RT 类库
- 生成的应用只使用部分 Java 标准库（java.lang、java.util、java.io 等）
- 可通过 `jlink` 或手动裁剪移除不需要的模块（如 java.sql、java.rmi、javax.swing 等）
- 预期可减少 3-4 MB

**4b. 精简 androidx-classes.jar.zip**
- 当前包含完整 AndroidX + Material Components 编译类
- 可分析实际生成的应用中使用的 AndroidX 类，移除未使用的模块
- 预期可减少 1-2 MB

**风险**：中等。裁剪过度会导致生成的应用编译失败。需要维护一个"需要保留的类列表"并充分测试各种生成场景。

**预计工作量**：3-5 天

---

### 方案 5：使用 App Bundle (AAB) 替代 APK 分发

**预期收益：用户下载大小减少 30-40%（约 40-50 MB）**
**实现复杂度：低**

通过 Google Play 的 App Bundle 分发，每个设备只下载所需的资源和 Native 库：
- 只下载设备对应的 ABI（如 arm64-v8a）
- 只下载设备对应的屏幕密度资源
- 只下载设备对应的语言资源

**注意**：这不减小 APK/AAB 本身的大小，而是减小用户实际下载的大小。如果通过 Google Play 分发，这是最简单的优化。

**风险**：极低。这是 Google 推荐的标准分发方式。

**预计工作量**：0.5 天（改用 `./gradlew bundleRelease`）

---

### 方案 6：移除 DEX 中的 javac 编译器冗余资源

**预期收益：1-2 MB**
**实现复杂度：低**

APK 中包含大量 `com/sun/tools/javac/resources/compiler_*.properties` 和 `XMLSchemaMessages_*.properties` 多语言翻译文件。这些是 javac 编译器内置的错误消息翻译，对用户不可见。

可通过 ProGuard/R8 规则或 `packagingOptions` 排除这些文件：

```kotlin
packaging {
    resources {
        excludes += setOf(
            "com/sun/tools/javac/resources/compiler_ja.properties",
            "com/sun/tools/javac/resources/compiler_zh_CN.properties",
            "XMLSchemaMessages_*.properties",
            "com/android/sdklib/devices/nexus.xml",
            "migration.xml",
        )
    }
}
```

**风险**：低。这些文件仅影响编译器错误消息的本地化显示，不影响编译功能。

**预计工作量**：0.5 天

---

### 方案 7：Native 库压缩优化

**预期收益：3-5 MB**
**实现复杂度：低**

当前 `useLegacyPackaging = true`，这意味着 Native .so 文件以未压缩方式存储在 APK 中。改为 `false` 后，AGP 会压缩 .so 文件（但运行时需要解压，会增加首次启动时间和磁盘占用）。

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = false
    }
}
```

**注意**：libaapt2.so 和 libzipalign.so 是在运行时从 APK 中加载的。如果代码通过 `context.applicationInfo.nativeLibraryDir` 访问它们，`useLegacyPackaging = false` 是安全的（Android 会自动解压到 nativeLibraryDir）。如果代码直接从 APK 中读取 .so 文件，则会失败。

**风险**：中低。需要验证 AAPT2 和 zipalign 的加载方式。

**预计工作量**：0.5 天（含测试）

---

## 优化方案总览

| 方案 | 预期收益 | 复杂度 | 风险 | 优先级 |
|------|----------|--------|------|--------|
| 1. 开启 R8 混淆压缩 | 25-35 MB | 中高 | 中 | P0 |
| 2. 移除不需要的 ABI | 7-8 MB | 低 | 低 | P0 |
| 5. App Bundle 分发 | 下载减 40-50 MB | 低 | 极低 | P0 |
| 7. Native 库压缩 | 3-5 MB | 低 | 中低 | P1 |
| 4. 精简 Assets 运行时 | 2-4 MB | 中 | 中 | P1 |
| 6. 移除编译器冗余资源 | 1-2 MB | 低 | 低 | P1 |
| 3. 资源压缩 | 0.5-1 MB | 低 | 低 | P2 |

**如果全部实施，预计 APK 大小可从 119.9 MB 降至约 70-80 MB**（仅算 APK 文件本身）。通过 App Bundle 分发，用户实际下载大小可进一步降至 **50-60 MB**。

## 推荐实施路径

1. **第一阶段（快速见效）**：方案 2 + 6 + 7 → 预计减少 12-15 MB，工作量 1-2 天
2. **第二阶段（核心优化）**：方案 1 + 3 → 预计额外减少 25-35 MB，工作量 2-3 天
3. **第三阶段（深度优化）**：方案 4 → 预计额外减少 2-4 MB，工作量 3-5 天
4. **分发优化**：方案 5 → 改用 AAB，用户侧体验最优
