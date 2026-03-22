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

## 选定方案：预编译资源 + AAPT2 `-R` overlay 注入

### 核心原理

将 AndroidX 库的资源在开发期用 AAPT2 预编译为 `.flat` 文件，运行时通过 `-R`（overlay）参数注入 AAPT2 link，资源会合并到 app 的命名空间中。

> **为什么不用 `-I`？** `-I` 将资源视为外部框架包（类似 `android.jar`），资源需要显式包名前缀引用。而 AndroidX 的 attr/style（如 `app:cardCornerRadius`、`Widget.MaterialComponents.*`）需要在 app 命名空间中解析，必须使用 `-R` overlay 方式。

### 整体流程

```
开发期（本地/CI 一次性）                    运行时（每次构建生成 App）
┌──────────────────────────┐              ┌──────────────────────────────────┐
│ AAR 拆包                  │              │ AAPT2 link                       │
│  ├─ 提取 res/ + Manifest │              │  -I android.jar                  │
│  └─ 提取 classes.jar     │              │  -R *.flat (AndroidX) ← 新增     │
│          ↓                │              │  -R project.zip                  │
│ AAPT2 compile            │              │                                  │
│  → *.flat 文件            │              │ Javac                            │
│          ↓                │              │  classpath += androidx-classes.jar│
│ 合并 classes.jar         │              │                                  │
│  → androidx-classes.jar  │              │ D8                               │
│          ↓                │              │  programFiles += androidx-classes │
│ 打包进 build-engine      │              │                                  │
│  src/main/assets/        │              │ APK 打包 → 签名                   │
└──────────────────────────┘              └──────────────────────────────────┘
```

### 分步说明

| # | 阶段 | 时机 | 具体操作 |
|---|------|------|----------|
| 1 | 预处理 AAR | 开发期（本地/CI） | 拆解 AAR，用 AAPT2 compile 所有库资源为 `.flat` 文件；合并所有 `classes.jar` 为 `androidx-classes.jar` |
| 2 | 打包进 assets | 开发期 | 将 `.flat` 文件打包为 `androidx-res-compiled.zip`，`androidx-classes.jar` 打包为 `androidx-classes.jar.zip`，放入 `build-engine/src/main/assets/` |
| 3 | 运行时解压 | App 首次启动 | 仿照 `rt.jar` 的解压机制，解压到 `filesDir`（`.flat` 文件解压到 `filesDir/androidx-res-compiled/` 目录） |
| 4 | AAPT2 link | 每次构建 | 将 `.flat` 文件逐个通过 `-R` 传入（先于项目资源，确保项目资源可覆盖） |
| 5 | Javac 编译 | 每次构建 | classpath 加入 `androidx-classes.jar` |
| 6 | D8 DEX 转换 | 每次构建 | 将 `androidx-classes.jar` 作为 **programFiles**（而非 classpathFiles），使其参与 DEX 转换 |
| 7 | APK 打包 | 每次构建 | DEX 已包含库代码，无需额外处理 |

### 已修改的代码文件

| 文件 | 改动内容 |
|------|----------|
| `Aapt2ResourceCompiler.kt` | link 参数中为每个 `.flat` 文件添加 `-R` |
| `D8DexConverter.kt` | 预编译库 JAR 加入 `addProgramFiles` |
| `JavacCompiler.kt` | classpath 加入 `androidxClassesJar` |
| `BuildWorkspace.kt` | 新增 `androidxClassesJar` 和 `androidxResCompiledDir` 字段 |
| `BuildModule.java` | 新增 `getAndroidxClassesJar()` 和 `getAndroidxResCompiledDir()` 解压逻辑 |

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

## 实际打包库版本

### 核心目标库

| 库 | 版本 |
|----|------|
| `com.google.android.material:material` | 1.4.0 |
| `androidx.appcompat:appcompat` | 1.3.1 |
| `androidx.appcompat:appcompat-resources` | 1.3.1 |
| `androidx.constraintlayout:constraintlayout` | 2.1.0 |

### 传递依赖

| 库 | 版本 |
|----|------|
| `androidx.activity:activity` | 1.2.4 |
| `androidx.annotation:annotation` | 1.2.0 |
| `androidx.annotation:annotation-experimental` | 1.0.0 |
| `androidx.arch.core:core-common` | 2.1.0 |
| `androidx.arch.core:core-runtime` | 2.1.0 |
| `androidx.cardview:cardview` | 1.0.0 |
| `androidx.collection:collection` | 1.1.0 |
| `androidx.constraintlayout:constraintlayout-core` | 1.0.0 |
| `androidx.coordinatorlayout:coordinatorlayout` | 1.1.0 |
| `androidx.core:core` | 1.5.0 |
| `androidx.cursoradapter:cursoradapter` | 1.0.0 |
| `androidx.customview:customview` | 1.0.0 |
| `androidx.drawerlayout:drawerlayout` | 1.0.0 |
| `androidx.dynamicanimation:dynamicanimation` | 1.0.0 |
| `androidx.fragment:fragment` | 1.3.6 |
| `androidx.interpolator:interpolator` | 1.0.0 |
| `androidx.lifecycle:lifecycle-common` | 2.3.1 |
| `androidx.lifecycle:lifecycle-livedata` | 2.0.0 |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.3.1 |
| `androidx.lifecycle:lifecycle-runtime` | 2.3.1 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.3.1 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.3.1 |
| `androidx.loader:loader` | 1.0.0 |
| `androidx.recyclerview:recyclerview` | 1.1.0 |
| `androidx.savedstate:savedstate` | 1.1.0 |
| `androidx.tracing:tracing` | 1.0.0 |
| `androidx.transition:transition` | 1.2.0 |
| `androidx.vectordrawable:vectordrawable` | 1.1.0 |
| `androidx.vectordrawable:vectordrawable-animated` | 1.1.0 |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 |
| `androidx.viewpager:viewpager` | 1.0.0 |
| `androidx.viewpager2:viewpager2` | 1.0.0 |

### 已移除的库（字节码分析确认无引用）

| 库 | 原因 |
|----|------|
| `asynclayoutinflater` | 无核心库引用 |
| `constraintlayout-solver` | 已被 `constraintlayout-core` 取代 |
| `documentfile` | SAF 工具，无核心库引用 |
| `legacy-support-core-ui` | 旧兼容层，无核心库引用 |
| `legacy-support-core-utils` | 旧兼容层，无核心库引用 |
| `localbroadcastmanager` | 已废弃，无核心库引用 |
| `print` | 打印框架，无核心库引用 |
| `slidingpanelayout` | 无核心库引用 |
| `swiperefreshlayout` | 无核心库引用 |

### 本地化资源

仅保留 zh-rCN、zh-rTW、zh-rHK、ja、ko、en-rGB 语言资源，其余 80+ 语言已剥离（Material 组件在这些语言下会 fallback 到默认英文）。

### 产物文件

| 文件 | 位置 | 大小 | 说明 |
|------|------|------|------|
| `androidx-classes.jar.zip` | `build-engine/src/main/assets/` | ~4.7 MB | 所有库的 classes 合并 JAR（zip 压缩） |
| `androidx-res-compiled.zip` | `build-engine/src/main/assets/` | ~1.3 MB | AAPT2 compile 产出的 614 个 `.flat` 文件，运行时解压后通过 `-R` 传入 AAPT2 link |

### 实现说明

最终实现采用 `-R`（overlay）方式而非 `-I`（import）方式注入 AndroidX 资源：

- `-I` 方式会将资源视为外部框架包（类似 `android.jar`），需要显式包名前缀引用，导致 `app:cardCornerRadius` 等无命名空间前缀的属性无法解析
- `-R` 方式将预编译的 `.flat` 资源文件作为 overlay 合并到 app 的资源命名空间中，`style/Widget.MaterialComponents.*`、`attr/cardCornerRadius` 等均可正常解析
- 运行时流程：解压 `androidx-res-compiled.zip` → 获得 `.flat` 文件目录 → AAPT2 link 时逐个通过 `-R` 传入（先于项目资源，确保项目资源可覆盖库资源）
