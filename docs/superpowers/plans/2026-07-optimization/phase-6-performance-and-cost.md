# Phase 6: 性能与成本 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读 `00-progress.md`,确认前置状态与本 phase 当前进度;
> 2. 本文 `file:line` 基于 `dev@be1f944`,动手前用 grep 重新定位,不要盲信行号;
> 3. 每完成一个 Task:勾选本文 checkbox、跑该 Task"验证"节命令、独立 commit、更新 `00-progress.md` 状态表"当前位置";
> 4. 偏离计划必须在文末"实施记录"表中说明,禁止静默偏离;
> 5. 建议用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 执行。

**目标:** 削减 agent loop 的 API 成本(Anthropic prompt caching)、削减端上构建耗时(R.class 缓存)、消除 Room 大字段与全表扫描的性能/崩溃风险、修复会话状态只在结束时落库的问题。

**评审依据:** `docs/optimization-review-2026-07.md` §4-8(增量编译)、§4-9(prompt caching)、§4-10(Room)、§4-13(会话落库)。

**前置依赖:** Task 6.3 的"thoughts 外置"步骤依赖 Phase 4.3(TurnArtifact 结构化持久化)完成后收益最大,但**不硬依赖**——可独立实施;其余 Task 无前置。

**涉及模块:** `app/data/dto/anthropic`、`app/feature/agent/loop`、`build-engine/compiler`、`app/data/database`、`app/data/repository`、`app/feature/agent/service`。

---

## Task 6.1: Anthropic prompt caching

**现状与证据:**
- 全库无 `cache_control`(grep 为空)。Agent loop 每次迭代(一轮最多 30 次)全量重发 ~12.6KB 系统提示 + 全部工具 schema + 全部历史,重复计费重复 prefill。
- `MessageRequest.systemPrompt` 是**纯字符串**(`MessageRequest.kt:37-39`,`@SerialName("system")`),而 cache_control 断点要求 system 是 content block 数组。
- `AnthropicTool`(`ToolRequest.kt:14-24`)无 cacheControl 字段。
- `Usage` DTO **已有** `cache_creation_input_tokens` / `cache_read_input_tokens` 字段(`Usage.kt:12-16`),gateway 在 `message_start` 已把三者求和记入 trace(`AnthropicMessagesAgentGateway.kt:120-126`),但没有单独记录命中量,无法验证缓存生效。
- 输出上限硬编码 `DEFAULT_MAX_TOKENS = 16000`(`AnthropicMessagesAgentGateway.kt:86,300`)。
- **序列化陷阱:** `AnthropicAPIImpl` 的 Json 配置是 `encodeDefaults = false`(`AnthropicAPIImpl.kt:40`),`CacheControl(type = "ephemeral")` 的默认值字段会被丢弃,必须用 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/data/dto/anthropic/common/CacheControl.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/data/dto/anthropic/request/MessageRequest.kt`(systemPrompt 改块数组)
- Modify: `app/src/main/kotlin/com/vibe/app/data/dto/anthropic/request/ToolRequest.kt`(AnthropicTool 加 cacheControl)
- Modify: `app/src/main/kotlin/com/vibe/app/data/dto/anthropic/common/TextContent.kt`、`ToolResultContent.kt`(可选 cacheControl)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt`
- Test: `app/src/test/kotlin/com/vibe/app/data/dto/anthropic/CacheControlSerializationTest.kt`

**接口契约(供其他 Task/Phase 使用):**
- `CacheControl(type: String = "ephemeral")` — 唯一的 cache_control DTO,后续如需 1h TTL 扩展在此加字段。
- `MessageRequest.systemPrompt` 类型变为 `List<SystemTextBlock>?`(SerialName 仍为 `system`)。全库唯一构造点在 AnthropicMessagesAgentGateway(已 grep 确认),无其他调用方需要迁移。

- [ ] **Step 1: 新建 CacheControl 与 SystemTextBlock DTO**

```kotlin
// app/src/main/kotlin/com/vibe/app/data/dto/anthropic/common/CacheControl.kt
package com.vibe.app.data.dto.anthropic.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CacheControl(
    @SerialName("type")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "ephemeral",
)

/** System prompt 的 content block 形式,用于挂 cache_control 断点。 */
@Serializable
data class SystemTextBlock(
    @SerialName("type")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "text",

    @SerialName("text")
    val text: String,

    @SerialName("cache_control")
    val cacheControl: CacheControl? = null,
)
```

注意 `SystemTextBlock` 也要 `@OptIn(ExperimentalSerializationApi::class)`(与文件内 CacheControl 共用一个 OptIn 即可)。

- [ ] **Step 2: MessageRequest.systemPrompt 改为块数组;AnthropicTool / TextContent / ToolResultContent 加 cacheControl**

`MessageRequest.kt:37-39` 改为:

```kotlin
    @SerialName("system")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val systemPrompt: List<SystemTextBlock>? = null,
```

`ToolRequest.kt` 的 `AnthropicTool` 尾部加字段:

```kotlin
    @SerialName("cache_control")
    val cacheControl: CacheControl? = null,
```

`TextContent.kt` 与 `ToolResultContent.kt` 各加同样的可空 `cacheControl` 字段(默认 null,`explicitNulls = false` 下不影响既有序列化输出)。

- [ ] **Step 3: 写序列化快照测试(先写,预期失败=编译不过,实现 Step 1/2 后转绿)**

```kotlin
// app/src/test/kotlin/com/vibe/app/data/dto/anthropic/CacheControlSerializationTest.kt
package com.vibe.app.data.dto.anthropic

import com.vibe.app.data.dto.anthropic.common.CacheControl
import com.vibe.app.data.dto.anthropic.common.SystemTextBlock
import com.vibe.app.data.dto.anthropic.request.MessageRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheControlSerializationTest {

    // 与 AnthropicAPIImpl.kt:37-42 完全一致的配置,防止 encodeDefaults=false 吞掉 type 字段
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `cache_control type survives encodeDefaults=false`() {
        val encoded = json.encodeToJsonElement(CacheControl()).toString()
        assertEquals("""{"type":"ephemeral"}""", encoded)
    }

    @Test
    fun `system block array serializes with breakpoint on last block`() {
        val request = MessageRequest(
            model = "claude-sonnet-5",
            messages = emptyList(),
            maxTokens = 16000,
            systemPrompt = listOf(
                SystemTextBlock(text = "instructions", cacheControl = CacheControl()),
            ),
        )
        val encoded = json.encodeToJsonElement(request).toString()
        assertTrue(encoded.contains(""""system":[{"type":"text","text":"instructions","cache_control":{"type":"ephemeral"}}]"""))
    }
}
```

运行:`./gradlew :app:testDebugUnitTest --tests "com.vibe.app.data.dto.anthropic.CacheControlSerializationTest"`,预期 PASS。

- [ ] **Step 4: gateway 打三类断点**

`AnthropicMessagesAgentGateway.kt:83-93` 的请求构造改为:

```kotlin
        val tools = request.tools
            .takeIf { it.isNotEmpty() }
            ?.mapIndexed { index, it ->
                AnthropicTool(
                    name = it.name,
                    description = it.description,
                    inputSchema = it.inputSchema,
                    // 断点 1:工具表尾部。工具集在整轮 loop 内不变,首次迭代后全量命中。
                    cacheControl = CacheControl().takeIf { _ -> index == request.tools.lastIndex },
                )
            }

        val messageRequest = MessageRequest(
            model = request.platform.model,
            messages = markConversationBreakpoint(messages),
            maxTokens = DEFAULT_MAX_TOKENS,
            stream = true,
            // 断点 2:system 尾部。
            systemPrompt = request.instructions?.let {
                listOf(SystemTextBlock(text = it, cacheControl = CacheControl()))
            },
            temperature = request.platform.temperature,
            topP = request.platform.topP,
            tools = tools,
            toolChoice = tools?.let { buildToolChoice(request.policy.toolChoiceMode) },
        )
```

新增私有方法(断点 3:对话尾部的**移动断点**——每次迭代把断点打在当前最后一个 content block 上,下一次迭代的公共前缀恰好覆盖到这里,实现历史增量缓存):

```kotlin
    /**
     * Marks the last content block of the last message with cache_control so the
     * next iteration's shared prefix (which extends past this point) hits cache.
     * Anthropic allows max 4 breakpoints per request: system + tools + this = 3.
     */
    private fun markConversationBreakpoint(messages: List<InputMessage>): List<InputMessage> {
        val last = messages.lastOrNull() ?: return messages
        val lastBlock = last.content.lastOrNull() ?: return messages
        val marked = when (lastBlock) {
            is TextContent -> lastBlock.copy(cacheControl = CacheControl())
            is ToolResultContent -> lastBlock.copy(cacheControl = CacheControl())
            else -> return messages // image/tool_use 结尾的少见情况,放弃本次断点
        }
        return messages.dropLast(1) + last.copy(content = last.content.dropLast(1) + marked)
    }
```

注意:`InputMessage.content` 若是不可变 List 的 data class 直接 `copy`;若不是 data class,先改成 data class(读文件确认)。

- [ ] **Step 5: 记录缓存命中量以便验证**

`AnthropicMessagesAgentGateway.kt:120-126` 的 `MessageStartResponseChunk` 分支追加日志(不改 trace 结构,避免扩散):

```kotlin
                is MessageStartResponseChunk -> {
                    val u = chunk.message.usage
                    trace.markInputTokens(
                        u.inputTokens + (u.cacheCreationInputTokens ?: 0) + (u.cacheReadInputTokens ?: 0),
                    )
                    android.util.Log.d(
                        "AnthropicGateway",
                        "usage: input=${u.inputTokens} cacheRead=${u.cacheReadInputTokens} cacheWrite=${u.cacheCreationInputTokens}",
                    )
                }
```

- [ ] **Step 6: maxTokens 配置化(不阻塞于 Phase 4)**

若 Phase 4.5 已给 `PlatformV2` 加了 `maxOutputTokens`:改为 `maxTokens = request.platform.maxOutputTokens ?: DEFAULT_MAX_TOKENS`。
若 Phase 4 未完成:把 `DEFAULT_MAX_TOKENS` 从 gateway companion(:299-301)挪到 `ModelConstants`(`app/src/main/kotlin/com/vibe/app/data/ModelConstants.kt`)成 `const val ANTHROPIC_MAX_OUTPUT_TOKENS = 16000`,gateway 引用之,并在"实施记录"注明待 Phase 4.5 接管。

- [ ] **Step 7: 验证**

1. `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.data.dto.anthropic.*"` → PASS;
2. `./gradlew assembleDebug` → BUILD SUCCESSFUL;
3. 真机配置 Anthropic key,发起一个需要多次工具调用的任务(如"做一个记账 app"),`adb logcat -s AnthropicGateway`:第 1 次迭代 `cacheWrite > 0`,第 2 次迭代起 `cacheRead > 0` 且约等于上次的 input 总量的大头。MiniMax 走同一 gateway(`ProviderAgentGatewayRouter.kt:36`),若其 Anthropic 兼容端点不认 `cache_control` 需回归验证一次 MiniMax 正常出词(cache_control 是增量字段,标准 Anthropic 兼容实现会忽略;若 MiniMax 报 4xx,在 gateway 按 `request.platform.compatibleType == ClientType.ANTHROPIC` 门控断点,并记入实施记录)。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/dto/anthropic app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt app/src/test/kotlin/com/vibe/app/data/dto/anthropic
git commit -m "feat(agent): add Anthropic prompt caching breakpoints (opt task 6.1)"
```

**验收标准:** 序列化测试通过;真机上第二次迭代 `cacheRead > 0`;Anthropic 与 MiniMax 均正常完成 agent 任务。

---

## Task 6.2: RClassCache — R.java 编译产物缓存

**现状与证据:**
- 每次构建 `classesDir` 被整体删除(`JavacCompiler.kt:42-45`),~14 个巨型 R.java(AAPT2 `--extra-packages` 生成,`Aapt2ResourceCompiler.kt:102`)按每批 3 个全量重编,批间 `System.gc()`(`JavacCompiler.kt:58-66`,`R_JAVA_BATCH_SIZE=3` :177)——这是低内存设备上单次构建的最大开销项,而资源不变时 R.java 内容逐字节相同。
- `BuildTool` 默认 `clean=true` 每次清 build 缓存(`BuildTool.kt:39-43`),所以缓存必须放在**项目 build 目录之外**。
- 现成模式:`PreDexCache`(`PreDexCache.kt`)——`object` + 内容 hash + CACHE_VERSION + 临时目录原子替换,缓存根在 `context.filesDir/predex-cache`。本 Task 完全仿照。
- 可测试性:`PreDexCache` 直接用 `android.util.Log`/`Context`,没有单测。为了 TDD,把核心逻辑拆成不依赖 Android 的 `RClassCacheCore`(纯 File + 函数注入日志),薄壳 `RClassCache` 负责 Context/Log。build-engine 已有 JUnit 基建(`build-engine/src/test/java/com/vibe/build/engine/ExampleUnitTest.kt`)。

**改动文件:**
- Create: `build-engine/src/main/java/com/vibe/build/engine/compiler/RClassCacheCore.kt`
- Create: `build-engine/src/main/java/com/vibe/build/engine/compiler/RClassCache.kt`
- Modify: `build-engine/src/main/java/com/vibe/build/engine/compiler/JavacCompiler.kt`(:58-66 附近)
- Test: `build-engine/src/test/java/com/vibe/build/engine/compiler/RClassCacheCoreTest.kt`

**接口契约:**
- `RClassCacheCore(cacheRoot: File, maxEntries: Int = 8, log: (String) -> Unit = {})`
  - `fun computeKey(rJavaFiles: List<File>): String` — SHA-256(排序后的相对文件名 + 内容 + CACHE_VERSION)
  - `fun restore(key: String, into: File): Boolean` — 命中则把缓存的 .class 树复制进 `into`,并 touch LRU 标记
  - `fun store(key: String, classesDir: File)` — 把 `classesDir` 下所有 `R.class`/`R$*.class` 拷入缓存并做 LRU 裁剪
- `RClassCache.getOrNull(context) / JavacCompiler` 集成点见 Step 4。

- [ ] **Step 1: 写失败测试**

```kotlin
// build-engine/src/test/java/com/vibe/build/engine/compiler/RClassCacheCoreTest.kt
package com.vibe.build.engine.compiler

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RClassCacheCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rJava(name: String, content: String): File =
        tmp.newFolder().resolve(name).apply { writeText(content) }

    private fun fakeClassesDir(): File {
        val dir = tmp.newFolder("classes")
        File(dir, "com/pkg").mkdirs()
        File(dir, "com/pkg/R.class").writeBytes(byteArrayOf(1, 2))
        File(dir, "com/pkg/R\$layout.class").writeBytes(byteArrayOf(3, 4))
        File(dir, "com/pkg/MainActivity.class").writeBytes(byteArrayOf(9, 9)) // 非 R 类,不应入缓存
        return dir
    }

    @Test
    fun `same content yields same key, different content different key`() {
        val core = RClassCacheCore(tmp.newFolder("cache"))
        val a = listOf(rJava("R.java", "class R { int x = 1; }"))
        val b = listOf(rJava("R.java", "class R { int x = 1; }"))
        val c = listOf(rJava("R.java", "class R { int x = 2; }"))
        assertEquals(core.computeKey(a), core.computeKey(b))
        assertFalse(core.computeKey(a) == core.computeKey(c))
    }

    @Test
    fun `store then restore copies only R classes`() {
        val core = RClassCacheCore(tmp.newFolder("cache"))
        val key = "abc123"
        core.store(key, fakeClassesDir())

        val target = tmp.newFolder("restored")
        assertTrue(core.restore(key, target))
        assertTrue(File(target, "com/pkg/R.class").exists())
        assertTrue(File(target, "com/pkg/R\$layout.class").exists())
        assertFalse(File(target, "com/pkg/MainActivity.class").exists())
    }

    @Test
    fun `restore misses for unknown key`() {
        val core = RClassCacheCore(tmp.newFolder("cache"))
        assertFalse(core.restore("nope", tmp.newFolder("t")))
    }

    @Test
    fun `lru prunes to maxEntries keeping most recently used`() {
        val core = RClassCacheCore(tmp.newFolder("cache"), maxEntries = 2)
        core.store("k1", fakeClassesDir())
        Thread.sleep(10)
        core.store("k2", fakeClassesDir())
        Thread.sleep(10)
        core.restore("k1", tmp.newFolder("a")) // k1 变为最新
        Thread.sleep(10)
        core.store("k3", fakeClassesDir())     // 触发裁剪,应驱逐 k2
        assertTrue(core.restore("k1", tmp.newFolder("b")))
        assertFalse(core.restore("k2", tmp.newFolder("c")))
        assertTrue(core.restore("k3", tmp.newFolder("d")))
    }
}
```

运行:`./gradlew :build-engine:test --tests "com.vibe.build.engine.compiler.RClassCacheCoreTest"`,预期 FAIL(类不存在)。

- [ ] **Step 2: 实现 RClassCacheCore**

```kotlin
// build-engine/src/main/java/com/vibe/build/engine/compiler/RClassCacheCore.kt
package com.vibe.build.engine.compiler

import java.io.File
import java.security.MessageDigest

/**
 * Pure-JVM core of the R.class cache. Keyed by SHA-256 over the generated
 * R.java contents; caches the compiled R*.class tree so unchanged resources
 * skip the most expensive javac phase (see JavacCompiler R.java batching).
 */
class RClassCacheCore(
    private val cacheRoot: File,
    private val maxEntries: Int = 8,
    private val log: (String) -> Unit = {},
) {
    companion object {
        // Bump when key semantics or stored layout changes.
        private const val CACHE_VERSION = 1
        private const val LAST_USED_FILE = ".last-used"
    }

    fun computeKey(rJavaFiles: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("v$CACHE_VERSION".toByteArray())
        rJavaFiles.sortedBy { it.name }.forEach { file ->
            digest.update(file.name.toByteArray())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun restore(key: String, into: File): Boolean {
        val entry = File(cacheRoot, key)
        val classes = File(entry, "classes")
        if (!classes.isDirectory || classes.walkTopDown().none { it.isFile }) return false
        classes.copyRecursively(into, overwrite = true)
        File(entry, LAST_USED_FILE).writeText(System.currentTimeMillis().toString())
        log("RClassCache hit: $key")
        return true
    }

    fun store(key: String, classesDir: File) {
        val entry = File(cacheRoot, key)
        val tempDir = File(cacheRoot, "$key.tmp")
        tempDir.deleteRecursively()
        val classes = File(tempDir, "classes")
        var copied = 0
        classesDir.walkTopDown().filter { it.isFile && isRClass(it.name) }.forEach { file ->
            val rel = file.relativeTo(classesDir).path
            val dest = File(classes, rel)
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
            copied++
        }
        if (copied == 0) {
            tempDir.deleteRecursively()
            return
        }
        entry.deleteRecursively()
        tempDir.renameTo(entry)
        File(entry, LAST_USED_FILE).writeText(System.currentTimeMillis().toString())
        log("RClassCache store: $key ($copied classes)")
        prune()
    }

    private fun isRClass(name: String): Boolean = name == "R.class" || name.startsWith("R\$")

    private fun prune() {
        val entries = cacheRoot.listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") } ?: return
        if (entries.size <= maxEntries) return
        entries
            .sortedBy { File(it, LAST_USED_FILE).takeIf { f -> f.exists() }?.readText()?.toLongOrNull() ?: 0L }
            .take(entries.size - maxEntries)
            .forEach {
                log("RClassCache evict: ${it.name}")
                it.deleteRecursively()
            }
    }
}
```

- [ ] **Step 3: 跑测试转绿**

`./gradlew :build-engine:test --tests "com.vibe.build.engine.compiler.RClassCacheCoreTest"` → PASS。

- [ ] **Step 4: 薄壳 + JavacCompiler 集成**

```kotlin
// build-engine/src/main/java/com/vibe/build/engine/compiler/RClassCache.kt
package com.vibe.build.engine.compiler

import android.content.Context
import android.util.Log
import java.io.File

/** Android-facing wrapper. Cache lives OUTSIDE project build dirs so BuildTool's clean=true cannot wipe it. */
object RClassCache {
    private const val CACHE_DIR_NAME = "rclass-cache"

    fun core(context: Context): RClassCacheCore =
        RClassCacheCore(File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }) { Log.d("RClassCache", it) }
}
```

`JavacCompiler.execute`(:58-66)的 R.java 批编译段改为:

```kotlin
        if (rJavaFiles.isNotEmpty()) {
            val cache = RClassCache.core(context)
            val key = cache.computeKey(rJavaFiles)
            if (cache.restore(key, workspace.classesDir)) {
                Log.d(tag, "Phase 1: R.class cache hit, skipped ${rJavaFiles.size} R.java files")
            } else {
                Log.d(tag, "Phase 1: Compiling ${rJavaFiles.size} R.java files in batches of $R_JAVA_BATCH_SIZE")
                rJavaFiles.chunked(R_JAVA_BATCH_SIZE).forEachIndexed { idx, batch ->
                    Log.d(tag, "R.java batch ${idx + 1}/${(rJavaFiles.size + R_JAVA_BATCH_SIZE - 1) / R_JAVA_BATCH_SIZE}: ${batch.size} files")
                    compileFiles(batch, workspace, input, logger)
                    System.gc()
                }
                // 此时 classesDir 里只有 R 类(用户源码在 Phase 2 才编译),store 只会收 R*.class
                cache.store(key, workspace.classesDir)
            }
        }
```

`context` 字段:`BuildStep` 基类持有 context(`JavacCompiler` 构造函数已接收),确认其可见性(protected/私有),不可见则给 JavacCompiler 保留一份 `private val appContext = context`。

**正确性依据:** R.java 内容由 AAPT2 依据当前资源生成(`Aapt2ResourceCompiler` 每次重新生成,:34-35),资源变了 → R.java 内容变 → key 变 → 缓存失效,不存在脏缓存;R.class 编译不依赖用户源码(R.java 无外部引用)。

- [ ] **Step 5: 验证**

1. `./gradlew :build-engine:test` → PASS;`./gradlew assembleDebug` → BUILD SUCCESSFUL;
2. 真机:同一项目连续 build 两次(agent 调 `run_build_pipeline` 或 UI 预览按钮),`adb logcat -s BuildEngine-Javac RClassCache`:第 1 次出现 `RClassCache store`,第 2 次出现 `R.class cache hit`,且第 2 次 COMPILE 阶段耗时显著下降(对比 build result 日志);
3. 改一个 `strings.xml` 再 build:出现 cache miss(key 变化)→ 正常编译 → 新 key store;
4. 构建产物 APK 安装运行正常(资源 ID 正确,无 Snackbar/布局错乱)。

- [ ] **Step 6: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/compiler build-engine/src/test/java/com/vibe/build/engine/compiler
git commit -m "feat(build-engine): cache compiled R classes keyed by R.java content hash (opt task 6.2)"
```

**验收标准:** 4 个单测通过;真机二次构建命中缓存且 APK 行为正常;资源修改后缓存正确失效。

---

## Task 6.3: Room 优化 — FTS 搜索 + thoughts 大字段外置 + 查询瘦身

**现状与证据:**
- 搜索是无索引 LIKE 全表扫描:`MessageV2Dao.kt:19-24`(`content LIKE '%q%' OR revisions LIKE '%q%'`);`ChatRepositoryImpl.searchChatsV2`(:19-38)还先 `getChatRooms()` 全量加载再内存过滤。
- `MessageV2.thoughts/content` 是无界 TEXT(`MessageV2.kt:29-33`),thoughts 存整轮 agent 输出(可达数百 KB);`loadMessages` 一次拉整聊天(`MessageV2Dao.kt:13-14`)。单行超大有 CursorWindow(2MB)`SQLiteBlobTooBigException` 风险。
- DB 当前 version=3(`ChatDatabaseV2.kt:21`),已有迁移先例 `MIGRATION_CHAT_DB_V2_1_2`、`_2_3`(`DatabaseModule.kt:24,45`,经 `addMigrations` 注册 :117)。
- thoughts 的全部消费点(grep 验证):`AgentSessionManager`(写入+parseThoughtsToSteps)、`TurnWorkSummaryFormatter`(跨回合摘要)、`DefaultAgentLoopCoordinator`(buildInitialConversation)、`ChatViewModel.fetchGroupedMessages`(:779-781 解析步骤展示)、`ThinkingBlock`(UI 展示)。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/database/ChatDatabaseV2.kt`(version 4,注册 FTS 实体)
- Create: `app/src/main/kotlin/com/vibe/app/data/database/entity/MessageFts.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/data/database/dao/MessageV2Dao.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/data/database/dao/ChatRoomV2Dao.kt`(加 getChatRoomsByIds)
- Modify: `app/src/main/kotlin/com/vibe/app/di/DatabaseModule.kt`(MIGRATION_3_4)
- Modify: `app/src/main/kotlin/com/vibe/app/data/repository/ChatRepositoryImpl.kt` 与 `ChatRepository` 接口
- Create: `app/src/main/kotlin/com/vibe/app/data/repository/ThoughtsStore.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/service/AgentSessionManager.kt`、`app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt`(读写路径)
- Test: `app/src/test/kotlin/com/vibe/app/data/repository/ThoughtsStoreTest.kt`

**接口契约:**
- `ThoughtsStore(baseDir: File)`:`fun write(chatId: Int, messageId: Int, thoughts: String)`、`fun read(chatId: Int, messageId: Int): String?`、`fun deleteChat(chatId: Int)`。存储路径 `files/chats/{chatId}/thoughts/{messageId}.txt`。
- 外置标记:`MessageV2.thoughts` 列值为字面量 `"@file"` 表示已外置;旧数据仍是内联全文,读取端两者兼容。
- (与 Phase 4.3 的衔接:TurnArtifact 落地后,跨回合重建不再读 thoughts 全文,本 Task 的外置收益进一步放大;两者互不阻塞。)

- [ ] **Step 1: FTS 实体 + DAO + 迁移**

```kotlin
// app/src/main/kotlin/com/vibe/app/data/database/entity/MessageFts.kt
package com.vibe.app.data.database.entity

import androidx.room.Entity
import androidx.room.Fts4

/** External-content FTS index over messages_v2.content — Room 自动生成同步触发器。 */
@Fts4(contentEntity = MessageV2::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val content: String,
)
```

`ChatDatabaseV2.kt`:entities 数组加 `MessageFts::class`,`version = 4`。

`MessageV2Dao.kt:19-24` 替换为:

```kotlin
    @Query(
        "SELECT DISTINCT messages_v2.chat_id FROM messages_v2 " +
            "JOIN messages_fts ON messages_v2.message_id = messages_fts.rowid " +
            "WHERE messages_fts MATCH :ftsQuery"
    )
    suspend fun searchMessagesByContent(ftsQuery: String): List<Int>
```

调用方负责把用户输入包装成安全的 FTS 前缀查询(见 Step 3)。注意:FTS 只索引 `content`,原先对 `revisions` 的 LIKE 匹配价值极低(revision 文本是 content 历史版本),直接放弃并在实施记录注明。

`ChatRoomV2Dao.kt` 新增:

```kotlin
    @Query("SELECT * FROM chat_rooms_v2 WHERE chat_id IN (:ids)")
    suspend fun getChatRoomsByIds(ids: List<Int>): List<ChatRoomV2>
```

(表名/列名以实体注解实际值为准,写前先读 `ChatRoomV2.kt` 核对。)

`DatabaseModule.kt` 加迁移并注册到 `.addMigrations(...)`(:117):

```kotlin
    private val MIGRATION_CHAT_DB_V2_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` " +
                    "USING FTS4(`content` TEXT NOT NULL, content=`messages_v2`)"
            )
            db.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
        }
    }
```

**迁移校验方法:** Room 对 FTS external-content 表的期望建表语句必须与迁移完全一致,执行 `./gradlew assembleDebug` 后打开生成的 `app/schemas/**/4.json`(exportSchema=true),把其中 `messages_fts` 的 `createSql` 原样抄进迁移(以 schema 文件为准,上面的 SQL 是初稿)。

- [ ] **Step 2: 修 searchChatsV2 的全量加载**

`ChatRepositoryImpl.searchChatsV2`(:19-38)中 `getChatRooms()+filter` 改为:

```kotlin
        val messageMatchChatIds = messageV2Dao.searchMessagesByContent(toFtsQuery(query))
        val messageMatches = if (messageMatchChatIds.isEmpty()) emptyList()
                             else chatRoomV2Dao.getChatRoomsByIds(messageMatchChatIds)
```

新增私有函数(防 FTS 语法注入,双引号包裹 + 前缀匹配):

```kotlin
    private fun toFtsQuery(raw: String): String =
        "\"" + raw.trim().replace("\"", "\"\"") + "\"*"
```

- [ ] **Step 3: ThoughtsStore + 写入路径外置**

```kotlin
// app/src/main/kotlin/com/vibe/app/data/repository/ThoughtsStore.kt
package com.vibe.app.data.repository

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** thoughts 大字段外置存储:files/chats/{chatId}/thoughts/{messageId}.txt。列值 "@file" 为外置标记。 */
@Singleton
class ThoughtsStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {
    companion object { const val FILE_MARKER = "@file" }

    private fun file(chatId: Int, messageId: Int): File =
        File(context.filesDir, "chats/$chatId/thoughts/$messageId.txt")

    fun write(chatId: Int, messageId: Int, thoughts: String) {
        val f = file(chatId, messageId)
        f.parentFile?.mkdirs()
        f.writeText(thoughts)
    }

    fun read(chatId: Int, messageId: Int): String? =
        file(chatId, messageId).takeIf { it.exists() }?.readText()

    fun deleteChat(chatId: Int) {
        File(context.filesDir, "chats/$chatId/thoughts").deleteRecursively()
    }
}
```

写入路径:`ChatRepositoryImpl.saveChat` 是唯一 DB 写入口(新聊天 insert :69-83;既有聊天 diff 更新 :85-107)。改造:

1. `MessageV2Dao.addMessages` 改为返回 rowIds:`@Insert suspend fun addMessages(vararg messages: MessageV2): List<Long>`;
2. `saveChat` 内:对 thoughts 超过阈值(`THOUGHTS_INLINE_LIMIT = 16 * 1024` 字符)的消息,insert/update 前替换为 `thoughts = ThoughtsStore.FILE_MARKER`,拿到 messageId 后调 `thoughtsStore.write(chatId, messageId, 原文)`(insert 用返回的 rowId;update 分支 id 已知);
3. `deleteChatsV2` / `deleteMessagesByChatId` 联动 `thoughtsStore.deleteChat(chatId)`。

读取路径:`ChatRepository` 加 `suspend fun resolveThoughts(message: MessageV2): String`,实现:

```kotlin
    override suspend fun resolveThoughts(message: MessageV2): String =
        if (message.thoughts == ThoughtsStore.FILE_MARKER) {
            thoughtsStore.read(message.chatId, message.id).orEmpty()
        } else {
            message.thoughts
        }
```

消费点改造(全部 grep 到,见"现状"):`ChatViewModel.fetchGroupedMessages`(:779-781)与 `AgentSessionManager.startSession`(历史 thoughts 解析,:110-118)在用 `msg.thoughts` 前先经 `resolveThoughts`;`ThinkingBlock` 由上游传值,无需改;`DefaultAgentLoopCoordinator`/`TurnWorkSummaryFormatter` 接收的是 ViewModel 传入的 MessageV2,在 `ChatViewModel` 组装 `AgentLoopRequest` 处统一 resolve(找到 `startSession(... userMessages, assistantMessages ...)` 的调用点,map 一遍)。

- [ ] **Step 4: ThoughtsStore 单测**

```kotlin
// 要点:write→read 回读一致;read 不存在返回 null;deleteChat 后 read 为 null;
// FILE_MARKER 常量值为 "@file"。用 Robolectric 或把 File 构造抽成可注入 baseDir 后纯 JVM 测试
// (推荐后者:构造函数改为 internal constructor(baseDir: File) + @Inject 主构造委托)。
```

按上述要点写 4 个用例,运行 `./gradlew :app:testDebugUnitTest --tests "*ThoughtsStoreTest"` → PASS。

- [ ] **Step 5: 验证**

1. `./gradlew test` → PASS;`./gradlew assembleDebug` → BUILD SUCCESSFUL;
2. 真机升级路径:装旧版本(dev 分支上一 commit)造几条聊天 → 装新版本 → 打开旧聊天正常、搜索命中旧消息(FTS rebuild 生效);
3. 新会话跑一轮 agent 任务 → `adb shell run-as com.vibe.app ls files/chats/<chatId>/thoughts/` 出现 txt 文件,DB 里对应行 thoughts 列为 `@file`(Database Inspector 或导出查看);
4. 聊天页展开 ThinkingBlock 步骤显示正常;删除聊天后 thoughts 目录被清。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data app/src/main/kotlin/com/vibe/app/di/DatabaseModule.kt app/src/main/kotlin/com/vibe/app/feature/agent/service/AgentSessionManager.kt app/src/main/kotlin/com/vibe/app/presentation/ui/chat app/src/test/kotlin/com/vibe/app/data app/schemas
git commit -m "feat(data): FTS message search + externalize large thoughts to files (opt task 6.3)"
```

**验收标准:** 旧库升级不炸、旧数据可读;搜索走 FTS(EXPLAIN QUERY PLAN 不再全表扫);新消息大 thoughts 落文件;UI 展示与删除联动正常。

---

## Task 6.4: 会话落库节流 + messageStates LRU 清理

**现状与证据:**
- `saveToRoom` 只在会话终态调用(完成/取消/异常,`AgentSessionManager.kt:146-160`),数分钟的运行期状态只在内存——进程被杀丢整轮。
- `removeSession` 刻意保留 messageStates,注释:"Keep messageStates around so reconnecting UI can still read final state. They will be cleaned up on next startSession() for the same chatId."(:465-469)——设计意图是断线重连后 UI 还能读到终态,但没有任何总量上限,每条含整聊天 thoughts/content,长期使用内存稳步上涨。
- **重复插入陷阱(必须处理):** `saveChat` 的更新分支按 `message.id` diff(`ChatRepositoryImpl.kt:88-101`),而会话内存中的 MessageV2 `id=0`(autoGenerate),**每次保存都会命中 shouldBeAdded 再插一遍**。终态单次调用没问题,节流多次调用必须先回填 DB id。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/database/dao/MessageV2Dao.kt`(addMessages 返回 ids,与 6.3 Step 3 同一改动)
- Modify: `app/src/main/kotlin/com/vibe/app/data/repository/ChatRepository.kt` / `ChatRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/service/AgentSessionManager.kt`

**接口契约:**
- `ChatRepository.saveChatReturningMessages(chatRoom, messages, chatPlatformModels): SavedChatResult`,`data class SavedChatResult(val chatRoom: ChatRoomV2, val messages: List<MessageV2>)`——返回的 messages 与入参**顺序一致**且 id 已是 DB 值。内部复用现有 saveChat 逻辑:insert 分支用 `addMessages` 返回的 rowIds 回填;diff 分支中 shouldBeAdded 同样回填,其余原样返回。

- [ ] **Step 1: 实现 saveChatReturningMessages(改造 saveChat 为其别名)**

保持 `saveChat` 签名兼容(内部调用新方法丢弃 messages 返回值)。核心变化:

```kotlin
        // insert 分支(chatRoom.id == 0)
        val rowIds = messageV2Dao.addMessages(*updatedMessages.toTypedArray())
        val withIds = updatedMessages.zip(rowIds) { m, rowId -> m.copy(id = rowId.toInt()) }
```

diff 分支对 `shouldBeAdded` 同样 zip 回填,组装返回列表时按入参顺序映射。

- [ ] **Step 2: AgentSessionManager 加 id 回填 + 节流保存**

`saveToRoom`(:430-451)改为调 `saveChatReturningMessages`,保存后把返回的 ids 回填进 state(按 `userMessages + assistantMessages.flatten()` 过滤排序后的顺序一一对应,与 saveToRoom 组装顺序相同):

```kotlin
            val result = chatRepository.saveChatReturningMessages(...)
            saveContexts[chatId] = saveContext.copy(chatRoom = result.chatRoom)
            backfillIds(chatId, result.messages)
```

`backfillIds` 实现:遍历 state 的 userMessages/assistantMessages,凡 `id == 0` 的消息按 (createdAt, platformType, content) 在返回列表中找到对应项(保存列表本身就是从 state 生成的,顺序稳定,可直接按"第 N 个 id==0 的消息 ↔ 返回列表第 N 个新插入项"配对——实现时在 saveToRoom 组装 messages 时同步记录源引用最稳妥),`copy(id = dbId)` 写回 stateFlow。

节流保存:字段区新增

```kotlin
    private val saveSignals = ConcurrentHashMap<Int, MutableSharedFlow<Unit>>()
```

`startSession` 中(`scope.launch` 主 job 之前):

```kotlin
        val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        saveSignals[chatId] = saveSignal
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        val saverJob = scope.launch {
            saveSignal.debounce(5_000).collect { saveToRoom(chatId) }
        }
```

主 job 的 `finally` 中:`saverJob.cancel(); saveSignals.remove(chatId)`(先 cancel 再做终态 saveToRoom 的现有调用不受影响——终态保存在 try/catch 各分支已有)。

`applyEvent` 的 `ToolExecutionFinished` 分支(:264-277)末尾加:

```kotlin
            saveSignals[chatId]?.tryEmit(Unit)
```

- [ ] **Step 3: messageStates LRU**

`removeSession`(:464-469)在保留语义(当前 chat 可重连读终态)的前提下加上限:

```kotlin
    private fun removeSession(chatId: Int) {
        _sessions.update { it - chatId }
        saveContexts.remove(chatId)
        // Keep the finished session's state for UI reconnection, but cap retained
        // states at MAX_RETAINED_STATES (evict least-recently-finished, never an active session).
        retainedOrder.remove(chatId)
        retainedOrder.add(chatId)
        while (retainedOrder.size > MAX_RETAINED_STATES) {
            val evict = retainedOrder.removeAt(0)
            if (_sessions.value.containsKey(evict)) continue // active — never evict
            messageStates.remove(evict)
        }
    }
```

字段区:`private val retainedOrder = java.util.Collections.synchronizedList(mutableListOf<Int>())`、`companion object { private const val MAX_RETAINED_STATES = 4 }`(companion 已存在则并入)。`clearMessageState`(:195-198)同步 `retainedOrder.remove(chatId)`。

- [ ] **Step 4: 验证**

1. `./gradlew test && ./gradlew assembleDebug` → PASS/SUCCESS;
2. 真机:发起长 agent 任务,任务进行中(几次工具调用后)`adb shell am kill com.vibe.app`(或系统设置强停),重开 app 进该聊天:**已完成的工具批次内容在**(不再是整轮丢失);
3. 重复消息检查:让任务正常完成后查看该聊天,消息无重复(id 回填生效);再次进入聊天并重开新任务,历史正常;
4. 依次在 6 个不同聊天各跑一个短任务,结束后用 Android Studio Profiler 或 `adb shell dumpsys meminfo com.vibe.app` 对比:messageStates 只保留最近 4 个(可加临时日志验证后删除)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data app/src/main/kotlin/com/vibe/app/feature/agent/service/AgentSessionManager.kt
git commit -m "feat(agent): throttled mid-run persistence with id backfill + LRU message states (opt task 6.4)"
```

**验收标准:** 进程被杀后已执行部分不丢;多次节流保存不产生重复消息;retained states 有上限且活跃会话永不被驱逐。

---

## Phase 完成检查

- [ ] `./gradlew test` 全绿
- [ ] `./gradlew :build-engine:test` 全绿
- [ ] `./gradlew assembleDebug` 成功
- [ ] 人工验证清单:
  - [ ] Anthropic 任务第二次迭代 cacheRead > 0;MiniMax 回归正常
  - [ ] 同项目二次构建命中 R.class 缓存,APK 运行正常;改资源后缓存失效
  - [ ] 旧数据库升级后聊天/搜索正常;大 thoughts 落文件;删聊天清文件
  - [ ] 任务中途杀进程,重开不丢已完成部分;消息无重复
- [ ] 更新 `00-progress.md`:Phase 6 状态 → ✅ 已完成,填完成日期
- [ ] `git commit -m "docs: mark optimization phase 6 complete"`

## 实施记录(执行时追加)

| 日期 | 执行者 | 完成内容 | 偏离/备注 |
|------|--------|----------|-----------|
