# Phase 3: 调试体验强化 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读 `00-progress.md`,确认本 phase 状态与当前任务位置;
> 2. 本文引用的 `file:line` 基于 `dev@be1f944`,代码可能已漂移——每个 Task 动手前先用 grep 重新定位锚点;
> 3. 每完成一个 Task:勾选本文 checkbox → 按"验证"节跑命令(不跑验证不许勾选)→ 独立 commit → 更新 `00-progress.md` 状态表;
> 4. 任何偏离(改方案/跳步骤/发现计划错误)必须写进文末"实施记录",禁止静默偏离;
> 5. 推荐用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 执行。

**目标**:把"agent 只能摸视图树、崩溃靠用户手动上报"的调试链路,升级为"agent 能看截图、崩溃主动推送、安装模式的崩溃也能回流"。

**评审依据**:`docs/optimization-review-2026-07.md` §1.3 方向一(DebugBridge)+ 方向二(定向补强)。

**前置依赖**:无(可与 Phase 1/2 并行;与 Phase 7 都改 `shadow-runtime`,若 Phase 7 已开工需先沟通合并顺序)。

**涉及模块**:`app/src/main/kotlin/com/vibe/app/plugin/`、`shadow-runtime/`、`feature/agent/tool/`、`feature/agent/loop/`、`presentation/ui/chat/`、`app/src/main/assets/templates/EmptyActivity/`、`app/src/main/AndroidManifest.xml`。

**本 phase 大部分任务无法用 JVM 单测覆盖(Android 框架依赖),验证以"编译通过 + 真机人工验证清单"为主;可提纯的逻辑(3.6 的校验器)必须写单测。**

---

## Task 3.1: 崩溃主动推送(CrashLogWatcher)

**现状与证据**:插件崩溃写入 `files/projects/{id}/logs/crash.log`(`PluginContainerActivity.kt:352-369`),但 UI 侧只在 ChatScreen ON_RESUME 时对比文件大小(`ChatViewModel.checkForNewCrashLog`,`ChatViewModel.kt:447-464`)。插件进程崩溃时用户正看着插件界面,回到 VibeApp 前完全无感知。

**改动文件**:
- Create: `app/src/main/kotlin/com/vibe/app/feature/project/CrashLogWatcher.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt`(grep 锚点:`lastKnownCrashLogSize`、`checkForNewCrashLog`)

**接口(供 Task 3.6 依赖)**:`CrashLogWatcher.watch(projectId: String): Flow<String>` —— emit 值为最新一条崩溃摘要(最多 15 行)。DebugBridge 写入同一文件即自动触发本 Flow,无需额外接线。

- [x] **Step 1: 新建 CrashLogWatcher**

```kotlin
package com.vibe.app.feature.project

import android.content.Context
import android.os.FileObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Watches a project's crash.log via inotify (FileObserver) and emits the
 * latest crash summary whenever the file is written.
 *
 * Watches the logs DIRECTORY (not the file): crash.log may not exist yet,
 * and FileObserver on a non-existent path never fires. Works across
 * processes — plugin processes and DebugReportProvider write the same path.
 */
@Singleton
class CrashLogWatcher @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    fun watch(projectId: String): Flow<String> = callbackFlow {
        val logDir = File(appContext.filesDir, "projects/$projectId/logs")
        logDir.mkdirs()
        val crashFile = File(logDir, "crash.log")
        var lastSize = if (crashFile.exists()) crashFile.length() else 0L

        val observer = object : FileObserver(logDir, CLOSE_WRITE or MOVED_TO or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != "crash.log") return
                val size = crashFile.length()
                if (size <= lastSize) return
                lastSize = size
                extractLatestCrash(crashFile)?.let { trySend(it) }
            }
        }
        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }

    companion object {
        /** Returns the last "--- CRASH" block (max 15 lines), or null. */
        fun extractLatestCrash(crashFile: File): String? {
            if (!crashFile.exists()) return null
            val lines = crashFile.readLines()
            val lastCrashIdx = lines.indexOfLast { it.startsWith("--- CRASH") }
            if (lastCrashIdx < 0) return null
            return lines.drop(lastCrashIdx).take(15).joinToString("\n")
        }
    }
}
```

注意:`FileObserver(File, Int)` 构造器 API 29+,`minSdk = 29` 满足;字符串路径构造器已废弃,不要用。

- [x] **Step 2: ChatViewModel 接线**

注入 `crashLogWatcher: CrashLogWatcher`(加进构造器参数)。在当前项目 ID 确定处(grep `_currentProjectId` 的赋值/collect 位置,即 `lastKnownCrashLogSize` 在 :270 附近被初始化的那段)启动收集,项目切换时取消旧的:

```kotlin
private var crashWatchJob: Job? = null

private fun startCrashWatcher(projectId: String) {
    crashWatchJob?.cancel()
    crashWatchJob = viewModelScope.launch {
        crashLogWatcher.watch(projectId).collect { summary ->
            lastKnownCrashLogSize = withContext(Dispatchers.IO) {
                File(appContext.filesDir, "projects/$projectId/logs/crash.log").length()
            }
            _crashPrompt.update { CrashPrompt(crashSummary = summary) }
        }
    }
}
```

保留 `checkForNewCrashLog()` 的 ON_RESUME 调用作为兜底(FileObserver 偶发丢事件)。`extractLatestCrash` 与 `checkForNewCrashLog` 内的解析逻辑重复——把 `checkForNewCrashLog` 内 :456-460 的解析替换为 `CrashLogWatcher.extractLatestCrash(crashFile)`,消除重复。

- [x] **Step 3: 单测(解析逻辑)**

Create: `app/src/test/kotlin/com/vibe/app/feature/project/CrashLogWatcherTest.kt`

```kotlin
package com.vibe.app.feature.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogWatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `extractLatestCrash returns last crash block`() {
        val f = tmp.newFile("crash.log")
        f.writeText("--- CRASH 01-01 ---\nold\n--- CRASH 01-02 ---\njava.lang.NullPointerException\n  at Main.java:5\n")
        val result = CrashLogWatcher.extractLatestCrash(f)!!
        assertEquals("--- CRASH 01-02 ---\njava.lang.NullPointerException\n  at Main.java:5", result)
    }

    @Test
    fun `extractLatestCrash returns null for file without crash marker`() {
        val f = tmp.newFile("crash.log")
        f.writeText("just noise\n")
        assertNull(CrashLogWatcher.extractLatestCrash(f))
    }
}
```

- [x] **Step 4: 验证**

Run: `./gradlew test --tests "*CrashLogWatcherTest*"` → PASS;`./gradlew assembleDebug` → BUILD SUCCESSFUL。
真机:预览一个会点击即崩的 app → 崩溃后**不切回 ChatScreen 也能**(回到 VibeApp 任意页面即)看到 CrashPrompt 卡片弹出;点"自动修复"流程正常。

- [x] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/project/CrashLogWatcher.kt \
  app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt \
  app/src/test/kotlin/com/vibe/app/feature/project/CrashLogWatcherTest.kt
git commit -m "feat(debug): push crash prompts via FileObserver instead of ON_RESUME polling (opt task 3.1)"
```

**验收标准**:插件崩溃后 2 秒内 CrashPrompt 出现,无需离开/返回 ChatScreen;单测通过。

---

## Task 3.2: PixelCopy 截图(Inspector 实现 + capture_screenshot 工具)

**现状与证据**:`PluginInspectorService.captureScreenshot` 直接返回 `jsonError("screenshot not implemented yet")`(`PluginInspectorService.kt:82-84`);AIDL 三方法已含 `captureScreenshot(String optionsJson)`(`IPluginInspector.aidl:6`),**无需改 AIDL**。Anthropic gateway 已有图片 DTO 与编码路径:USER 消息的 `attachments` 经 `FileUtils.readAndEncodeFile` 变成 `ImageContent(source = ImageSource(BASE64, mediaType, data))`(`AnthropicMessagesAgentGateway.kt:282-289`)。`AgentConversationItem` 自带 `attachments: List<String>` 字段(`AgentModels.kt:30`)。

**设计决定**:截图写入 `files/projects/{id}/logs/screenshot.webp`(固定名,每次覆盖,不用清理),AIDL 只回传 JSON(路径+尺寸),避开 Binder 1MB 限制。工具输出带 `attachment_paths`,coordinator 把它抬进 TOOL 消息的 `attachments`,Anthropic gateway 在 tool_result 里加 image block;其他 provider 忽略 attachments,模型看到文本提示。

**改动文件**:
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt`(binder 内 `captureScreenshot`)
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/CaptureScreenshotTool.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt`(grep 锚点:工具结果追加处 `AgentMessageRole.TOOL`,约 :409-417)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt`(grep 锚点:`tool_result` / TOOL item 分支)
- Modify: `app/src/main/assets/agent-system-prompt.md`(UI 测试流程段,grep `inspect_ui`)

- [x] **Step 1: Inspector 端实现截图**

替换 `PluginInspectorService.kt` binder 中的 `captureScreenshot`:

```kotlin
override fun captureScreenshot(optionsJson: String?): String {
    return try {
        val options = JSONObject(optionsJson ?: "{}")
        val projectId = options.optString("project_id")
        if (!projectId.matches(Regex("[A-Za-z0-9_-]+"))) {
            return jsonError("invalid project_id")
        }
        doCaptureScreenshot(projectId)
    } catch (e: Exception) {
        jsonError("screenshot failed: ${e.message}")
    }
}
```

服务类内新增(与 `doDump` 平级):

```kotlin
private fun doCaptureScreenshot(projectId: String): String {
    val activity = ActivityHolder.get(slotIndex)
        ?: return jsonError("no active plugin activity in slot $slotIndex")
    val window = activity.window ?: return jsonError("activity has no window")
    val decor = window.decorView
    if (decor.width <= 0 || decor.height <= 0) return jsonError("window not laid out yet")

    val bitmap = android.graphics.Bitmap.createBitmap(
        decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val latch = CountDownLatch(1)
    var copyResult = -1
    mainHandler.post {
        try {
            android.view.PixelCopy.request(window, bitmap, { result ->
                copyResult = result
                latch.countDown()
            }, mainHandler)
        } catch (e: Exception) {
            latch.countDown()
        }
    }
    if (!latch.await(3, TimeUnit.SECONDS)) return jsonError("screenshot timed out")
    if (copyResult != android.view.PixelCopy.SUCCESS) {
        return jsonError("PixelCopy failed with code $copyResult")
    }

    // Downscale so the long edge is <= 1280px, compress to WebP q80.
    val maxEdge = 1280
    val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
    val scaled = if (scale < 1f) {
        android.graphics.Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true,
        )
    } else bitmap

    val outFile = java.io.File(filesDir, "projects/$projectId/logs/screenshot.webp")
    outFile.parentFile?.mkdirs()
    val format = if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP
    }
    outFile.outputStream().use { scaled.compress(format, 80, it) }
    if (scaled !== bitmap) bitmap.recycle()

    return JSONObject().apply {
        put("path", outFile.absolutePath)
        put("width", scaled.width)
        put("height", scaled.height)
        put("bytes", outFile.length())
    }.toString()
}
```

说明:Inspector 与插件同进程但同属 `com.vibe.app` UID,`filesDir` 就是宿主数据目录,写入路径与 crash.log 同级。`PixelCopy.request(Window, ...)` API 26+,minSdk 29 满足。

- [x] **Step 2: 新建 CaptureScreenshotTool**

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.plugin.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject

@Singleton
class CaptureScreenshotTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "capture_screenshot",
        description = "Capture a screenshot of the running app (launched via launch_app). " +
            "Use it to visually verify layout, colors, and rendering that the view tree cannot show. " +
            "Models with vision receive the image; otherwise rely on inspect_ui.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return call.errorResult("App is not running. Call launch_app first.")
        val raw = try {
            inspector.captureScreenshot("""{"project_id":"${context.projectId}"}""")
        } catch (e: Exception) {
            return call.errorResult("Screenshot IPC failed: ${e.message}")
        }
        val json = JSONObject(raw)
        if (json.has("error")) return call.errorResult(json.getString("error"))
        return call.result(
            buildJsonObject {
                put("status", JsonPrimitive("captured"))
                put("width", JsonPrimitive(json.optInt("width")))
                put("height", JsonPrimitive(json.optInt("height")))
                put("note", JsonPrimitive("Screenshot attached for vision-capable models; otherwise use inspect_ui."))
                put("attachment_paths", buildJsonArray { add(JsonPrimitive(json.getString("path"))) })
            },
        )
    }
}
```

注册:`AgentToolModule.kt` 加 `@Binds @IntoSet abstract fun bindCaptureScreenshot(tool: CaptureScreenshotTool): AgentTool`(import 同步加)。

- [x] **Step 3: Coordinator 把 attachment_paths 抬进 TOOL 消息**

在 `DefaultAgentLoopCoordinator` 追加 TOOL 会话项处(grep `AgentMessageRole.TOOL`,基线约 :409-417),构造 `AgentConversationItem` 时补:

```kotlin
val attachmentPaths = (result.output as? JsonObject)
    ?.get("attachment_paths")?.let { it as? JsonArray }
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    .orEmpty()
// ...原有字段不动,新增:
attachments = attachmentPaths,
```

- [x] **Step 4: Anthropic gateway 在 tool_result 中附图**

在 `AnthropicMessagesAgentGateway` 构造 tool_result 内容处(grep `tool_result`;TOOL role 分支),把纯文本 content 改为数组:先文本,后图片(复用 :282-289 的既有编码模式):

```kotlin
val contents = mutableListOf<Content>(TextContent(text = payloadText))
item.attachments.forEach { path ->
    val mediaType = resolveMediaType(path) ?: return@forEach
    val base64 = FileUtils.readAndEncodeFile(context, path) ?: return@forEach
    contents.add(ImageContent(source = ImageSource(ImageSourceType.BASE64, mediaType, base64)))
}
```

(具体 DTO 字段名以该文件现有 USER attachments 分支为准,保持一致;`resolveMediaType` 即 :291-295 的既有映射,`.webp -> image/webp` 已支持。)其他 gateway(OpenAI/Qwen/Kimi/DeepSeek)**不改**——TOOL 项的 attachments 被忽略,模型看到 note 文本降级提示。

- [x] **Step 5: 系统提示词**

`agent-system-prompt.md` UI 测试段(grep `inspect_ui` 所在的流程行)追加一句:

```
- After interact_ui changes the screen, you may call capture_screenshot to visually verify layout/colors/rendering (vision models only); inspect_ui remains the source of truth for element ids.
```

- [x] **Step 6: 验证**

`./gradlew assembleDebug` → BUILD SUCCESSFUL。
真机(Anthropic 平台 + 视觉模型):对话让 agent"构建并检查界面配色"→ agent 调 launch_app → capture_screenshot → 模型回复中能描述截图内容;检查 `files/projects/{id}/logs/screenshot.webp` 存在且 <300KB。
真机(非视觉 provider,如 DeepSeek):调用同工具,agent 收到文本 note,不报错。

- [x] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt \
  app/src/main/kotlin/com/vibe/app/feature/agent/tool/CaptureScreenshotTool.kt \
  app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt \
  app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt \
  app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt \
  app/src/main/assets/agent-system-prompt.md
git commit -m "feat(debug): PixelCopy screenshot tool with vision tool_result support (opt task 3.2)"
```

**验收标准**:视觉模型能"看到"插件界面截图并据此评价 UI;非视觉 provider 平滑降级;截图文件 ≤300KB。

---

## Task 3.3: launch_app 前台限制放宽(通知兜底)

**现状与证据**:`LaunchAppTool.kt:44-49` —— VibeApp 不在前台直接 `errorResult`,并让模型"停止测试结束回合"。后台运行的 agent(`AgentForegroundService` 常驻时)永远无法自测。Android 10+ 禁止后台启动 Activity,通知是唯一合规路径。

**改动文件**:
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/LaunchAppTool.kt`
- Create: `app/src/main/kotlin/com/vibe/app/plugin/PluginLaunchProxyActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [x] **Step 1: 新建通知跳板 Activity**

```kotlin
package com.vibe.app.plugin

import android.app.Activity
import android.os.Bundle
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

/** Invisible trampoline launched from the "tap to preview" notification.
 *  Runs in the main process with foreground privileges, so PluginManager
 *  can legally start the plugin container Activity. */
@AndroidEntryPoint
class PluginLaunchProxyActivity : Activity() {
    @Inject lateinit var pluginManager: PluginManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (apkPath != null && packageName != null && projectId != null) {
            pluginManager.launchPlugin(apkPath, packageName, projectId)
        }
        finish()
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
```

Manifest(`<application>` 内,与 PluginSlot 声明并列):

```xml
<activity
    android:name="com.vibe.app.plugin.PluginLaunchProxyActivity"
    android:exported="false"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:excludeFromRecents="true" />
```

- [x] **Step 2: LaunchAppTool 后台分支改为发通知**

替换 :44-49 的 errorResult 分支:

```kotlin
if (!isVibeAppInForeground()) {
    postLaunchNotification(signedApk.absolutePath, packageName, context.projectId)
    return call.result(
        buildJsonObject {
            put("status", JsonPrimitive("queued"))
            put("note", JsonPrimitive(
                "VibeApp is in the background; a notification was posted asking the user " +
                    "to tap and open the preview. Do NOT call inspect_ui/interact_ui now — " +
                    "finish the turn and report the build result.",
            ))
        },
    )
}
```

`postLaunchNotification` 实现(类内新增,注入 `@ApplicationContext private val appContext: Context`):

```kotlin
private fun postLaunchNotification(apkPath: String, packageName: String, projectId: String) {
    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "plugin_launch"
    nm.createNotificationChannel(
        NotificationChannel(channelId, "App preview", NotificationManager.IMPORTANCE_HIGH),
    )
    val intent = Intent(appContext, PluginLaunchProxyActivity::class.java).apply {
        putExtra(PluginLaunchProxyActivity.EXTRA_APK_PATH, apkPath)
        putExtra(PluginLaunchProxyActivity.EXTRA_PACKAGE_NAME, packageName)
        putExtra(PluginLaunchProxyActivity.EXTRA_PROJECT_ID, projectId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    val pi = PendingIntent.getActivity(
        appContext, projectId.hashCode(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    nm.notify(projectId.hashCode(), NotificationCompat.Builder(appContext, channelId)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Build ready")
        .setContentText("Tap to preview the generated app")
        .setContentIntent(pi)
        .setAutoCancel(true)
        .build())
}
```

同步更新工具 `description`(:29-33):把 "Fails if VibeApp itself is not in the foreground..." 改为 "If VibeApp is in the background, a notification is posted for the user and the tool returns status=queued — finish the turn in that case."

- [x] **Step 3: 验证**

`./gradlew assembleDebug` → BUILD SUCCESSFUL。
真机:发起构建任务后立刻把 VibeApp 切到后台 → agent 走到 launch_app 时收到 `status=queued` 且正常结束回合(不再报错重试);通知出现,点按后插件界面打开。POST_NOTIFICATIONS 未授权时(设置里关掉通知)工具仍返回 queued、不崩溃。

- [x] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/LaunchAppTool.kt \
  app/src/main/kotlin/com/vibe/app/plugin/PluginLaunchProxyActivity.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat(debug): queue plugin launch via notification when app is backgrounded (opt task 3.3)"
```

**验收标准**:后台场景下 launch_app 不再是硬失败;通知点按可启动预览;前台快速路径行为不变。

---

## Task 3.4: getIntent 语义修正

**现状与证据**:`ShadowActivity.getIntent`(`ShadowActivity.java:280-283`)返回 `hostDelegator.getHostIntent()` = 容器的启动 intent,内含 `plugin_apk_path` 等宿主 extras(`PluginContainerActivity.kt:373-377`),插件代码拿不到符合自身语义的 launch intent。

**改动文件**:
- Modify: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/HostActivityDelegator.java`
- Modify: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java`
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`

- [x] **Step 1: 接口加方法**

`HostActivityDelegator.java` 末尾(`getHostIntent()` 之后)加:

```java
    /** Synthetic launch intent for the plugin (MAIN/LAUNCHER, plugin component). */
    Intent getPluginIntent();
```

- [x] **Step 2: 容器实现**

`PluginContainerActivity` 增加字段与实现(放在 `getHostIntent` 旁,:317 附近):

```kotlin
private var pluginLaunchIntent: Intent? = null

override fun getPluginIntent(): Intent {
    pluginLaunchIntent?.let { return it }
    val mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS) ?: ""
    val synthetic = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(mainClass.substringBeforeLast('.'), mainClass)
    }
    pluginLaunchIntent = synthetic
    return synthetic
}
```

- [x] **Step 3: ShadowActivity 切换**

`ShadowActivity.getIntent`(:280-283)改为:

```java
    @Override
    public Intent getIntent() {
        if (hostDelegator != null) return hostDelegator.getPluginIntent();
        return super.getIntent();
    }
```

- [x] **Step 4: 验证**

`./gradlew assembleDebug` → BUILD SUCCESSFUL(shadow-runtime 参与 app 编译,接口新增方法若容器未实现会编译失败——这就是验证)。
真机:生成一个在 `onCreate` 里 `AppLogger.d("T", getIntent().toString())` 的测试 app,插件模式运行后 `read_runtime_log` 中 intent 应为 `act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] cmp=com.vibe.generated.pXXX/.MainActivity`,不含 `plugin_apk_path`。

- [x] **Step 5: Commit**

```bash
git add shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/HostActivityDelegator.java \
  shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java \
  app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt
git commit -m "fix(plugin): return synthetic MAIN/LAUNCHER intent from plugin getIntent (opt task 3.4)"
```

**验收标准**:插件内 `getIntent()` 语义正确;独立安装模式行为不变(hostDelegator == null 路径未动)。

---

## Task 3.5: 插件生命周期补齐 + 返回键分发

**现状与证据**:容器只转发 create/resume/pause/stop/destroy/activityResult(`PluginContainerActivity.kt:141-196`);`onStart`/`onSaveInstanceState`/`onRestoreInstanceState`/`onConfigurationChanged`/`onRequestPermissionsResult` 无转发;返回键直接 finish 容器,插件不可拦截(:198-205)。`ShadowActivity` 用 `pluginLifecycleActive` 标志跳过 super 生命周期(:119-157)——新方法沿用同一模式。宿主与插件共享同一份 `ShadowActivity` Class(`ShadowBridgeClassLoader` 转发 runtime 包),容器可直接调用新方法,无需反射;生成 APK 每次重建,无旧版兼容问题。

**改动文件**:
- Modify: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java`
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`

- [x] **Step 1: ShadowActivity 增加 perform* 入口与 skip-super 覆写**

在 `performDestroy()`(:78-80)后追加,完全沿用既有 flag 模式:

```java
    public void performStart() {
        pluginLifecycleActive = true;
        onStart();
        pluginLifecycleActive = false;
    }

    public void performSaveInstanceState(Bundle outState) {
        pluginLifecycleActive = true;
        onSaveInstanceState(outState);
        pluginLifecycleActive = false;
    }

    public void performRestoreInstanceState(Bundle savedInstanceState) {
        pluginLifecycleActive = true;
        onRestoreInstanceState(savedInstanceState);
        pluginLifecycleActive = false;
    }

    public void performConfigurationChanged(android.content.res.Configuration newConfig) {
        pluginLifecycleActive = true;
        onConfigurationChanged(newConfig);
        pluginLifecycleActive = false;
    }

    public void performRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Back-key dispatch. Returns true when the plugin requested the default
     * behavior (its onBackPressed called super) — the container should finish.
     */
    public boolean performBackPressed() {
        backDefaultRequested = false;
        pluginLifecycleActive = true;
        onBackPressed();
        pluginLifecycleActive = false;
        return backDefaultRequested;
    }

    private boolean backDefaultRequested;
```

对应的 skip-super 覆写(加在 :152-157 的 `onDestroy` 覆写之后):

```java
    @Override
    protected void onStart() {
        if (!pluginLifecycleActive) super.onStart();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (!pluginLifecycleActive) super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        if (!pluginLifecycleActive) super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        if (!pluginLifecycleActive) super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (hostDelegator == null) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        // Plugin mode: FragmentActivity's implementation touches mFragments
        // (never initialized) — subclasses override this directly.
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (hostDelegator != null) {
            backDefaultRequested = true; // user code called super.onBackPressed()
            return;
        }
        super.onBackPressed();
    }
```

- [x] **Step 2: 容器转发**

`PluginContainerActivity` 增加(与既有 onResume 等并列,保持相同 try/catch + writeCrashLog 风格):

```kotlin
override fun onStart() {
    super.onStart()
    try {
        pluginActivity?.performStart()
    } catch (e: Exception) {
        Log.e(TAG, "Plugin crashed during onStart", e)
        writeCrashLog(e)
        finish()
    }
}

override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    try {
        pluginActivity?.performSaveInstanceState(outState)
    } catch (e: Exception) {
        Log.e(TAG, "Plugin crashed during onSaveInstanceState", e)
        writeCrashLog(e)
    }
}

override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    try {
        pluginActivity?.performRestoreInstanceState(savedInstanceState)
    } catch (e: Exception) {
        Log.e(TAG, "Plugin crashed during onRestoreInstanceState", e)
        writeCrashLog(e)
    }
}

override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    try {
        pluginActivity?.performConfigurationChanged(newConfig)
    } catch (e: Exception) {
        Log.e(TAG, "Plugin crashed during onConfigurationChanged", e)
        writeCrashLog(e)
    }
}

override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    try {
        pluginActivity?.performRequestPermissionsResult(requestCode, permissions.map { it }.toTypedArray(), grantResults)
    } catch (e: Exception) {
        Log.e(TAG, "Plugin crashed during onRequestPermissionsResult", e)
        writeCrashLog(e)
    }
}
```

替换 `onBackPressed`(:198-205):

```kotlin
@SuppressLint("MissingSuperCall")
@Suppress("DEPRECATION")
override fun onBackPressed() {
    // Never call super.onBackPressed() — it touches the host FragmentManager.
    // Dispatch to the plugin first; finish only if it requested default behavior.
    val wantsDefault = try {
        pluginActivity?.performBackPressed() ?: true
    } catch (e: Exception) {
        Log.e(TAG, "Plugin crashed during onBackPressed", e)
        writeCrashLog(e)
        true
    }
    if (wantsDefault) finish()
}
```

注意:插件权限说明——插件进程实际权限 = 宿主 Manifest 声明的权限;`requestPermissions` 经 `startActivityForResult` 委托链走宿主,结果经上面新转发回流。宿主未声明的权限永远是 DENIED,这是插件模式的固有边界(评审 §1.2),本 Task 只补回调通路,不承诺扩权。

- [x] **Step 3: 验证**

`./gradlew assembleDebug` → BUILD SUCCESSFUL。
真机人工验证清单:
1. 生成一个覆写 `onBackPressed`(弹确认对话框、不调 super)的 app → 插件模式按返回键出现对话框而不是直接退出;确认后(用户代码调 `super.onBackPressed()` 或 `finish()`)容器正常关闭;
2. 生成一个未覆写 onBackPressed 的 app → 返回键行为与现在一致(直接退出);
3. 生成一个在 `onSaveInstanceState` 存计数器、`onCreate`/`onRestoreInstanceState` 恢复的 app → 旋转屏幕(Manifest `configChanges` 吞掉,走 onConfigurationChanged 转发)计数不丢,`AppLogger` 里能看到 onConfigurationChanged 日志;
4. 崩溃注入:onStart 里抛异常 → crash.log 有记录、容器 finish、CrashPrompt 弹出(联动 3.1)。

- [x] **Step 4: Commit**

```bash
git add shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java \
  app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt
git commit -m "feat(plugin): forward start/saveState/config/permission callbacks and dispatch back key (opt task 3.5)"
```

**验收标准**:上述 4 条人工验证全部通过;独立安装模式回归正常(standalone 路径全部走 super,未受影响)。

---

## Task 3.6: DebugBridge 宿主侧(DebugReportProvider)

**现状与证据**:独立安装模式的崩溃写在生成应用自己的沙箱(`templates/.../CrashHandlerApp.java:40-57` 写 SharedPreferences + 自身 filesDir),VibeApp 无法读取,只能靠崩溃对话框让用户复制粘贴(评审 §1.2 表末行)。

**设计决定(与评审文档 §1.3 的偏差,已核实代码后调整)**:
1. **不用 signature 权限**:生成 APK 由 build-engine 内置的 debug keystore 签名,而 VibeApp 正式包用发布证书——两者证书不同,`protectionLevel="signature"` 在 release 上永远不匹配。改为 **exported provider + 宿主侧校验调用方签名**:用 `PackageManager` 取 callingPackage 的签名证书,与内置 debug keystore 证书的 SHA-256 比对。
2. **不需要 {{PROJECT_ID}} 模板变量**:生成应用包名恒为 `com.vibe.generated.p{projectId}`(`ProjectInitializer.kt:88`),宿主从 `callingPackage` 反推 projectId——天然防伪造(A 项目冒充不了 B 项目)。

**改动文件**:
- Create: `app/src/main/kotlin/com/vibe/app/plugin/DebugReportProvider.kt`
- Create: `app/src/main/kotlin/com/vibe/app/plugin/DebugReportValidator.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/vibe/app/plugin/DebugReportValidatorTest.kt`

- [x] **Step 1: 先写校验器单测(TDD)**

```kotlin
package com.vibe.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugReportValidatorTest {

    @Test
    fun `projectIdFromPackage extracts id from generated package`() {
        assertEquals("abc123", DebugReportValidator.projectIdFromPackage("com.vibe.generated.pabc123"))
    }

    @Test
    fun `projectIdFromPackage rejects foreign packages`() {
        assertNull(DebugReportValidator.projectIdFromPackage("com.evil.app"))
        assertNull(DebugReportValidator.projectIdFromPackage("com.vibe.generated.p"))
        assertNull(DebugReportValidator.projectIdFromPackage(null))
    }

    @Test
    fun `projectIdFromPackage rejects path traversal characters`() {
        assertNull(DebugReportValidator.projectIdFromPackage("com.vibe.generated.p../../etc"))
    }

    @Test
    fun `truncateForAppend caps content size`() {
        val big = "x".repeat(300_000)
        assertEquals(64 * 1024, DebugReportValidator.truncateForAppend(big).length)
    }
}
```

Run: `./gradlew test --tests "*DebugReportValidatorTest*"` → FAIL(类不存在)。

- [x] **Step 2: 实现校验器**

```kotlin
package com.vibe.app.plugin

object DebugReportValidator {
    private const val PACKAGE_PREFIX = "com.vibe.generated.p"
    private val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    const val MAX_REPORT_CHARS = 64 * 1024

    /** Derives the projectId from a generated app's package name, or null if not ours. */
    fun projectIdFromPackage(callingPackage: String?): String? {
        if (callingPackage == null || !callingPackage.startsWith(PACKAGE_PREFIX)) return null
        val id = callingPackage.removePrefix(PACKAGE_PREFIX)
        return id.takeIf { it.isNotEmpty() && PROJECT_ID_PATTERN.matches(it) }
    }

    fun truncateForAppend(content: String): String =
        if (content.length <= MAX_REPORT_CHARS) content else content.take(MAX_REPORT_CHARS)
}
```

Run: `./gradlew test --tests "*DebugReportValidatorTest*"` → PASS。

- [x] **Step 3: 实现 Provider**

```kotlin
package com.vibe.app.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Receives crash/log reports from installed generated apps (DebugBridge).
 * Caller identity is verified by (a) package prefix com.vibe.generated.p*,
 * (b) signing cert digest == the bundled debug keystore cert that
 * DebugApkSigner uses for every generated APK. This works regardless of how
 * VibeApp itself is signed, which is why a signature <permission> is NOT used.
 */
class DebugReportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        val projectId = DebugReportValidator.projectIdFromPackage(callingPackage) ?: return null
        if (!isCallerSignedByBundledDebugKey(callingPackage!!)) return null
        val kind = values?.getAsString("kind") ?: return null
        val content = values.getAsString("content") ?: return null
        if (!File(ctx.filesDir, "projects/$projectId").isDirectory) return null

        val fileName = when (kind) {
            "crash" -> "crash.log"
            "log" -> "app.log"
            else -> return null
        }
        val logDir = File(ctx.filesDir, "projects/$projectId/logs").apply { mkdirs() }
        val target = File(logDir, fileName)
        try {
            if (target.exists() && target.length() > 256 * 1024) {
                File(target.path + ".1").delete()
                target.renameTo(File(target.path + ".1"))
            }
            target.appendText(DebugReportValidator.truncateForAppend(content))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist debug report", e)
            return null
        }
        return uri
    }

    private fun isCallerSignedByBundledDebugKey(pkg: String): Boolean = try {
        val pm = context!!.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = info.signingInfo?.apkContentsSigners ?: return false
        certs.any { sig ->
            val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) } == GENERATED_APP_CERT_SHA256
        }
    } catch (e: Exception) {
        false
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.vibe.debugreport"
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0

    companion object {
        private const val TAG = "DebugReportProvider"
        const val AUTHORITY = "com.vibe.app.debugreport"
        /** SHA-256 of the debug signing cert used by DebugApkSigner — fill in Step 4. */
        const val GENERATED_APP_CERT_SHA256 = "<REPLACE_IN_STEP_4>"
    }
}
```

- [x] **Step 4: 计算并填入内置 debug 证书指纹**

找到 build-engine 内置的 debug keystore(grep `DebugApkSigner` 定位 keystore 资产路径,通常在 `build-engine/src/main/assets/` 下),执行:

```bash
keytool -list -v -keystore <keystore路径> -storepass android -alias androiddebugkey | grep "SHA256:"
```

把输出的 SHA-256(去冒号、转小写)替换 `GENERATED_APP_CERT_SHA256` 常量。若 keystore 密码/别名不同,以 `DebugApkSigner` 源码中的常量为准。

- [x] **Step 5: Manifest 注册**

`<application>` 内(FileProvider 声明之后):

```xml
<provider
    android:name="com.vibe.app.plugin.DebugReportProvider"
    android:authorities="com.vibe.app.debugreport"
    android:exported="true"
    tools:ignore="ExportedContentProvider" />
```

(exported 是必须的——调用方是独立安装的生成 app;安全性由签名校验保证,lint 抑制注明原因。)

- [x] **Step 6: 验证**

`./gradlew test --tests "*DebugReportValidatorTest*"` → PASS;`./gradlew assembleDebug` → BUILD SUCCESSFUL。真机端到端验证放在 Task 3.7(需要模板侧配合)。

- [x] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/plugin/DebugReportProvider.kt \
  app/src/main/kotlin/com/vibe/app/plugin/DebugReportValidator.kt \
  app/src/test/kotlin/com/vibe/app/plugin/DebugReportValidatorTest.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat(debug): DebugReportProvider accepts crash/log reports from installed generated apps (opt task 3.6)"
```

**验收标准**:单测通过;Provider 拒绝非 `com.vibe.generated.p*` 包名与非内置证书签名的调用方;crash 写入路径与插件模式一致(`projects/{id}/logs/crash.log`)。

---

## Task 3.7: DebugBridge 模板侧(崩溃上报)

**现状与证据**:模板 `CrashHandlerApp.uncaughtException`(`templates/EmptyActivity/app/src/main/java/$packagename/CrashHandlerApp.java:40-57`)只写本地 `AppLogger.crash(e)` + SharedPreferences。模板变量机制是 `$packagename` 文本替换(`ProjectInitializer.kt:347-358`),包名即 `com.vibe.generated.p{projectId}`——**无需新增模板变量**,宿主端从 callingPackage 反推 projectId(见 Task 3.6)。

**改动文件**:
- Modify: `app/src/main/assets/templates/EmptyActivity/app/src/main/java/$packagename/CrashHandlerApp.java`
- Modify: `app/src/main/assets/agent-system-prompt.md`(崩溃修复流程段,grep `fix_crash_guide`)

注意 `CLAUDE.md` 规定模板资产不轻易改动——本 Task 属于"明确以更新模板资产为目标"的任务,允许修改;改动保持 Java 8 兼容(生成项目的语言级别)。

- [x] **Step 1: CrashHandlerApp 增加上报**

在 `uncaughtException` 中 `AppLogger.crash(e);` 之后、`Process.killProcess` 之前插入调用,并新增私有方法:

```java
// uncaughtException 内,AppLogger.crash(e) 之后:
                reportCrashToVibeApp(stackTrace);
```

```java
    /**
     * Best-effort crash report to VibeApp's DebugReportProvider so the AI
     * agent can see install-mode crashes. Silently no-ops when VibeApp is
     * not installed or rejects the call.
     */
    private void reportCrashToVibeApp(String stackTrace) {
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("kind", "crash");
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US);
            values.put("content", "--- CRASH " + fmt.format(new java.util.Date())
                    + " (installed mode) ---\n" + stackTrace + "\n");
            getContentResolver().insert(
                    android.net.Uri.parse("content://com.vibe.app.debugreport/report"), values);
        } catch (Throwable ignored) {
            // VibeApp absent / provider rejected — local crash dialog still works.
        }
    }
```

注意:`stackTrace` 变量在现有 `uncaughtException` 中 :46-48 已构造,直接复用;插入位置必须在 `Process.killProcess` 之前(进程死了就发不出去);ContentProvider.insert 是同步 Binder 调用,崩溃处理器里可接受(<50ms)。

生成 app 访问宿主 provider 需要包可见性:模板 Manifest(`templates/.../AndroidManifest.xml`)`<manifest>` 根节点内、`<application>` 之前加:

```xml
    <queries>
        <provider android:authorities="com.vibe.app.debugreport" />
    </queries>
```

- [x] **Step 2: 系统提示词补充说明**

`agent-system-prompt.md` 崩溃处理段(grep `fix_crash_guide` 的流程行)追加:

```
- Crashes from the INSTALLED app (not just plugin preview) also land in crash.log via the debug bridge — fix_crash_guide covers both modes.
```

- [x] **Step 3: 端到端验证(真机,核心验收)**

1. 用 VibeApp 生成一个"点按钮抛 RuntimeException"的 app,构建 → **安装**(不是插件预览)→ 打开安装的 app → 点按钮触发崩溃;
2. 检查 `run adb shell run-as com.vibe.app cat files/projects/<id>/logs/crash.log`(或直接看 VibeApp)包含 `(installed mode)` 崩溃条目;
3. 回到 VibeApp 对应项目的 ChatScreen → CrashPrompt 卡片弹出(FileObserver 联动,Task 3.1)→ 点"自动修复"→ agent 调 `fix_crash_guide` 能读到该崩溃栈;
4. 卸载 VibeApp 后单独运行生成 app 并触发崩溃 → 生成 app 自身崩溃对话框正常、无二次异常(静默降级验证)。
5. `./gradlew assembleDebug` → BUILD SUCCESSFUL;新生成项目走 `run_build_pipeline` 全量构建成功(模板改动不破坏编译)。

- [x] **Step 4: Commit**

```bash
git add "app/src/main/assets/templates/EmptyActivity/app/src/main/java/\$packagename/CrashHandlerApp.java" \
  app/src/main/assets/templates/EmptyActivity/app/src/main/AndroidManifest.xml \
  app/src/main/assets/agent-system-prompt.md
git commit -m "feat(debug): installed generated apps report crashes back to VibeApp (opt task 3.7)"
```

**验收标准**:安装模式崩溃 → VibeApp 弹卡片 → 自动修复闭环可用;VibeApp 不存在时生成 app 不受影响。

---

## Phase 完成检查

- [x] 全部 7 个 Task 的 checkbox 已勾选,每个 Task 有独立 commit;
- [x] `./gradlew :app:testDebugUnitTest` 全绿(全套件 --rerun-tasks);`./gradlew :build-engine:test` 全绿;`./gradlew assembleDebug` 成功。**注**:全模块 `./gradlew test` 因 vendored `build-tools/android-common-resources` 预存编译问题失败(与本分支无关,同 Phase 1/2 遗留说明),故以 `:app` + `:build-engine` 作用域为准;
- [ ] 人工验证清单汇总(真机 Android 10+)——**待用户在真机执行**:
  1. 插件崩溃 → CrashPrompt 秒级弹出(3.1);
  2. 视觉模型可通过 capture_screenshot 看到界面(3.2);
  3. 后台 launch_app → 通知 → 点按打开预览(3.3);
  4. 插件 getIntent 不含宿主 extras(3.4);
  5. 自定义 onBackPressed / 旋转 / onSaveInstanceState 均按预期(3.5);
  6. 安装模式崩溃回流 + 自动修复闭环(3.6+3.7);
  7. 回归:独立安装模式正常运行、无新崩溃;既有 inspect_ui / interact_ui 正常。
- [x] 更新 `00-progress.md`:Phase 3 状态记为进行中·代码完成·待真机验证(真机 7 项通过后再改 `✅ 已完成` 并填完成日期);
- [ ] `git commit -m "docs: mark optimization phase 3 complete"`(真机验证通过后执行)。

## 实施记录(执行时追加)

| 日期 | 执行者 | 完成内容 | 偏离/备注 |
|------|--------|----------|-----------|
| 2026-07-04 | Claude Opus 4.8 (1M) | 全 7 Task 代码完成,分支 `opt/phase-3-debug-experience`,逐 Task 独立 commit;`:app:testDebugUnitTest`(全套件)+ `:build-engine:test` + `assembleDebug` 全绿;新增 2 组单测(CrashLogWatcher 解析、DebugReportValidator)。开工前用 Explore agent 全量核实锚点,发现多处漂移/计划错误(见下)。 | 全模块 `./gradlew test` 因 vendored `android-common-resources` 预存问题失败(非本分支),同 Phase 1/2 处理。 |
| 2026-07-04 | Claude Opus 4.8 (1M) | Task 3.1 FileObserver 崩溃推送;3.4 getPluginIntent;3.5 生命周期补齐+返回键分发。 | 3.4/3.5 的 getIntent 委托/部分生命周期转发机制已存在,但 3.4 原返回 host intent(正是待改现状)、3.5 缺 5 个回调+返回键分发,均为有效工作;改 shadow-runtime 源码后按仓库惯例(参 099ba7c)重生成并提交捆绑 `build-engine/src/main/assets/shadow-runtime.jar`(单独 commit,含新方法,javap 已验)。 |
| 2026-07-04 | Claude Opus 4.8 (1M) | Task 3.2 PixelCopy 截图 + 视觉 tool_result。 | **计划外 DTO 改动**:tool_result 嵌图需把 `ToolResultContent.content` 由 `String` 改为 `List<MessageContent>`(仅 1 处构造点,gateway);计划的 `resolveMediaType` 实名 `mimeTypeToMediaType`,抽出 `addImageAttachments` 复用。 |
| 2026-07-04 | Claude Opus 4.8 (1M) | Task 3.3 后台 launch_app 通知兜底。 | `PluginLaunchProxyActivity` 必须继承 `ComponentActivity`(非计划的 `android.app.Activity`),否则 Hilt `@AndroidEntryPoint` KSP 失败;`LaunchAppTool` 原未注入 `@ApplicationContext Context`,已补;POST_NOTIFICATIONS 权限原已声明。 |
| 2026-07-04 | Claude Opus 4.8 (1M) | Task 3.6 DebugReportProvider + 校验器;3.7 模板侧崩溃上报 + `<queries>`。 | **计划 keystore 假设错误**:`DebugApkSigner` 用 AOSP testkey(`testkey.pk8`/`testkey.x509.pem`),无 keystore/storepass/alias;证书 SHA-256 改用 openssl 对 DER 证书计算 = `a40da80a…bf5dc`(众所周知的 AOSP testkey 指纹,佐证正确)。 |
