# 插件模式下无法使用 Fragment / FragmentManager

> 状态：**已知问题，暂未修复**
> 记录日期：2026-04-12
> 相关文件：
> - `app/src/main/kotlin/com/vibe/app/plugin/PluginResourceLoader.kt`
> - `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`
> - `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java`
> - `app/src/main/assets/agent-system-prompt.md`

## 1. 症状

生成的 App 在插件模式（通过 `PluginContainerActivity` 加载 APK）运行时，只要代码里调用了 `getSupportFragmentManager()` 或任何基于 `FragmentManager` 的 API，就会在运行时抛出 `NoSuchMethodError`：

```
java.lang.NoSuchMethodError: No virtual method getSupportFragmentManager()
  Landroidx/fragment/app/FragmentManager;
  in class Lcom/vibe/generated/<pkg>/MainActivity; or its super classes
  (declaration of '...MainActivity' appears in
   /data/user/0/com.vibe.app/files/projects/<id>/app/build/bin/signed.apk!classes2.dex)
    at ...MainActivity$StockAdapter$ViewHolder$1.onClick(MainActivity.java:xxx)
```

独立模式（直接安装 APK 运行）不受影响。

## 2. 根因

这是**类身份（Class identity）不一致**的问题，不是单纯的 "FragmentManager 没初始化"。

### 2.1 ClassLoader 架构

`PluginResourceLoader.createPluginClassLoader` 构造的链路：

```
DexClassLoader(plugin APK)
      ↓ parent
ShadowBridgeClassLoader
      ↓ 只转发 com.tencent.shadow.core.runtime.* 到宿主
      ↓ 其它一律走 bootClassLoader
bootClassLoader (android.* / java.*)
```

`shouldLoadFromHost` 只共享 `com.tencent.shadow.core.runtime.*`，其注释明确说明过去尝试共享 `androidx.*` 会级联引发 `AppCompatDelegateImpl` 的 `VerifyError`，所以**所有 androidx 类都从插件 DEX 自己的副本里加载**。

### 2.2 继承链断裂

- 插件里 `MainActivity` 的父类在字节码里是 `com.tencent.shadow.core.runtime.ShadowActivity`。
- 插件 CL 解析这个父类时走 `ShadowBridgeClassLoader` → **返回宿主的** `ShadowActivity.class`。
- 宿主的 `ShadowActivity extends androidx.appcompat.app.AppCompatActivity`（**宿主 CL 的那一份**）→ 宿主的 `FragmentActivity` → ……

于是运行时 `MainActivity` 的父类链条全是**宿主 CL 的 androidx 类**。

与此同时，插件的 DEX 里**也带着它自己编译时用的那一份** `androidx.fragment.app.FragmentActivity`、`AppCompatActivity`、`FragmentManager` 等（通过 `PreDexCache` + `androidx-classes.jar` 打进了 `classes2.dex`）。插件代码里 `invokevirtual getSupportFragmentManager()` 的方法引用，通过插件 CL 解析出来的 `FragmentActivity` 是**插件 DEX 自己的那一份**。

结果：
- 字节码里方法引用的 `FragmentActivity` = 插件版本
- `MainActivity` 运行时父类链里的 `FragmentActivity` = 宿主版本
- 两个 `Class<?>` 同名但身份不同
- ART 做虚方法派发时无法在 `MainActivity` 的实际类层次里匹配到该方法 → 报 `NoSuchMethodError: ... in class ... or its super classes`

### 2.3 即使修好身份问题，还有第二层障碍

`ShadowActivity.performCreate`（`shadow-runtime/.../ShadowActivity.java:53-93`）在插件模式下**故意跳过 `super.onCreate`**，因为插件 Activity 从未被 Android 系统正式 attach 过，调用真正的 `Activity.onCreate` 会炸。

这意味着即使把类身份对齐，`FragmentActivity.onCreate` 也没机会跑完 —— `mFragments.attachHost(null)` 和 `mFragments.dispatchCreate()` 都不会被执行，`<fragment>` / `FragmentContainerView` 的 `LayoutInflater.Factory2` 也不会被注册。

`PluginContainerActivity.kt:200-205` 的 `onBackPressed` 注释已经侧面记录了这个限制：

```kotlin
// Don't call super.onBackPressed() — it accesses FragmentManager
// which is not initialized (plugin is not a real system Activity).
```

## 3. 当前规避措施

在 agent system prompt（`app/src/main/assets/agent-system-prompt.md`）里显式禁止模型生成 Fragment 相关代码：

- 不得使用 `Fragment` / `FragmentManager` / `FragmentTransaction` / `DialogFragment` / `BottomSheetDialogFragment` / `NavHostFragment` / `ViewPager2 + FragmentStateAdapter`；
- 多屏导航用 `ViewFlipper` / `FrameLayout` 切换子 View；
- 对话框用 `MaterialAlertDialogBuilder` / `BottomSheetDialog`；
- 分页用 `ViewPager2 + RecyclerView.Adapter`。

这是**提示词层面的权宜之计**，不是根治。

## 4. 候选修复方案

### 方案 A：共享 androidx 骨干类 + 手动 bootstrap FragmentController

**思路**：

1. 在 `ShadowBridgeClassLoader.shouldLoadFromHost` 里增加共享：`androidx.fragment.app.*`、`androidx.lifecycle.*`、`androidx.savedstate.*`、`androidx.activity.*`、`androidx.arch.core.*`（可能还要 `androidx.appcompat.app.AppCompatActivity`）。让宿主和插件看到同一份 `FragmentActivity`，解决类身份问题。
2. 在 `ShadowActivity.performCreate` 里通过反射手动调用 `mFragments.attachHost(null)` 和 `mFragments.dispatchCreate()`，补上被跳过的 `FragmentActivity.onCreate` 关键逻辑。
3. 把 Fragment 的 `LayoutInflater.Factory2` 注册到插件 `LayoutInflater` 上，让 XML 里的 `<fragment>` / `FragmentContainerView` 能被识别。

**风险**：
- `PluginResourceLoader.kt:103-107` 的注释已经警告：共享 androidx 类会沿 `ActionBar → ActionMode → AppCompatDelegateImpl` 级联触发 `VerifyError`。以前踩过坑。
- 需要谨慎挑选"哪些类共享、哪些保留在插件本地"，避免撞到 Material 组件通过 `R.styleable` 访问宿主资源 ID 的路径。
- 本质是打补丁，每次 androidx 升级都要重新验证。

**预估工作量**：1–3 天开发，但调 `VerifyError` 可能失控。

### 方案 B：构建期字节码改写（推荐）

**思路**：按 `docs/shadow-plugin-feasibility.md` 和 `docs/superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md` 已规划的设计实施：

1. 设备首次插件构建时，用 ASM 对 `androidx-classes.jar` 做一次性字节码改写，把 `AppCompatActivity` 的继承链从 `FragmentActivity → ComponentActivity → Activity` 改写为最终指向 `ShadowActivity`（这是腾讯 Shadow 官方推荐的思路）。
2. 改写结果缓存为 `filesDir/shadow-androidx-classes.jar`。
3. 插件模式的 COMPILE / DEX 步骤使用改写后的 jar，独立模式继续用原版。
4. `ShadowBridgeClassLoader` 依然只共享 `com.tencent.shadow.core.runtime.*`。因为插件自己的 androidx 继承链已经自洽，不再有类身份冲突。
5. `FragmentActivity.onCreate` 可以正常跑完，整条 Fragment 基础设施（`attachHost` / `dispatchCreate` / `LayoutInflater.Factory2`）都正常工作。

**收益**：
- 一次投入解决根本问题；
- 同时解锁 `Fragment`、`FragmentContainerView`、`ViewPager2 + FragmentStateAdapter`、`NavHostFragment`、`DialogFragment` 等 Fragment 全家桶；
- 顺带清除其它插件模式遗留限制（`setSupportActionBar`、`super.onBackPressed` 等，它们和 Fragment 问题同源）。

**预估工作量**：3–7 天。

### 方案 C：在 `ShadowActivity` 里逐个打补丁（不推荐）

重写 `getSupportFragmentManager()` 等方法，内部构造一个 `FragmentController` 返回。`<fragment>` XML 标签依然无法工作（那条路径走 `LayoutInflater.Factory2`，不走方法调用）。只是列在这里对比用。

## 5. 决策

暂时维持现状（提示词禁用 Fragment）。方案 B 作为长期规划，择机启动；启动时先撤掉 `agent-system-prompt.md` 里的 Fragment 禁令。

## 6. 参考

- `docs/shadow-plugin-feasibility.md` — Shadow 插件框架整体可行性分析
- `docs/superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md` — 方案 B 的详细计划
- `docs/superpowers/plans/2026-03-28-shadow-full-integration.md` — Shadow 集成整体计划
