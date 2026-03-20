# AndroidX 预编译库支持方案

> 目标：让模板生成的 App 可以在 XML 和 Java 中使用 Material / AppCompat / ConstraintLayout 组件，同时保持最小的编译链路改动。

## 背景

当前编译管线不支持 AAR 格式的第三方库。Material、AppCompat、ConstraintLayout 等 AndroidX 库原始格式均为 AAR，包含 Android 资源（layout、style、drawable 等）和 AndroidManifest。

### 完整 AAR 运行时合并方案（已排除）

在设备上实现完整的 AAR 资源合并需要：

1. 解包每个 AAR → 提取 `classes.jar`、`res/`、`AndroidManifest.xml`、`R.txt`
2. 合并所有库的 `res/` 和项目 `res/` → 处理同名资源冲突与优先级
3. 合并所有 AndroidManifest → `<uses-permission>`、`<activity>` 等节点合并去重
4. 资源 ID 重映射 → 保证各库的 R 值不冲突
5. 为每个库包名生成 R.java

这相当于在手机上重写一个迷你版 AGP 资源合并器，复杂度过高，已排除。

## 选定方案：预编译资源包 + AAPT2 `-I` 注入

### 核心原理

AAPT2 的 `link` 命令通过 `-I` 参数引入预编译资源包，与引入 `android.jar` 是同一机制。当前代码仅有一个 `-I`：

```kotlin
// Aapt2ResourceCompiler.kt
"-I", workspace.bootstrapJar.absolutePath   // android.jar
```

将 AndroidX 库的资源预编译为一个资源包，在 AAPT2 link 时通过额外的 `-I` 传入即可，无需运行时资源合并。

### 整体流程

```
开发期（本地/CI 一次性）                    运行时（每次构建生成 App）
┌──────────────────────────┐              ┌──────────────────────────────────┐
│ AAR 拆包                  │              │ AAPT2 link                       │
│  ├─ 提取 res/ + Manifest │              │  -I android.jar                  │
│  └─ 提取 classes.jar     │              │  -I androidx-res.apk  ← 新增     │
│          ↓                │              │                                  │
│ AAPT2 compile + link     │              │ Javac                            │
│  → androidx-res.apk      │              │  classpath += androidx-classes.jar│
│          ↓                │              │                                  │
│ 合并 classes.jar         │              │ D8                               │
│  → androidx-classes.jar  │              │  programFiles += androidx-classes │
│          ↓                │              │                                  │
│ 打包进 build-engine      │              │ APK 打包 → 签名                   │
│  src/main/assets/        │              └──────────────────────────────────┘
└──────────────────────────┘
```

### 分步说明

| # | 阶段 | 时机 | 具体操作 |
|---|------|------|----------|
| 1 | 预处理 AAR | 开发期（本地/CI） | 拆解 AAR，用 AAPT2 把所有库资源 compile + link 成 `androidx-res.apk`；合并所有 `classes.jar` 为 `androidx-classes.jar` |
| 2 | 打包进 assets | 开发期 | 将 `androidx-res.apk` 和 `androidx-classes.jar`（压缩为 zip）放入 `build-engine/src/main/assets/` |
| 3 | 运行时解压 | App 首次启动 | 仿照 `rt.jar` 的解压机制，解压到 `filesDir` |
| 4 | AAPT2 link | 每次构建 | 在 link 参数中增加 `-I androidx-res.apk` |
| 5 | Javac 编译 | 每次构建 | `classpathEntries` 加入 `androidx-classes.jar` |
| 6 | D8 DEX 转换 | 每次构建 | 将 `androidx-classes.jar` 作为 **programFiles**（而非 classpathFiles），使其参与 DEX 转换 |
| 7 | APK 打包 | 每次构建 | DEX 已包含库代码，无需额外处理 |

### 需要修改的代码文件

| 文件 | 改动内容 | 规模 |
|------|----------|------|
| `Aapt2ResourceCompiler.kt` | link 参数增加 `-I` 指向预编译资源包 | 几行 |
| `D8DexConverter.kt` | 预编译库 JAR 从 `addClasspathFiles` 改为 `addProgramFiles` | 几行 |
| `ProjectInitializer.kt` | 自动将预编译 JAR 加入 `classpathEntries` | 几行 |
| `CompileInput` / `BuildWorkspace` | 新增字段指向预编译资源包路径 | 几行 |
| 资源解压逻辑（仿 `BuildModule`） | 新增 `androidx-res.apk` 和 `androidx-classes.jar` 的解压 | 十几行 |

### Manifest 合并说明

AAPT2 `-I` 不会合并 Manifest。但 AppCompat / Material / ConstraintLayout 的 Manifest 中基本只有 `minSdkVersion` 声明，没有必须合并的 `<activity>` 等组件声明。因此**对这三个库无需实现 Manifest 合并**，只需在模板 `AndroidManifest.xml` 中设置正确的 `theme` 即可。

如果未来引入包含 `ContentProvider`、`BroadcastReceiver` 等组件声明的库，则需另行评估 Manifest 合并需求。

### 模板改动

模板 `EmptyActivity` 需要同步更新：

- `AndroidManifest.xml` — `application` 节点的 `android:theme` 改为 Material/AppCompat 主题
- `MainActivity.java` — 可改为继承 `AppCompatActivity`
- `res/values/styles.xml` — 使用 Material 主题（如 `Theme.MaterialComponents.DayNight.DarkActionBar`）
- 布局 XML — 可使用 Material 组件（`MaterialButton`、`CoordinatorLayout` 等）

## 方案权衡

| 维度 | 评价 |
|------|------|
| 代码改动量 | 小（4-5 个文件，每个改几行） |
| 运行时开销 | 几乎为零（仅多传一个 `-I` 参数） |
| APK 体积增长 | 中等（AndroidX 库的 classes + 资源会增加 assets 体积） |
| 库版本管理 | 版本随 VibeApp 发布固定，升级需重新预编译并更新 assets |
| 用户自选版本 | 不支持（对基础库来说是合理的限制） |
| 扩展性 | 如需新增其他 AndroidX 库，按相同流程预编译即可 |

## 目标库版本（初始）

| 库 | 推荐版本 |
|----|----------|
| `androidx.appcompat:appcompat` | 1.7.0 |
| `com.google.android.material:material` | 1.12.0 |
| `androidx.constraintlayout:constraintlayout` | 2.2.1 |

> 具体版本以实施时最新稳定版为准。需包含这些库的所有传递依赖（如 `androidx.core`、`androidx.fragment` 等）。
