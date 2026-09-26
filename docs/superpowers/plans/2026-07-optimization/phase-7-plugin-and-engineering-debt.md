# Phase 7: 插件根治与工程还债 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读 `00-progress.md`,确认前置状态与本 phase 当前进度;
> 2. 本文 `file:line` 基于 `dev@be1f944`,动手前用 grep 重新定位,不要盲信行号;
> 3. 每完成一个 Task:勾选本文 checkbox、跑该 Task"验证"节命令、独立 commit、更新 `00-progress.md` 状态表"当前位置";
> 4. 偏离计划必须在文末"实施记录"表中说明,禁止静默偏离;
> 5. 建议用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 执行。

**目标:** 根治插件模式的两大结构性限制(Fragment 全家桶不可用、单 Activity),并偿还两笔工程债(三个 ChatCompletions gateway ~880 行复制粘贴 + 死代码、模板导航层缺失)。完成后撤掉 `agent-system-prompt.md` 里的 Fragment / 多 Activity 禁令,把多屏导航从"模型即兴发挥"变成"预置组件填空"。

**评审依据:** `docs/optimization-review-2026-07.md` §1.3 方向三(ASM 字节码改写)、§1.3 方向二第 3 条(生命周期/返回键,已由 Phase 3 承接,本文只做衔接)、§4-14(gateway 重复与死代码)、§4-15(模板单一 + ScreenRouter)。

**前置依赖:**
- Task 7.2 **硬依赖** Task 7.1(多 Activity 需要 Fragment/AppCompat 继承链先自洽);
- Task 7.3 **建议在 Phase 1 完成之后执行**(Phase 1 的 Task 1.1/1.2/1.8 会改这三个 gateway,先重构会使 Phase 1 的 file:line 全部失效;开工前查 `00-progress.md`,若 Phase 1 未完成,先做 Phase 1 或在实施记录中说明合并策略);
- Task 7.4 无硬依赖,但插件模式下的返回键分发依赖 Phase 3 Task 3.5(`performBackPressed`),未完成时插件模式返回键会直接关闭容器(可接受的降级,见 Task 7.4 Step 4);
- Task 7.1/7.4 可与其他 phase 并行。

**涉及模块:** `build-engine`(shadow 包新增、PreDexCache、BuildWorkspace)、`app/plugin`、`shadow-runtime`、`app/feature/agent/loop`、`app/src/main/assets/templates`、`app/src/main/assets/agent-system-prompt.md`。

---

## Task 7.1: ASM AndroidX 改写落地(解锁 Fragment 全家桶)

**现状与证据:**
- 完整的实施计划已存在:`docs/superpowers/plans/2026-03-28-shadow-androidx-on-device-transform.md`(下称"ASM 计划")。本 Task **直接执行该计划**,不在此复述其代码;本节只记录**该计划写成之后代码基线发生的漂移**和**该计划遗漏的集成缺口**,执行时以本节为准做增量修正。
- **漂移 1:ASM 计划的 Task 2 已提前落地一半。** `BuildModels.kt:32-34` 已有 `enum class BuildMode { STANDALONE }`,`CompileInput.buildMode` 字段已存在(`BuildModels.kt:59`);`ProjectInitializer` 已把 `buildMode` 穿透到 `toCompileInput` / `buildProject`(`ProjectInitializer.kt:44,52,160,176,196`);`ChatViewModel` 两处构建调用已显式传参(`ChatViewModel.kt:373,411`)。**缺的只是枚举里的 `PLUGIN` 值**和真正消费它的逻辑。
- **漂移 2:插件预览构建入口已就位但传错值。** `ChatViewModel.runBuild`(`:360` 附近)日志写着 `"Starting PLUGIN build"`,构建成功后调 `pluginManager.launchPlugin(...)`,但 `buildMode` 传的是 `STANDALONE`(`:373`)——因为 `PLUGIN` 值还不存在。这是本 Task 的主接线点。
- **缺口 1:PreDexCache 会让改写失效(ASM 计划未覆盖)。** ASM 计划声称"D8DexConverter 无需改动",这只对回退路径成立。实际上 `D8DexConverter.kt:53` 优先走 `PreDexCache.getOrCreateLibraryDex(context, minSdk)`,而 `PreDexCache.kt:41-49` **内部直接取 `BuildModule.getAndroidxClassesJar()`(原版 jar)**,不看 `workspace.androidxClassesJar`。插件模式下如果命中预 dex 缓存,打进 APK 的仍是**未改写**的 androidx dex,ASM 变换被静默绕过。必须给 PreDexCache 增加插件变体(Step 3)。
- **缺口 2:agent 的 run_build → launch_app 链路。** `LaunchAppTool.kt:38-52` 直接拿 `build/bin/signed.apk` 调 `launchPlugin`,而这个 APK 由 agent 的 `BuildTool` 产出(`BuildTool.kt:40-45`,走 `workspace.buildProject()`,默认 STANDALONE)。7.1 之后插件/独立两种产物**内容不同**,agent 构建必须也走 PLUGIN,否则 agent 预览的 APK 里 Fragment 依然是坏的。
- 假设核实:`shadow-runtime.jar` 恒在编译 classpath(`BuildWorkspace.kt:64-66`、`JavacCompiler.kt:119`)✅;生成 app 的 `MainActivity extends ShadowActivity`(模板 `MainActivity.java`)✅;Fragment 禁令在 `agent-system-prompt.md:16`,多 Activity 禁令在 `:15`,ViewPager2 限制在 `:35` ✅;根因与方案 B 的论证见 `docs/known-issues/fragment-in-plugin-mode.md` §2/§4。

**改动文件:**
- 按 ASM 计划:`gradle/libs.versions.toml`、`build-engine/build.gradle.kts`、`build-engine/.../model/BuildModels.kt`、`build-engine/.../shadow/ShadowClassRemapper.kt`(新建)、`build-engine/.../shadow/ShadowAndroidxTransformer.kt`(新建)、`build-engine/.../internal/BuildWorkspace.kt`、对应测试;
- 本文增量:`build-engine/.../dex/PreDexCache.kt`、`build-engine/.../dex/D8DexConverter.kt`、`app/.../presentation/ui/chat/ChatViewModel.kt`、agent 构建链路(`BuildTool` 背后的 `workspace.buildProject()`,定义位置用 grep `fun buildProject` 找)、`app/src/main/assets/agent-system-prompt.md`、`docs/known-issues/fragment-in-plugin-mode.md`。

- [ ] **Step 1: 执行 ASM 计划 Task 1、3、4(依赖、Remapper、Transformer)**

严格按 `2026-03-28-shadow-androidx-on-device-transform.md` 的 Task 1(ASM 9.7.1 依赖)、Task 3(`ShadowClassRemapper`,TDD)、Task 4(`ShadowAndroidxTransformer`,MD5 缓存,TDD)执行,包括其中的测试与 commit 步骤。两点注意:
- 其 Task 2 跳过大半,只需给枚举补值:

```kotlin
// build-engine/src/main/java/com/vibe/build/engine/model/BuildModels.kt:32
enum class BuildMode {
    STANDALONE,
    PLUGIN,
}
```

- 其 Task 5 采用文中 **Step 3 替代方案**(在 `BuildWorkspace.from()` 内解析,避免 build-logic → build-engine 循环依赖),Task 6 照做。变换缓存目录用 `File(context.filesDir, "shadow-cache")`。

- [ ] **Step 2: 验证 ASM 计划部分**

```bash
./gradlew :build-engine:test
./gradlew assembleDebug
grep -rn "CompileInput(" --include="*.kt" app/src build-engine/src | grep -v test
```

全绿;所有 `CompileInput` 调用点仍默认/显式 STANDALONE,行为零变化。

- [ ] **Step 3: 堵上 PreDexCache 缺口**

给 `PreDexCache.getOrCreateLibraryDex` 增加"androidx jar 覆写 + 独立缓存目录"参数,插件模式用改写后 jar 单独预 dex、单独缓存,与独立模式互不污染:

```kotlin
// PreDexCache.kt — 签名扩展(保持旧调用兼容)
fun getOrCreateLibraryDex(
    context: Context,
    minSdk: Int,
    androidxJarOverride: File? = null,          // PLUGIN 模式传改写后 jar
    cacheDirName: String = CACHE_DIR_NAME,      // PLUGIN 模式传 "predex-shadow"
): CachedDexFiles {
    val cacheDir = File(context.filesDir, cacheDirName)
    ...
    val jarsToPreDex = buildList {
        (androidxJarOverride ?: BuildModule.getAndroidxClassesJar())
            ?.takeIf { it.exists() }?.let { add(it) }
        BuildModule.getShadowRuntimeJar()?.takeIf { it.exists() }?.let { add(it) }
        BuildModule.getJsoupJar()?.takeIf { it.exists() }?.let { add(it) }
    }
    ...
}
```

`D8DexConverter.kt:53` 处按 `input.buildMode` 分流:

```kotlin
val preDexResult = if (input.buildMode == BuildMode.PLUGIN) {
    PreDexCache.getOrCreateLibraryDex(
        context, input.minSdk,
        androidxJarOverride = workspace.androidxClassesJar, // 此时已是改写版(Step 1 的 BuildWorkspace 逻辑)
        cacheDirName = "predex-shadow",
    )
} else {
    PreDexCache.getOrCreateLibraryDex(context, input.minSdk)
}
```

同时确认 `isCacheValid`(`PreDexCache.kt:101`)若被外部调用,也要能区分两个缓存目录(grep 调用点,当前若只有内部使用则不动)。**注意 Phase 6 Task 6.2(RClassCache)也会动 `cleanBuildCache` 附近逻辑,若 Phase 6 已完成,先 grep 对齐现状。**

为其补一个测试:PLUGIN 与 STANDALONE 两种模式各跑一次 `getOrCreateLibraryDex`,断言产物落在不同目录、互不失效(仿照现有 PreDexCache 测试写法;若无现成测试,用 TemporaryFolder + 最小 jar 构造,参考 ASM 计划 Task 4 的测试)。

- [ ] **Step 4: 接线所有"以插件预览为目的"的构建**

规则:**插件预览构建 → PLUGIN;安装构建 → STANDALONE**。

1. `ChatViewModel.runBuild`(`:373` 附近)改传 `BuildMode.PLUGIN`;`installBuild`(`:411` 附近)保持 `STANDALONE`;
2. agent 构建链路:grep `fun buildProject` 找到 `BuildTool.kt:45` 背后的 workspace 方法,加 `buildMode` 参数(默认 STANDALONE),`BuildTool` 传 `PLUGIN`——因为 agent 构建的产物被 `LaunchAppTool` 以插件方式加载(`LaunchAppTool.kt:38-52`);
3. 两种产物共用 `build/bin/signed.apk` 路径且 `cleanOutput` 默认 true(`BuildModels.kt:57`),交替构建会互相覆盖但不会串味;在 `LaunchAppTool` 的 APK 存在性检查处加一行注释说明"此 APK 必须由 PLUGIN 模式构建产出"。

- [ ] **Step 5: 撤销 Fragment 禁令(多 Activity 禁令保留给 7.2)**

1. `agent-system-prompt.md:16` 的 Fragment 禁令整段改写为允许并给出惯例(Fragment、DialogFragment、BottomSheetDialogFragment、ViewPager2+FragmentStateAdapter 均可用);`:35` 的 ViewPager2 限制注释删掉;`:15` 多 Activity 禁令**原样保留**;
2. `docs/known-issues/fragment-in-plugin-mode.md` 顶部状态改为"已修复(方案 B,Phase 7.1)",正文追加一节记录落地日期与实现位置;`docs/README.md` §3 该条目的"(历史,暂未修复)"标注同步更新;
3. 真机回归发现的其余同源限制(`setSupportActionBar`、`super.onBackPressed`)若随改写自然解除,一并从提示词/注释里清理,记入实施记录。

- [ ] **Step 6: 验证**

```bash
./gradlew :build-engine:test && ./gradlew test && ./gradlew assembleDebug
```

人工验证(真机/模拟器,Android 10+):
1. 生成一个使用 `BottomNavigationView + Fragment`(或 `ViewPager2 + FragmentStateAdapter`)的测试应用;
2. 插件模式运行:页签切换、Fragment 生命周期、`DialogFragment` 弹出均正常,无 `NoSuchMethodError`;
3. 首次插件构建日志出现 ASM 变换耗时记录,二次构建命中 `shadow-androidx-classes.jar` 缓存;
4. 同一项目独立模式(安装)运行正常——独立产物不含改写类(可 `unzip -p signed.apk classes*.dex | strings | grep -c ShadowActivity` 粗验,或直接行为验证);
5. 一个 7.1 之前创建的旧项目重新构建、插件运行,回归正常。

- [ ] **Step 7: Commit(增量部分)**

ASM 计划内步骤按其原文的 commit 划分;本文增量部分:

```bash
git commit -m "feat(build): plugin-mode predex cache for shadow-transformed androidx (opt task 7.1)"
git commit -m "feat(app): route plugin preview builds through BuildMode.PLUGIN (opt task 7.1)"
git commit -m "docs: lift Fragment ban from agent prompt, mark fragment issue fixed (opt task 7.1)"
```

**验收标准:** Fragment 测试应用插件模式运行正常;独立模式与旧项目回归无影响;预 dex 双缓存互不污染;提示词 Fragment 禁令已撤、多 Activity 禁令仍在;known-issues 文档状态已更新。

---

## Task 7.2: 插件内多 Activity 支持(依赖 7.1)

**现状与证据:**
- `PluginManager.findMainActivity`(`PluginManager.kt:120-129`)只取 `info?.activities?.firstOrNull()?.name`,Manifest 里声明的其余 Activity 一律不可达;注意 `getPackageArchiveInfo(path, GET_ACTIVITIES)` **拿不到 intent-filter**,无法用 MAIN/LAUNCHER 判定主入口,只能靠命名约定。
- 5 个容器槽位 `PluginSlot0..4` 全部 `launchMode="singleTask"` + 独立 `taskAffinity`(`AndroidManifest.xml:55-91`),singleTask 无法在同任务内叠第二个实例 → 插件内跳转需要**standard launchMode 的伴生容器**。
- `ShadowActivity.startActivity`(`ShadowActivity.java:251-255`)无条件委托 `hostDelegator.superStartActivity(intent)`——插件代码 `startActivity(new Intent(this, DetailActivity.class))` 会让系统去解析一个未安装的组件,直接失败。注意 `Intent(Context, Class)` 生成的 component 包名是**宿主包名**(`ShadowActivity.getPackageName` 返回宿主包名,`:291-295`),所以拦截判定必须**按类**(能否被插件 ClassLoader 加载)而不是按包名。
- 容器与插件的通信面是 `HostActivityDelegator`(`HostActivityDelegator.java:12-28`),容器按 `EXTRA_MAIN_CLASS` 反射实例化插件 Activity(`PluginContainerActivity.kt:44-48,115-121`,extras 常量 `:373-377`,槽位子类 `:382-386`)。
- `ActivityHolder`(`PluginInspectorService.kt:33-47`)每槽位只持有**单个** Activity 引用;多容器叠栈后,`clear(slotIndex)` 会在任意一层销毁时清掉引用,`finishPluginAndReturn`(`PluginManager.kt:131` 附近)也只 finish 一层。需要栈化。
- Phase 3 Task 3.4(getIntent 语义修正)与本 Task 有交集:跳转时携带的 extras 应从插件侧 `getIntent()` 可读。开工前查 `00-progress.md` 确认 Phase 3 状态,按 Step 5 的两种情况衔接。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt`(findMainActivity)
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`(startPluginActivity 实现 + 伴生容器子类)
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt`(ActivityHolder 栈化)
- Modify: `app/src/main/AndroidManifest.xml`(PluginStandardSlot0..4)
- Modify: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/HostActivityDelegator.java`
- Modify: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java`
- Create: `app/src/main/kotlin/com/vibe/app/plugin/PluginIntentResolver.kt`(拦截判定纯函数)
- Test: `app/src/test/kotlin/com/vibe/app/plugin/PluginIntentResolverTest.kt`
- Modify: `app/src/main/assets/agent-system-prompt.md`(撤多 Activity 禁令)

**设计总览:**

```
插件代码 startActivity(Intent(this, DetailActivity::class))
  → ShadowActivity.startActivity 拦截
    → PluginIntentResolver:component.className 能被插件 CL 加载
      且是 ShadowActivity 子类?
      ├── 是 → hostDelegator.startPluginActivity(className, extras)
      │        → 容器构造 Intent(this, PluginStandardSlot{N})   ← 同槽位、同进程、standard
      │           携带 EXTRA_APK_PATH(同 APK)/ EXTRA_MAIN_CLASS(目标类)
      │           / EXTRA_SLOT_INDEX / EXTRA_PROJECT_ID / EXTRA_PLUGIN_EXTRAS
      │        → 新容器叠在同任务栈上,反射实例化目标 ShadowActivity
      └── 否 → hostDelegator.superStartActivity(intent)          ← 现行为(系统 Activity 等)
返回键 / finish → 容器 finish → 任务栈回落到上一层容器 → 上一个插件 Activity resume
```

- [ ] **Step 1: ActivityHolder 栈化**

`PluginInspectorService.kt:33-47` 改为每槽位一个栈,Inspector 取栈顶,清理按引用移除:

```kotlin
object ActivityHolder {
    private val stacks = Array(PluginManager.MAX_SLOTS) { ArrayDeque<Activity>() }

    @Synchronized fun push(slotIndex: Int, activity: Activity) {
        stacks.getOrNull(slotIndex)?.addLast(activity)
    }

    @Synchronized fun get(slotIndex: Int): Activity? = stacks.getOrNull(slotIndex)?.lastOrNull()

    @Synchronized fun remove(slotIndex: Int, activity: Activity) {
        stacks.getOrNull(slotIndex)?.remove(activity)
    }

    @Synchronized fun all(slotIndex: Int): List<Activity> = stacks.getOrNull(slotIndex)?.toList() ?: emptyList()
}
```

调用点迁移:容器 `onCreate` 的 `set(...)` → `push(...)`;`onDestroy` 的 `clear(...)` → `remove(slotIndex, this)`(grep `ActivityHolder.` 找全调用点);`PluginManager.finishPluginAndReturn` 改为 `ActivityHolder.all(slotIndex).asReversed().forEach { it.finish() }`。Inspector 侧 `get()` 语义不变(自动变为"栈顶")。

- [ ] **Step 2: findMainActivity 解析全部 activities + 命名约定选主入口**

`PluginManager.kt:120-129` 替换为:

```kotlin
private fun findMainActivity(apkPath: String, packageName: String): String {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
        val names = info?.activities?.map { it.name }.orEmpty()
        // getPackageArchiveInfo 拿不到 intent-filter,按约定选主入口:
        // 生成项目的主入口恒为 MainActivity(模板与系统提示词共同保证)
        names.firstOrNull { it.endsWith(".MainActivity") }
            ?: names.firstOrNull()
            ?: "$packageName.MainActivity"
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse plugin manifest, using default", e)
        "$packageName.MainActivity"
    }
}
```

- [ ] **Step 3: 新增 standard 伴生容器 PluginStandardSlot0..4**

`PluginContainerActivity.kt:382-386` 之后追加:

```kotlin
class PluginStandardSlot0 : PluginContainerActivity()
class PluginStandardSlot1 : PluginContainerActivity()
class PluginStandardSlot2 : PluginContainerActivity()
class PluginStandardSlot3 : PluginContainerActivity()
class PluginStandardSlot4 : PluginContainerActivity()
```

`AndroidManifest.xml` 在现有 5 个槽位声明(`:55-91`)之后仿写 5 条,**差异仅三处**:类名、`launchMode="standard"`、无需改 taskAffinity(与同号 PluginSlot 相同,保证叠进同一任务栈):

```xml
<activity
    android:name="com.vibe.app.plugin.PluginStandardSlot0"
    android:process=":plugin0"
    android:taskAffinity="com.vibe.app.plugin0"
    android:launchMode="standard"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar" />
<!-- Slot1..4 同型,process/taskAffinity 序号对应 -->
```

- [ ] **Step 4: HostActivityDelegator 加 startPluginActivity + 容器实现**

`HostActivityDelegator.java` 接口追加(shadow-runtime 是宿主与插件共享的桥接层,加方法即可,插件侧无需重编译旧项目——旧 APK 不会调用新方法):

```java
/** Launches another Activity declared inside the same plugin APK, stacked in the same slot. */
void startPluginActivity(String targetClassName, Bundle pluginExtras);
```

`PluginContainerActivity` 实现(companion 中新增 `EXTRA_PLUGIN_EXTRAS = "plugin_forwarded_extras"`;伴生容器类数组仿照 `PluginManager.slotActivities` 定义):

```kotlin
override fun startPluginActivity(targetClassName: String, pluginExtras: Bundle?) {
    val standardSlots = arrayOf(
        PluginStandardSlot0::class.java, PluginStandardSlot1::class.java,
        PluginStandardSlot2::class.java, PluginStandardSlot3::class.java,
        PluginStandardSlot4::class.java,
    )
    val intent = Intent(this, standardSlots[slotIndex]).apply {
        putExtra(EXTRA_APK_PATH, intent.getStringExtra(EXTRA_APK_PATH))
        putExtra(EXTRA_MAIN_CLASS, targetClassName)
        putExtra(EXTRA_PLUGIN_LABEL, intent.getStringExtra(EXTRA_PLUGIN_LABEL))
        putExtra(EXTRA_SLOT_INDEX, slotIndex)
        putExtra(EXTRA_PROJECT_ID, intent.getStringExtra(EXTRA_PROJECT_ID))
        if (pluginExtras != null) putExtra(EXTRA_PLUGIN_EXTRAS, pluginExtras)
    }
    startActivity(intent)
}
```

注意:容器自身的 `intent` 属性与参数名冲突时用 `getIntent()` 显式区分;APK 路径等从**当前容器的 intent** 原样透传(同一插件 APK)。

- [ ] **Step 5: ShadowActivity.startActivity 拦截 + 判定纯函数**

判定逻辑放进可单测的纯函数(新文件,不依赖 Android 类型,ClassLoader 用 `java.lang.ClassLoader`):

```kotlin
// app/src/main/kotlin/com/vibe/app/plugin/PluginIntentResolver.kt
object PluginIntentResolver {
    /**
     * 返回目标类名 —— 当且仅当 className 能被插件 ClassLoader 定义(而非宿主/boot 委托)
     * 且是 ShadowActivity 子类;否则返回 null(走系统 startActivity)。
     */
    fun resolveIntraPluginTarget(
        className: String?,
        pluginClassLoader: ClassLoader,
        shadowActivityClass: Class<*>,
    ): String? {
        if (className.isNullOrEmpty()) return null
        return try {
            val clazz = Class.forName(className, false, pluginClassLoader)
            val definedByPlugin = clazz.classLoader === pluginClassLoader
            val isShadowActivity = shadowActivityClass.isAssignableFrom(clazz) &&
                clazz != shadowActivityClass
            if (definedByPlugin && isShadowActivity) className else null
        } catch (_: ClassNotFoundException) {
            null
        }
    }
}
```

`ShadowActivity.startActivity`(`:251-255`)改为(Java 侧只做薄拦截,判定逻辑因 shadow-runtime 不能依赖 app 模块,把上述判定用 Java 内联在 ShadowActivity 里即可——**保持一份实现**:推荐把纯函数写在 shadow-runtime 里,如 `com.tencent.shadow.core.runtime.PluginIntentResolver`,app 侧测试直接引用它):

```java
@Override
public void startActivity(Intent intent) {
    if (hostDelegator != null) {
        ComponentName cn = intent.getComponent();
        String target = PluginIntentResolver.resolveIntraPluginTarget(
                cn != null ? cn.getClassName() : null,
                hostDelegator.getPluginClassLoader(),
                ShadowActivity.class);
        if (target != null) {
            hostDelegator.startPluginActivity(target, intent.getExtras());
            return;
        }
        hostDelegator.superStartActivity(intent);
        return;
    }
    super.startActivity(intent);
}
```

`startActivityForResult` 本期**不做**插件内路由(跨容器回传结果需要额外管线),遇到插件内目标时降级为 `startPluginActivity` 并在 logcat 打 WARN(结果不会回传);在系统提示词里注明该限制(Step 7)。

extras 读取:目标 Activity 的 `getIntent()` 若 Phase 3 Task 3.4 已完成,把 `EXTRA_PLUGIN_EXTRAS` 合并进其构造的合成 intent;若未完成,容器把 `EXTRA_PLUGIN_EXTRAS` 摊平进 `getHostIntent()` 返回值的 extras(临时方案,3.4 落地时收编)。两种情况都要保证插件代码 `getIntent().getStringExtra("k")` 能读到跳转时 `putExtra("k", v)` 的值。

- [ ] **Step 6: 单元测试**

`PluginIntentResolverTest`(JUnit4,手写 Fake,禁 mock 框架——仓库无 mockk/mockito):
1. 用自定义 `ClassLoader`(内存内 defineClass 或直接用两个真实 URLClassLoader 加载测试 fixture 类)覆盖四种情况:插件类且 ShadowActivity 子类 → 返回类名;宿主/boot 委托加载的类 → null;ClassNotFound → null;ShadowActivity 自身 → null;
2. 若构造 ClassLoader fixture 成本过高,可退化为:把"definedByPlugin && isShadowActivity"的布尔组合逻辑抽成 `internal fun shouldRoute(definedByPlugin: Boolean, isSubclass: Boolean, isSelf: Boolean)` 纯函数测试,ClassLoader 部分靠人工验证兜底(在实施记录中注明)。

```bash
./gradlew test --tests "*PluginIntentResolver*"
```

- [ ] **Step 7: 撤多 Activity 禁令 + 文档**

1. `agent-system-prompt.md:15` 改写为允许多 Activity,并给出规则:每个新 Activity 必须在 `AndroidManifest.xml` 声明(`:107` 已允许);主入口必须叫 `MainActivity`;跳转用显式 Intent;`startActivityForResult` 在插件预览模式下结果不回传(改用单例/静态状态传递);
2. `docs/known-issues/fragment-in-plugin-mode.md` 若 Step 7.1 已更新,本处追加多 Activity 解禁说明;`00-progress.md` 状态表更新。

- [ ] **Step 8: 人工验证清单(真机/模拟器)**

1. 生成"列表 → 详情"两 Activity 测试应用:插件模式点击列表项 → 详情页打开(带 extras 数据正确显示)→ 返回键回列表(列表状态保留)→ 再次进入正常;
2. 详情页再跳第三个 Activity,连续返回逐层回落;
3. `finishPluginAndReturn`(聊天页"返回编辑")在详情页打开时调用,整栈关闭、回到 VibeApp;
4. 插件内 `startActivity(Intent(Intent.ACTION_VIEW, uri))` 等系统跳转仍走系统(不被拦截);
5. 同一应用独立安装模式运行,行为与标准 Android 一致;
6. 单 Activity 旧项目回归:插件模式启动、运行、退出正常。

- [ ] **Step 9: Commit**

```bash
git commit -m "feat(plugin): multi-activity support with standard companion slots (opt task 7.2)"
git commit -m "docs: lift multi-activity ban from agent prompt (opt task 7.2)"
```

**验收标准:** 列表-详情多 Activity 应用在插件模式跳转/回退/传参正常;系统 Intent 不受影响;单 Activity 项目回归无变化;`PluginIntentResolverTest` 全绿;提示词禁令已撤且注明 forResult 限制。

---

## Task 7.3: BaseChatCompletionsGateway 抽取 + 死代码清理

**现状与证据(已逐行 diff 三个文件):**

| 逻辑块 | Kimi(327 行) | Qwen(280 行) | DeepSeek(271 行) |
|--------|------|------|------|
| `json` 配置 | `:49-53` | `:36-40` | `:46-50` — **三者字节级相同** |
| system 前导块拼接(instructions + TOOL_REQUIRED/ENCOURAGE) | `:181-197` | `:162-178` | `:177-193` — **相同** |
| USER 消息 | **独有**:`buildKimiUserContent` 带图片(`:203,236-269`),因此多注入 `Context` | 纯文本 `:184` | 纯文本 `:206` |
| ASSISTANT reasoning 回传 | 全量保留 `:209` | **完全不带** `:187-201` | 仅最后一条保留(`lastReasoningIdx`,`:198-214`) |
| TOOL / SYSTEM 分支 | 相同 | 相同 | 相同 |
| tool 定义序列化 + schema 转换 | `:96-104,298-311` | `:80-88,251-264` | `:91-99,258-271` — **函数体相同,仅函数名不同** |
| toolChoice | 恒 `"auto"`(`:105`,thinking 模型不支持 required,`:38-40`) | **独有**:`toQwenToolChoice()` 支持 `"none"`(`:230-238`) | 恒 `"auto"`(`:100`) |
| TOOL_REQUIRED/ENCOURAGE 常量 | `:271-282` | `:216-227` | `:242-253` — **字节级相同** |
| `ToolCallAccumulator` + collect 主体 + streamError + tool-call 发射循环 | `:82-86,127-163` | `:67-71,110-146` | `:77-81,123-159` — **相同**(仅 reasoning delta:Qwen 不累积) |
| Completed 发射 | 带 reasoning `:165-173` | **不带** `:148-154` | 带 reasoning `:161-169` |
| URL 规范化 | `trimEnd('/')`(`:294-296`) | **独有**:重写 `/api/v2/...` → `/compatible-mode/v1`(`:240-249`) | `trimEnd('/')`(`:256`) |
| DTO | — | — | **三者已共用** `data/dto/qwen` 包(`QwenChatCompletionRequest.kt` / `QwenChatCompletionResponse.kt`),无需合并 |

- 死代码确认:`toAgentToolCall` 全仓仅两处**定义**、零调用(`KimiChatCompletionsAgentGateway.kt:313`、`QwenChatCompletionsAgentGateway.kt:266`,均 private);`EcjCompiler.kt` 是 8 行 `@Deprecated` 空壳,**代码零引用**,仅文档提及(`CLAUDE.md:79`、`AGENTS.md:79`、`docs/build-engine.md:19`);`ConversationContextManager` 的实例方法 `trimConversation/splitIntoTurns/summarizeTurn` 零调用,但 **companion `estimateTokens` 仍被 6 个文件调用**(coordinator、compaction 链、诊断)且 Phase 4 Task 4.4 继续依赖它——**只删实例方法,保留 companion**。
- 路由:`ProviderAgentGatewayRouter.kt:34-40` 按 `ClientType` 分发;`:15-21` 的路由表注释漏了 DEEPSEEK 行,顺手补上。
- 现有测试:`app/src/test/.../QwenChatCompletionsAgentGatewayTest.kt`,重构后必须继续全绿。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/BaseChatCompletionsGateway.kt`
- Modify: 三个 gateway、`ProviderAgentGatewayRouter.kt`(仅注释)、`ConversationContextManager.kt`
- Delete: `build-engine/src/main/java/com/vibe/build/engine/compiler/EcjCompiler.kt`(+ 同步 `CLAUDE.md`、`AGENTS.md`、`docs/build-engine.md` 的提法:改为"ECJ 兼容壳已于 Phase 7 移除")
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/GatewayRequestSnapshotTest.kt`(新建,重构前先行)

- [ ] **Step 0: 前置检查**

读 `00-progress.md`:若 Phase 1 未完成,停——先做 Phase 1(它会改这三个文件);若 Phase 1 已完成,本节所有行号先 grep 重定位,把 Phase 1 新增的逻辑(重试参数、SSE 终止校验、token/apiUrl 参数化)也纳入基类上提范围。

- [ ] **Step 1: 快照基线测试(重构之前写、重构之前提交)**

原则:**三个 provider 对请求字段极敏感,重构的回归保障是"重构前后序列化字节等同"**。

1. 写 `FakeOpenAIAPI`(手写 Fake,实现 `OpenAIAPI` 接口,`streamQwenChatCompletion` 记录收到的 `QwenChatCompletionRequest` 并返回固定的空 Flow;接口其余方法抛 `NotImplementedError`);
2. 构造一个覆盖面完整的 `AgentModelRequest` fixture:含 instructions、2 个 tool(带嵌套 schema)、多轮 history(USER 带图片附件路径、ASSISTANT 带 reasoning + toolCalls、TOOL 带 JsonObject payload 与纯文本两种)、`toolChoice = REQUIRED`;
3. 对三个 gateway 各跑一次 `streamTurn(fixture)`,把捕获的 request 用**固定配置的 Json**(`prettyPrint = true` + 显式字段序)序列化,写入 `app/src/test/resources/gateway-snapshots/{kimi,qwen,deepseek}-request.json`;
4. 测试断言"当前序列化结果 == 资源文件内容"。首次运行生成基线,人工 review 三份 JSON 确认符合各 provider 语义(Kimi 有 image_url、Qwen 无 reasoning、DeepSeek 仅末条 reasoning)后提交;
5. Kimi 的图片分支依赖 `Context` 读文件:fixture 的附件路径指向测试临时目录的真实小图片(JUnit `TemporaryFolder`),`Context` 用 Robolectric 或——仓库若未引 Robolectric——把 `buildKimiUserContent` 的文件读取旁路做成可注入(`(path) -> ByteArray?`),Fake 注入固定字节。以仓库现状为准,选改动最小的一种,记入实施记录。

```bash
./gradlew test --tests "*GatewayRequestSnapshot*"
git commit -m "test(agent): golden request snapshots for chat-completions gateways (opt task 7.3)"
```

- [ ] **Step 2: 抽取 BaseChatCompletionsGateway**

骨架(覆写点即 diff 表中的差异项,公共块全部上提):

```kotlin
abstract class BaseChatCompletionsGateway(
    protected val openAIAPI: OpenAIAPI,
    protected val diagnosticLogger: ChatDiagnosticLogger,
) : AgentModelGateway {

    protected enum class ReasoningReplayPolicy { FULL, NONE, LAST_ONLY }

    /** 子类差异点 */
    protected abstract val providerTag: String                       // 日志/诊断标识
    protected open val reasoningReplay: ReasoningReplayPolicy = ReasoningReplayPolicy.FULL
    protected open val emitReasoningInCompleted: Boolean = true      // Qwen = false
    protected open fun normalizeBaseUrl(raw: String): String = raw.trimEnd('/')
    protected open fun toolChoiceFor(request: AgentModelRequest): String? =
        if (request.tools.isNotEmpty()) "auto" else null              // Qwen 覆写支持 "none"
    protected open fun buildUserMessage(item: ConversationItem): QwenChatMessage =
        QwenChatMessage(role = "user", content = qwenTextContent(item.text.orEmpty()))  // Kimi 覆写图片
    protected open fun diagnosticExtras(request: AgentModelRequest): Map<String, Any?> = emptyMap()

    /** 公共实现:json 配置、buildMessages 骨架、tool 序列化、TOOL_REQUIRED/ENCOURAGE、
     *  ToolCallAccumulator、collect 主体、streamError、tool-call 发射、Completed。 */
    final override fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> = ...

    companion object {
        // TOOL_REQUIRED / TOOL_ENCOURAGE 从三处收敛到这里
    }
}
```

要点:
1. `buildMessages` 骨架用 `reasoningReplay` 驱动 ASSISTANT 分支(`FULL` 全带 / `NONE` 不带 / `LAST_ONLY` 用 lastReasoningIdx 逻辑,三种现行为逐字保留);
2. schema 转换三份同体函数收敛为基类一个 `protected fun AgentToolSchema.toChatToolSchema()`;
3. reasoning delta 累积:基类恒累积 + emit(与 Kimi/DeepSeek 一致),Qwen 通过 `emitReasoningInCompleted = false` 在收尾丢弃——**注意**这会让 Qwen 的行为从"不累积"变成"累积但不发",内存差异可忽略,快照/事件序列不变;
4. 若 Phase 1 已完成:重试/SSE 终止校验/token 参数化逻辑一并上提,子类不再各持一份。

- [ ] **Step 3: 逐个迁移子类(一个 commit 一个)**

顺序:DeepSeek(最小)→ Qwen(toolChoice/URL 覆写)→ Kimi(图片 + Context)。每迁移一个:

```bash
./gradlew test --tests "*GatewayRequestSnapshot*" --tests "*QwenChatCompletionsAgentGateway*"
git commit -m "refactor(agent): migrate <X> gateway onto BaseChatCompletionsGateway (opt task 7.3)"
```

快照测试**不许改基线**;任何字节差异都是回归,必须修基类而不是改快照。迁移完成后三个子类应各只剩:构造注入、providerTag、覆写点(预期每个 ≤80 行)。

- [ ] **Step 4: 死代码清理**

1. 删 `KimiChatCompletionsAgentGateway` 与 `QwenChatCompletionsAgentGateway` 的 `toAgentToolCall`(迁移时自然消失,确认基类没有带入);
2. 删 `EcjCompiler.kt`;同步改 `CLAUDE.md:79`、`AGENTS.md:79`、`docs/build-engine.md:19` 中"EcjCompiler 仍存在"的表述;
3. `ConversationContextManager`:删除 `trimConversation` / `splitIntoTurns` / `summarizeTurn` 及其私有辅助(先 grep 确认零调用),**保留 companion `estimateTokens`** 与类壳,类 KDoc 更新为"仅存 token 估算,Phase 4 校准器围绕它工作";
4. `ProviderAgentGatewayRouter.kt:15-21` 注释表补 DEEPSEEK 行;
5. 全量 grep 复查:`grep -rn "toAgentToolCall\|EcjCompiler" --include="*.kt" app/src build-engine/src` 应为空。

- [ ] **Step 5: 验证 + Commit**

```bash
./gradlew test && ./gradlew :build-engine:test && ./gradlew assembleDebug
git commit -m "refactor(agent): remove dead code (toAgentToolCall, EcjCompiler, deprecated trim methods) (opt task 7.3)"
```

人工验证:Kimi、Qwen、DeepSeek 三个平台各跑一个带工具调用的短任务,流式输出、思考内容、工具调用均正常。

**验收标准:** 三份请求快照字节不变;三个子类合计行数从 ~880 降到基类 + 3×薄子类;死代码清零且文档同步;三平台真机冒烟通过。

---

## Task 7.4: ScreenRouter 模板组件 + 系统提示词

**现状与证据:**
- 模板只有一个 `EmptyActivity`(`app/src/main/assets/templates/`,含 MainActivity / CrashHandlerApp / AppLogger / SimpleImageLoader 四个 Java 文件);
- 多屏导航目前全靠系统提示词一句"use a `ViewFlipper`/`FrameLayout` and swap child views"(`agent-system-prompt.md:16` 内),模型每次即兴实现视图切换与返回键,是构建失败与运行时 bug 高发区(评审 §4-15);
- 模板复制机制是**递归全量复制**(`ProjectInitializer.copyAssetDir`,`ProjectInitializer.kt:252-270`),无硬编码文件清单,新增 Java 文件会被自动带入新项目并做 `$packagename` 替换(`:354`);
- 生成项目为 Java 8 源级别(`CLAUDE.md`),ScreenRouter 必须 Java 8 兼容、零第三方依赖;
- 返回键:独立模式下 `MainActivity.onBackPressed()` 覆写即可;插件模式下返回键先到容器,Phase 3 Task 3.5 之后才会分发到插件的 `onBackPressed`(`performBackPressed`)。未做 3.5 时插件模式返回键直接关容器——功能退化但不崩溃。
- 与 7.1/7.2 的关系:即使 Fragment 与多 Activity 解禁,ScreenRouter 仍是**默认推荐**的轻量导航(无生命周期开销、插件/独立行为完全一致);提示词措辞按执行时 `:15-16` 的实际状态调整(可能已被 7.1/7.2 改过,先 grep)。

**改动文件:**
- Create: `app/src/main/assets/templates/EmptyActivity/app/src/main/java/$packagename/ScreenRouter.java`
- Modify: `app/src/main/assets/agent-system-prompt.md`(导航规则 + 模板文件清单 `:112` 附近)

- [ ] **Step 1: 新建 ScreenRouter.java(完整代码,直接落盘)**

```java
package $packagename;

import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal multi-screen navigator: a view stack inside one FrameLayout.
 * Each screen is a plain View (build it in code or inflate from XML).
 *
 * Usage in MainActivity:
 *   FrameLayout root = new FrameLayout(this);
 *   setContentView(root);
 *   router = new ScreenRouter(root);
 *   router.replaceRoot("home", buildHomeScreen());
 *   ...
 *   router.push("detail", buildDetailScreen(item));   // navigate forward
 *   ...
 *   @Override public void onBackPressed() {
 *       if (!router.pop()) super.onBackPressed();      // back = pop, exit at root
 *   }
 */
public final class ScreenRouter {

    /** One stack entry: a tag for identification plus the screen's root view. */
    private static final class Entry {
        final String tag;
        final View view;

        Entry(String tag, View view) {
            this.tag = tag;
            this.view = view;
        }
    }

    private final FrameLayout container;
    private final List<Entry> stack = new ArrayList<>();

    public ScreenRouter(FrameLayout container) {
        this.container = container;
    }

    /** Clears the whole stack and shows the given screen as the new root. */
    public void replaceRoot(String tag, View screen) {
        stack.clear();
        container.removeAllViews();
        stack.add(new Entry(tag, screen));
        container.addView(screen);
    }

    /** Pushes a new screen on top; the previous screen is hidden but kept (state preserved). */
    public void push(String tag, View screen) {
        if (!stack.isEmpty()) {
            stack.get(stack.size() - 1).view.setVisibility(View.GONE);
        }
        stack.add(new Entry(tag, screen));
        container.addView(screen);
    }

    /** Replaces the top screen without growing the stack (e.g. tab switching). */
    public void replaceTop(String tag, View screen) {
        if (!stack.isEmpty()) {
            Entry top = stack.remove(stack.size() - 1);
            container.removeView(top.view);
        }
        stack.add(new Entry(tag, screen));
        container.addView(screen);
    }

    /**
     * Pops the top screen. Returns true if a screen was popped,
     * false if already at the root (caller should then exit or ignore).
     */
    public boolean pop() {
        if (stack.size() <= 1) {
            return false;
        }
        Entry top = stack.remove(stack.size() - 1);
        container.removeView(top.view);
        stack.get(stack.size() - 1).view.setVisibility(View.VISIBLE);
        return true;
    }

    /** Pops until the screen with the given tag is on top. No-op if the tag is not in the stack. */
    public void popTo(String tag) {
        boolean found = false;
        for (Entry entry : stack) {
            if (entry.tag.equals(tag)) {
                found = true;
                break;
            }
        }
        if (!found) {
            return;
        }
        while (stack.size() > 1 && !stack.get(stack.size() - 1).tag.equals(tag)) {
            pop();
        }
    }

    /** Tag of the currently visible screen, or null if the stack is empty. */
    public String currentTag() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1).tag;
    }

    /** Root view of the currently visible screen, or null if the stack is empty. */
    public View currentView() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1).view;
    }

    /** Number of screens on the stack. */
    public int depth() {
        return stack.size();
    }
}
```

风格约束:与模板内既有 `AppLogger.java` / `SimpleImageLoader.java` 同风格(final 工具类、无三方依赖、KDoc 式注释);**不改 MainActivity 模板**(保持极简,ScreenRouter 是预置可选组件,与 SimpleImageLoader 同定位)。

- [ ] **Step 2: 确认模板机制自动携带新文件**

1. `grep -n "MainActivity.java\|SimpleImageLoader" app/src/main/kotlin/com/vibe/app/feature/projectinit/ProjectInitializer.kt` —— 确认没有硬编码模板文件清单(基线上没有,`copyAssetDir` 递归复制);
2. `assembleDebug` 后新建一个项目,确认 `files/projects/{id}/app/src/main/java/<pkg>/ScreenRouter.java` 存在且包名已替换;
3. 注意:**存量项目不会获得该文件**(模板只影响新建),提示词措辞要写成"如果项目里存在 ScreenRouter 则优先使用;不存在时可自行创建该文件(给出同样代码)或用 FrameLayout 手法"——避免模型在旧项目里引用不存在的类。

- [ ] **Step 3: 系统提示词更新**

`agent-system-prompt.md`(行号按执行时 grep,基线参考):
1. 导航规则处(基线 `:15-16`,可能已被 7.1/7.2 改写):把"use a `ViewFlipper`/`FrameLayout` and swap child views"改为指向 ScreenRouter,并给 3-5 行用法示例(push/pop/onBackPressed 覆写,与 Java 文件头注释一致);说明"简单多屏优先 ScreenRouter;需要独立生命周期/转场的复杂场景才用多 Activity(若 7.2 已解禁)";
2. 模板文件清单(基线 `:112` 附近)加一行 `- src/main/java/{{PACKAGE_PATH}}/ScreenRouter.java`;
3. 返回键规则:提示模型在 MainActivity 覆写 `onBackPressed` 接 `router.pop()`(基线下插件模式此覆写不会被调用,Phase 3.5 完成后生效——提示词不必解释这个内部细节,写标准覆写即可,两种模式都不崩)。

- [ ] **Step 4: 人工冒烟(真机/模拟器)**

1. 新建项目,让 agent 生成"列表-详情"两屏应用并明确要求用 ScreenRouter;
2. 插件模式:列表 → 详情 → (若 Phase 3.5 已完成)返回键回列表且滚动位置保留;(未完成)返回键关容器,无崩溃;
3. 独立安装模式:同一应用返回键逐屏回退,根屏返回键退出;
4. 观察 agent 是否正确引用了预置类而不是重写一份(提示词有效性检查)。

- [ ] **Step 5: Commit**

```bash
git add "app/src/main/assets/templates/EmptyActivity/app/src/main/java/\$packagename/ScreenRouter.java" \
        app/src/main/assets/agent-system-prompt.md
git commit -m "feat(template): ScreenRouter view-stack navigator + prompt guidance (opt task 7.4)"
```

**加分项(非验收):** 增加"列表-详情""底部导航"2 个脚手架模板(评审 §4-15 的后半),涉及模板选择 UI,工作量大,建议另立计划;若执行者顺手做了,记入实施记录。

**验收标准:** 新建项目自动包含 ScreenRouter.java(包名正确);agent 生成的多屏应用实际使用它;插件/独立两种模式冒烟通过;提示词对存量项目(无此文件)有兜底措辞。

---

## Phase 完成检查

- [ ] `./gradlew test` 全绿
- [ ] `./gradlew :build-engine:test` 全绿
- [ ] `./gradlew assembleDebug` 成功
- [ ] 人工验证清单(汇总):
  - [ ] Fragment 测试应用(BottomNavigation/ViewPager2+FragmentStateAdapter)插件模式运行正常;独立模式与旧项目回归无影响
  - [ ] 列表-详情多 Activity 应用插件模式跳转/回退/传参正常;系统 Intent 不受影响
  - [ ] Kimi/Qwen/DeepSeek 三平台各一个工具调用任务冒烟通过
  - [ ] 新项目含 ScreenRouter,agent 生成多屏应用实际使用
- [ ] `agent-system-prompt.md` 的 Fragment 禁令、多 Activity 禁令均已撤除且替换为新规则
- [ ] `docs/known-issues/fragment-in-plugin-mode.md` 状态已更新;`docs/README.md` §3 标注同步
- [ ] 更新 `00-progress.md`:Phase 7 状态 → ✅ 已完成,填完成日期
- [ ] `git commit -m "docs: mark optimization phase 7 complete"`

## 实施记录(执行时追加)

| 日期 | 执行者 | 完成内容 | 偏离/备注 |
|------|--------|----------|-----------|
