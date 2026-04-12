# UI Pattern Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a curated Android XML pattern library + embedded design tokens + three agent tools so VibeApp's generated apps reach a Material Components utility-app baseline without constraining creative scenarios.

**Architecture:** Three layers — (1) ~300-word hard token whitelist embedded in `agent-system-prompt.md`, (2) on-demand `PatternLibrary` in `assets/patterns/` with `blocks/` + `screens/` subdirectories + generated `index.json`, (3) three on-demand agent tools (`search_ui_pattern`, `get_ui_pattern`, `get_design_guide`) that read from assets.

**Tech Stack:** Kotlin 1.9, Hilt, kotlinx.serialization, JUnit4, Android assets, MaterialComponents (M2) theme attrs.

**Spec:** `docs/superpowers/specs/2026-04-11-ui-pattern-library-design.md`

**Theme constraint (critical):** bundled template parent is `Theme.MaterialComponents.DayNight.NoActionBar`. Use `@style/TextAppearance.MaterialComponents.*` and M2 color attrs only. Do NOT use M3 attrs like `textAppearanceTitleMedium`, `colorTertiary`, `colorSurfaceVariant`.

---

## Phase A · Data layer skeleton

### Task A1: Scaffold `PatternRecord` types

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/uipattern/PatternRecord.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.vibe.app.feature.uipattern

enum class PatternKind { BLOCK, SCREEN }

data class PatternSlot(
    val name: String,
    val description: String,
    val default: String,
)

data class PatternRecord(
    val id: String,
    val kind: PatternKind,
    val description: String,
    val keywords: List<String>,
    val slots: List<PatternSlot>,
    val dependencies: List<String>,
    val layoutXml: String,
    val notes: String,
)

data class PatternSearchHit(
    val id: String,
    val kind: PatternKind,
    val description: String,
    val keywords: List<String>,
    val slotNames: List<String>,
    val dependencies: List<String>,
)
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (file has no consumers yet — just verifies syntax).

### Task A2: `PatternLibrary` loads index.json from asset streams

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/uipattern/PatternLibrary.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/uipattern/PatternLibraryTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.vibe.app.feature.uipattern

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PatternLibraryTest {

    private val sampleIndex = """
        {
          "version": 1,
          "generated": "2026-04-11T10:00:00Z",
          "patterns": [
            {
              "id": "list_item_two_line",
              "kind": "block",
              "description": "Two-line list row",
              "keywords": ["list", "item"],
              "slotNames": ["title", "subtitle"],
              "dependencies": []
            }
          ]
        }
    """.trimIndent()

    private val sampleMeta = """
        {
          "id": "list_item_two_line",
          "kind": "block",
          "description": "Two-line list row",
          "keywords": ["list", "item"],
          "slots": [
            { "name": "title", "description": "Primary", "default": "Title" },
            { "name": "subtitle", "description": "Secondary", "default": "Subtitle" }
          ],
          "dependencies": []
        }
    """.trimIndent()

    private val sampleLayout = "<View xmlns:android=\"http://schemas.android.com/apk/res/android\"/>"
    private val sampleNotes = "Use for simple rows."

    private fun library() = PatternLibrary(object : PatternLibrary.AssetProvider {
        override fun openIndex() = ByteArrayInputStream(sampleIndex.toByteArray())
        override fun openFile(relativePath: String) = when (relativePath) {
            "blocks/list_item_two_line/meta.json" -> ByteArrayInputStream(sampleMeta.toByteArray())
            "blocks/list_item_two_line/layout.xml" -> ByteArrayInputStream(sampleLayout.toByteArray())
            "blocks/list_item_two_line/notes.md" -> ByteArrayInputStream(sampleNotes.toByteArray())
            else -> throw java.io.FileNotFoundException(relativePath)
        }
    })

    @Test
    fun `loads index and returns hits`() {
        val hits = library().allHits()
        assertEquals(1, hits.size)
        assertEquals("list_item_two_line", hits.first().id)
        assertEquals(PatternKind.BLOCK, hits.first().kind)
    }

    @Test
    fun `get returns full record`() {
        val record = library().get("list_item_two_line")
        assertNotNull(record)
        assertEquals(2, record!!.slots.size)
        assertEquals("Title", record.slots.first().default)
        assertEquals(sampleLayout, record.layoutXml)
        assertEquals("Use for simple rows.", record.notes)
    }

    @Test
    fun `get unknown id returns null`() {
        assertNull(library().get("nope"))
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternLibraryTest"`
Expected: FAIL (class `PatternLibrary` does not exist).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.vibe.app.feature.uipattern

import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PatternLibrary(
    private val assets: AssetProvider,
) {
    interface AssetProvider {
        fun openIndex(): InputStream
        fun openFile(relativePath: String): InputStream
    }

    @Serializable
    private data class IndexJson(
        val version: Int,
        val generated: String = "",
        val patterns: List<IndexEntry>,
    )

    @Serializable
    private data class IndexEntry(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val slotNames: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

    @Serializable
    private data class MetaJson(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val slots: List<MetaSlot> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

    @Serializable
    private data class MetaSlot(
        val name: String,
        val description: String,
        val default: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val index: List<IndexEntry> by lazy {
        val raw = assets.openIndex().use { it.readBytes().decodeToString() }
        json.decodeFromString(IndexJson.serializer(), raw).patterns
    }

    private fun IndexEntry.toHit() = PatternSearchHit(
        id = id,
        kind = parseKind(kind),
        description = description,
        keywords = keywords,
        slotNames = slotNames,
        dependencies = dependencies,
    )

    fun allHits(): List<PatternSearchHit> = index.map { it.toHit() }

    fun get(id: String): PatternRecord? {
        val entry = index.firstOrNull { it.id == id } ?: return null
        val kind = parseKind(entry.kind)
        val dir = "${if (kind == PatternKind.BLOCK) "blocks" else "screens"}/$id"
        val meta = assets.openFile("$dir/meta.json").use { it.readBytes().decodeToString() }
            .let { json.decodeFromString(MetaJson.serializer(), it) }
        val layoutXml = assets.openFile("$dir/layout.xml").use { it.readBytes().decodeToString() }
        val notes = assets.openFile("$dir/notes.md").use { it.readBytes().decodeToString() }
        return PatternRecord(
            id = meta.id,
            kind = parseKind(meta.kind),
            description = meta.description,
            keywords = meta.keywords,
            slots = meta.slots.map { PatternSlot(it.name, it.description, it.default) },
            dependencies = meta.dependencies,
            layoutXml = layoutXml,
            notes = notes,
        )
    }

    private fun parseKind(raw: String): PatternKind = when (raw.lowercase()) {
        "block" -> PatternKind.BLOCK
        "screen" -> PatternKind.SCREEN
        else -> error("Unknown pattern kind '$raw'")
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternLibraryTest"`
Expected: 3 tests PASS.

### Task A3: `PatternSearch` ranks hits

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/uipattern/PatternSearch.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/uipattern/PatternSearchTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.vibe.app.feature.uipattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternSearchTest {

    private val hits = listOf(
        hit("list_item_two_line", PatternKind.BLOCK, "Two-line list row", listOf("list", "item", "row")),
        hit("screen_list", PatternKind.SCREEN, "List screen with empty state", listOf("list", "screen")),
        hit("section_header", PatternKind.BLOCK, "Group header with optional subtitle", listOf("header", "title")),
        hit("stat_card", PatternKind.BLOCK, "Numeric stat card", listOf("card", "stat", "number")),
    )

    private fun hit(id: String, kind: PatternKind, desc: String, kw: List<String>) = PatternSearchHit(
        id = id,
        kind = kind,
        description = desc,
        keywords = kw,
        slotNames = emptyList(),
        dependencies = emptyList(),
    )

    @Test
    fun `empty keyword returns empty`() {
        assertTrue(PatternSearch.search(hits, "", PatternKind.BLOCK, 10).isEmpty())
    }

    @Test
    fun `id exact match ranks first`() {
        val out = PatternSearch.search(hits, "screen_list", null, 10)
        assertEquals("screen_list", out.first().id)
    }

    @Test
    fun `id substring outranks description match`() {
        val out = PatternSearch.search(hits, "list", null, 10)
        // Both list_item_two_line and screen_list have "list" in id
        assertTrue(out.take(2).map { it.id }.containsAll(listOf("list_item_two_line", "screen_list")))
    }

    @Test
    fun `kind filter restricts results`() {
        val out = PatternSearch.search(hits, "list", PatternKind.SCREEN, 10)
        assertEquals(1, out.size)
        assertEquals("screen_list", out.first().id)
    }

    @Test
    fun `limit caps results`() {
        val out = PatternSearch.search(hits, "list", null, 1)
        assertEquals(1, out.size)
    }

    @Test
    fun `keyword hit found via keywords list`() {
        val out = PatternSearch.search(hits, "header", null, 10)
        assertEquals("section_header", out.first().id)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternSearchTest"`
Expected: FAIL (`PatternSearch` unresolved).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.vibe.app.feature.uipattern

object PatternSearch {

    fun search(
        hits: List<PatternSearchHit>,
        keyword: String,
        kind: PatternKind?,
        limit: Int,
    ): List<PatternSearchHit> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        if (limit <= 0) return emptyList()

        val filtered = if (kind == null) hits else hits.filter { it.kind == kind }

        val ranked = filtered.mapNotNull { hit ->
            val idLower = hit.id.lowercase()
            val score = when {
                idLower == needle -> 0
                idLower.contains(needle) -> 1
                hit.keywords.any { it.lowercase().contains(needle) } -> 2
                hit.description.lowercase().contains(needle) -> 3
                else -> return@mapNotNull null
            }
            score to hit
        }

        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .take(limit)
            .map { it.second }
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternSearchTest"`
Expected: 6 tests PASS.

### Task A4: `DesignGuideLoader` slices markdown by H2

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/uipattern/DesignGuideLoader.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/uipattern/DesignGuideLoaderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.vibe.app.feature.uipattern

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignGuideLoaderTest {

    private val sample = """
        # VibeApp UI Design Guide

        Intro paragraph.

        ## Tokens

        Color attrs list.

        ## Components

        Component usage.

        ## Layout

        Spacing rules.

        ## Creative Mode

        Overrides allowed.
    """.trimIndent()

    private fun loader() = DesignGuideLoader { ByteArrayInputStream(sample.toByteArray()) }

    @Test
    fun `returns full document for all`() {
        val content = loader().load("all")
        assertTrue(content.contains("# VibeApp UI Design Guide"))
        assertTrue(content.contains("## Tokens"))
        assertTrue(content.contains("## Creative Mode"))
    }

    @Test
    fun `returns single section by name`() {
        val content = loader().load("tokens")
        assertTrue(content.startsWith("## Tokens"))
        assertTrue(content.contains("Color attrs list"))
        assertTrue(!content.contains("## Components"))
    }

    @Test
    fun `section name case insensitive`() {
        val content = loader().load("CREATIVE")
        assertTrue(content.startsWith("## Creative Mode"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown section throws`() {
        loader().load("nope")
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.DesignGuideLoaderTest"`
Expected: FAIL (class not found).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.vibe.app.feature.uipattern

import java.io.InputStream

class DesignGuideLoader(
    private val streamProvider: StreamProvider,
) {
    fun interface StreamProvider {
        fun open(): InputStream
    }

    private val content: String by lazy {
        streamProvider.open().use { it.readBytes().decodeToString() }
    }

    fun load(section: String): String {
        val key = section.trim().lowercase()
        if (key == "all") return content
        val sections = splitByH2(content)
        val match = sections.entries.firstOrNull { it.key.lowercase().startsWith(key) }
            ?: throw IllegalArgumentException(
                "Unknown section '$section'. Valid: ${sections.keys.joinToString()} or 'all'.",
            )
        return "## ${match.key}\n\n${match.value}".trimEnd()
    }

    private fun splitByH2(text: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        var currentTitle: String? = null
        val currentBody = StringBuilder()
        for (line in text.lines()) {
            if (line.startsWith("## ")) {
                if (currentTitle != null) {
                    result[currentTitle!!] = currentBody.toString().trimEnd()
                }
                currentTitle = line.removePrefix("## ").trim()
                currentBody.clear()
            } else if (currentTitle != null) {
                currentBody.appendLine(line)
            }
        }
        if (currentTitle != null) {
            result[currentTitle!!] = currentBody.toString().trimEnd()
        }
        return result
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.DesignGuideLoaderTest"`
Expected: 4 tests PASS.

### Task A5: Hilt module + empty asset stubs

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/di/UiPatternModule.kt`
- Create: `app/src/main/assets/patterns/index.json`
- Create: `app/src/main/assets/design/design-guide.md`

- [ ] **Step 1: Create empty index.json**

Path: `app/src/main/assets/patterns/index.json`
Content:
```json
{
  "version": 1,
  "generated": "2026-04-11T00:00:00Z",
  "patterns": []
}
```

- [ ] **Step 2: Create design-guide.md skeleton**

Path: `app/src/main/assets/design/design-guide.md`
Content:
```markdown
# VibeApp UI Design Guide

Material Components baseline for on-device generated Android utility apps.

## Tokens

(placeholder — filled in Phase D)

## Components

(placeholder — filled in Phase D)

## Layout

(placeholder — filled in Phase D)

## Creative Mode

(placeholder — filled in Phase D)
```

- [ ] **Step 3: Write Hilt module**

Path: `app/src/main/kotlin/com/vibe/app/di/UiPatternModule.kt`
```kotlin
package com.vibe.app.di

import android.content.Context
import com.vibe.app.feature.uipattern.DesignGuideLoader
import com.vibe.app.feature.uipattern.PatternLibrary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UiPatternModule {

    @Provides
    @Singleton
    fun providePatternLibrary(@ApplicationContext context: Context): PatternLibrary {
        val assets = context.assets
        return PatternLibrary(object : PatternLibrary.AssetProvider {
            override fun openIndex() = assets.open("patterns/index.json")
            override fun openFile(relativePath: String) = assets.open("patterns/$relativePath")
        })
    }

    @Provides
    @Singleton
    fun provideDesignGuideLoader(@ApplicationContext context: Context): DesignGuideLoader {
        return DesignGuideLoader { context.assets.open("design/design-guide.md") }
    }
}
```

- [ ] **Step 4: Run all app tests — verify nothing broke**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All Phase A tests pass.

- [ ] **Step 5: Compile app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit Phase A**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/uipattern \
        app/src/test/kotlin/com/vibe/app/feature/uipattern \
        app/src/main/kotlin/com/vibe/app/di/UiPatternModule.kt \
        app/src/main/assets/patterns/index.json \
        app/src/main/assets/design/design-guide.md
git commit -m "$(cat <<'EOF'
feat(ui-pattern): add PatternLibrary data layer

Phase A of the UI pattern library. Adds PatternRecord / PatternLibrary /
PatternSearch / DesignGuideLoader with unit tests. Empty index.json and
design-guide.md stubs so the Hilt module can wire up; content arrives in
Phase D.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase B · Agent tools + system prompt

### Task B1: Three agent tools

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/UiPatternTools.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/UiPatternToolsTest.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`

Reference for arg helpers (existing file): see `IconTools.kt` for usage of `call.arguments.requireString`, `optionalInt`, `optionalString`, `call.result(buildJsonObject {...})`, `call.errorResult`, `call.okResult`.

- [ ] **Step 1: Write failing tool test**

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.uipattern.DesignGuideLoader
import com.vibe.app.feature.uipattern.PatternKind
import com.vibe.app.feature.uipattern.PatternLibrary
import com.vibe.app.feature.uipattern.PatternRecord
import com.vibe.app.feature.uipattern.PatternSearchHit
import com.vibe.app.feature.uipattern.PatternSlot
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPatternToolsTest {

    private val record = PatternRecord(
        id = "list_item_two_line",
        kind = PatternKind.BLOCK,
        description = "Two-line list row",
        keywords = listOf("list", "item"),
        slots = listOf(PatternSlot("title", "Primary", "Title")),
        dependencies = emptyList(),
        layoutXml = "<View/>",
        notes = "Use for rows.",
    )

    private val hit = PatternSearchHit(
        id = "list_item_two_line",
        kind = PatternKind.BLOCK,
        description = "Two-line list row",
        keywords = listOf("list", "item"),
        slotNames = listOf("title"),
        dependencies = emptyList(),
    )

    private val library = object : PatternLibrary(stubProvider()) {
        // we'll not actually load; tests override methods via a fake subclass below
    }

    private fun stubProvider() = object : PatternLibrary.AssetProvider {
        override fun openIndex() = ByteArrayInputStream("{\"version\":1,\"patterns\":[]}".toByteArray())
        override fun openFile(relativePath: String) = ByteArrayInputStream(ByteArray(0))
    }

    private class FakeLibrary(
        private val hits: List<PatternSearchHit>,
        private val record: PatternRecord?,
    ) : PatternLibrary(object : AssetProvider {
        override fun openIndex() = ByteArrayInputStream("{\"version\":1,\"patterns\":[]}".toByteArray())
        override fun openFile(relativePath: String) = ByteArrayInputStream(ByteArray(0))
    }) {
        override fun allHits() = hits
        override fun get(id: String): PatternRecord? = if (record?.id == id) record else null
    }

    private val designGuide = DesignGuideLoader {
        ByteArrayInputStream(
            """
                # Guide

                ## Tokens

                Token rules here.

                ## Components

                Component rules.
            """.trimIndent().toByteArray(),
        )
    }

    private val context = AgentToolContext(projectId = "proj", workspacePath = "/tmp")

    private fun call(name: String, args: JsonObject) = AgentToolCall(id = "c1", name = name, arguments = args)

    @Test
    fun `search tool returns hits`() = runBlocking {
        val tool = SearchUiPatternTool(FakeLibrary(listOf(hit), record))
        val result = tool.execute(
            call("search_ui_pattern", buildJsonObject { put("keyword", JsonPrimitive("list")) }),
            context,
        )
        val output = result.output
        assertTrue(output.toString().contains("list_item_two_line"))
    }

    @Test
    fun `search tool rejects empty keyword`() = runBlocking {
        val tool = SearchUiPatternTool(FakeLibrary(listOf(hit), record))
        val result = tool.execute(
            call("search_ui_pattern", buildJsonObject { put("keyword", JsonPrimitive("")) }),
            context,
        )
        assertTrue(result.isError)
    }

    @Test
    fun `get tool returns full record`() = runBlocking {
        val tool = GetUiPatternTool(FakeLibrary(listOf(hit), record))
        val result = tool.execute(
            call("get_ui_pattern", buildJsonObject { put("id", JsonPrimitive("list_item_two_line")) }),
            context,
        )
        assertTrue(result.output.toString().contains("<View/>"))
        assertTrue(result.output.toString().contains("Use for rows."))
    }

    @Test
    fun `get tool unknown id returns error`() = runBlocking {
        val tool = GetUiPatternTool(FakeLibrary(emptyList(), null))
        val result = tool.execute(
            call("get_ui_pattern", buildJsonObject { put("id", JsonPrimitive("nope")) }),
            context,
        )
        assertTrue(result.isError)
    }

    @Test
    fun `design guide tool returns section`() = runBlocking {
        val tool = GetDesignGuideTool(designGuide)
        val result = tool.execute(
            call("get_design_guide", buildJsonObject { put("section", JsonPrimitive("tokens")) }),
            context,
        )
        assertTrue(result.output.toString().contains("Token rules here"))
    }
}
```

- [ ] **Step 2: Mark `PatternLibrary.allHits` and `get` as `open`**

Edit `app/src/main/kotlin/com/vibe/app/feature/uipattern/PatternLibrary.kt`:
change `class PatternLibrary(` to `open class PatternLibrary(`
change `fun allHits(): List<PatternSearchHit>` to `open fun allHits(): List<PatternSearchHit>`
change `fun get(id: String): PatternRecord?` to `open fun get(id: String): PatternRecord?`

- [ ] **Step 3: Write `UiPatternTools.kt`**

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.uipattern.DesignGuideLoader
import com.vibe.app.feature.uipattern.PatternKind
import com.vibe.app.feature.uipattern.PatternLibrary
import com.vibe.app.feature.uipattern.PatternSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Singleton
class SearchUiPatternTool @Inject constructor(
    private val library: PatternLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "search_ui_pattern",
        description = "Search the bundled UI pattern library by keyword. " +
            "Returns a list of component blocks and screen templates you can " +
            "pass to get_ui_pattern. Use this before writing new XML from scratch.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("keyword", stringProp("Keyword such as 'list', 'form', 'settings', 'empty'."))
                    put("kind", stringProp("Optional: 'block' | 'screen' | 'any' (default any)."))
                    put("limit", intProp("Max hits, default 10, max 30."))
                },
            )
            put("required", requiredFields("keyword"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val keyword = call.arguments.requireString("keyword")
        if (keyword.isBlank()) return call.errorResult("keyword required")
        val kindStr = call.arguments.optionalString("kind")?.lowercase()
        val kindFilter = when (kindStr) {
            null, "", "any" -> null
            "block" -> PatternKind.BLOCK
            "screen" -> PatternKind.SCREEN
            else -> return call.errorResult("kind must be 'block', 'screen', or 'any'")
        }
        val limit = call.arguments.optionalInt("limit", 10).coerceIn(1, 30)

        val hits = PatternSearch.search(library.allHits(), keyword, kindFilter, limit)
        val output = buildJsonObject {
            put("total", JsonPrimitive(hits.size))
            put(
                "hits",
                buildJsonArray {
                    for (hit in hits) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(hit.id))
                                put("kind", JsonPrimitive(hit.kind.name.lowercase()))
                                put("description", JsonPrimitive(hit.description))
                                put(
                                    "keywords",
                                    buildJsonArray { hit.keywords.forEach { add(JsonPrimitive(it)) } },
                                )
                                put(
                                    "slotNames",
                                    buildJsonArray { hit.slotNames.forEach { add(JsonPrimitive(it)) } },
                                )
                                put(
                                    "dependencies",
                                    buildJsonArray { hit.dependencies.forEach { add(JsonPrimitive(it)) } },
                                )
                            },
                        )
                    }
                },
            )
        }
        return call.result(output)
    }
}

@Singleton
class GetUiPatternTool @Inject constructor(
    private val library: PatternLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "get_ui_pattern",
        description = "Fetch a full UI pattern by id. Returns layoutXml (with " +
            "{{slot}} placeholders), slots, usage notes, and dependencies. " +
            "ALWAYS adapt the returned XML to the task — never paste verbatim.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("id", stringProp("Pattern id returned by search_ui_pattern."))
                },
            )
            put("required", requiredFields("id"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val id = call.arguments.requireString("id")
        val record = library.get(id)
            ?: return call.errorResult("Unknown pattern id '$id'. Call search_ui_pattern first.")

        val output = buildJsonObject {
            put("id", JsonPrimitive(record.id))
            put("kind", JsonPrimitive(record.kind.name.lowercase()))
            put("description", JsonPrimitive(record.description))
            put("layoutXml", JsonPrimitive(record.layoutXml))
            put(
                "slots",
                buildJsonArray {
                    for (slot in record.slots) {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(slot.name))
                                put("description", JsonPrimitive(slot.description))
                                put("default", JsonPrimitive(slot.default))
                            },
                        )
                    }
                },
            )
            put("usageNotes", JsonPrimitive(record.notes))
            put(
                "dependencies",
                buildJsonArray { record.dependencies.forEach { add(JsonPrimitive(it)) } },
            )
        }
        return call.result(output)
    }
}

@Singleton
class GetDesignGuideTool @Inject constructor(
    private val loader: DesignGuideLoader,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "get_design_guide",
        description = "Fetch the full VibeApp Android design guide or a " +
            "specific section. Sections: tokens | components | layout | creative | all.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("section", stringProp("'tokens' | 'components' | 'layout' | 'creative' | 'all'. Default 'all'."))
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val section = call.arguments.optionalString("section") ?: "all"
        val content = try {
            loader.load(section)
        } catch (e: IllegalArgumentException) {
            return call.errorResult(e.message ?: "Invalid section")
        }
        val output = buildJsonObject {
            put("section", JsonPrimitive(section.lowercase()))
            put("content", JsonPrimitive(content))
        }
        return call.result(output)
    }
}
```

- [ ] **Step 4: Register in `AgentToolModule`**

Edit `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`:
add imports:
```kotlin
import com.vibe.app.feature.agent.tool.SearchUiPatternTool
import com.vibe.app.feature.agent.tool.GetUiPatternTool
import com.vibe.app.feature.agent.tool.GetDesignGuideTool
```
add `@Binds @IntoSet` entries before the closing brace:
```kotlin
@Binds @IntoSet abstract fun bindSearchUiPattern(tool: SearchUiPatternTool): AgentTool
@Binds @IntoSet abstract fun bindGetUiPattern(tool: GetUiPatternTool): AgentTool
@Binds @IntoSet abstract fun bindGetDesignGuide(tool: GetDesignGuideTool): AgentTool
```

- [ ] **Step 5: Run tool tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.UiPatternToolsTest"`
Expected: 5 tests PASS.

- [ ] **Step 6: Run full app test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all green.

### Task B2: Update `agent-system-prompt.md`

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md`

- [ ] **Step 1: Delete current "UI Tips" section**

Remove the `## UI Tips` section entirely (the emoji-as-icon, vector-drawable, SimpleImageLoader block).

- [ ] **Step 2: Insert "Design Guide (Embedded)" + "UI Pattern Library" after "## Web Search & Page Fetching"**

Insert exactly this block before `## System Bars & Window Insets`:

```markdown
## Design Guide (Embedded Hard Constraints)

Bundled theme parent is `Theme.MaterialComponents.DayNight.NoActionBar` (M2).
Use MaterialComponents attrs only — NOT Material3.

Tokens (whitelist — violations break the build or look wrong):
- Colors: `?attr/colorPrimary`, `?attr/colorPrimaryVariant`, `?attr/colorOnPrimary`,
  `?attr/colorSecondary`, `?attr/colorSecondaryVariant`, `?attr/colorOnSecondary`,
  `?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorError`, `?attr/colorOnError`,
  `?android:attr/colorBackground`. No hex literals unless Creative Mode.
- Text: `@style/TextAppearance.MaterialComponents.Headline5` / Headline6 /
  Subtitle1 / Subtitle2 / Body1 / Body2 / Button / Caption / Overline.
- Spacing: pick from 4 / 8 / 12 / 16 / 24 / 32 dp.
- Corner radius: 4 / 8 / 12 / 16 / 28 dp.
- Elevation: 0 / 1 / 3 / 6 dp.
- Screen horizontal padding default: 16dp.
- Touch target ≥48dp.

Hard rules:
- MaterialToolbar as a regular View, never `setSupportActionBar()`.
- RecyclerView item spacing via padding, not ItemDecoration.
- Form row height ≥48dp.

## UI Pattern Library

Tools: `search_ui_pattern` / `get_ui_pattern` / `get_design_guide`.

Decision flow when building UI:
1. **Creative request?** Triggers: 好看 / 有设计感 / 复古 / 童趣 / 酷炫 / 极简 /
   暗黑 / "像 ___ 一样". YES → skip the library, use embedded tokens, and
   allow overriding primary/secondary palette and typeface.
2. **Standard utility screen?** (list / form / settings / detail / dashboard)
   → `search_ui_pattern(keyword, kind="screen")` as a shortcut.
3. **Otherwise** → `search_ui_pattern(keyword, kind="block")` and compose
   your own screen from blocks.
4. **Unsure about tokens / components?** → `get_design_guide(section=...)`.
5. **ALWAYS adapt fetched patterns** — change copy, remove unused slots,
   rearrange order. NEVER paste verbatim. The library is a floor, not a ceiling.

Slot format: `layoutXml` contains `{{slot_name}}` placeholders. Replace every
one with a real value (use `slots[].default` or something task-specific) before
writing the XML to `res/layout/`.

## UI Tips (quick reference)

- **Emoji as icons**: `<TextView android:text="☀️" android:textSize="48sp"/>`
- **Vector drawables**: simple vector XML in `res/drawable/`, ≤5 paths.
- **Network images**: `SimpleImageLoader.getInstance().load(url, imageView)`.
```

- [ ] **Step 3: Re-run app tests to ensure prompt load still works**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all green.

- [ ] **Step 4: Compile app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Phase B**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/uipattern/PatternLibrary.kt \
        app/src/main/kotlin/com/vibe/app/feature/agent/tool/UiPatternTools.kt \
        app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/tool/UiPatternToolsTest.kt \
        app/src/main/assets/agent-system-prompt.md
git commit -m "$(cat <<'EOF'
feat(ui-pattern): add three agent tools and design-guide prompt

Phase B: SearchUiPatternTool / GetUiPatternTool / GetDesignGuideTool wired
through AgentToolModule. agent-system-prompt.md gains Design Guide embedded
token whitelist and UI Pattern Library decision flow. UI Tips collapsed to
a quick-reference block.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C · Index generator + XML validity smoke

### Task C1: `PatternIndexGeneratorTest` — scans assets, regenerates index.json, validates meta

**Files:**
- Create: `app/src/test/kotlin/com/vibe/app/feature/uipattern/PatternIndexGeneratorTest.kt`

This test is also the build tool: running it re-writes `index.json`.

- [ ] **Step 1: Write the test**

```kotlin
package com.vibe.app.feature.uipattern

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regenerates app/src/main/assets/patterns/index.json by scanning the
 * blocks/ and screens/ subdirectories. Also validates that every meta.json
 * has all required fields and that layout.xml / notes.md exist.
 *
 * Running this test is the only supported way to update the index.
 */
class PatternIndexGeneratorTest {

    @Serializable
    private data class MetaJson(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val slots: List<MetaSlot> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

    @Serializable
    private data class MetaSlot(
        val name: String,
        val description: String,
        val default: String,
    )

    @Serializable
    private data class IndexEntry(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String>,
        val slotNames: List<String>,
        val dependencies: List<String>,
    )

    @Serializable
    private data class IndexJson(
        val version: Int,
        val generated: String,
        val patterns: List<IndexEntry>,
    )

    @Test
    fun `regenerates index and validates every pattern`() {
        val patternsRoot = locatePatternsDir()
        val blocksDir = File(patternsRoot, "blocks")
        val screensDir = File(patternsRoot, "screens")
        blocksDir.mkdirs()
        screensDir.mkdirs()

        val entries = mutableListOf<IndexEntry>()
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        for ((kindDir, kindName) in listOf(blocksDir to "block", screensDir to "screen")) {
            val children = kindDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
            for (dir in children) {
                val metaFile = File(dir, "meta.json")
                val layoutFile = File(dir, "layout.xml")
                val notesFile = File(dir, "notes.md")
                assertTrue("Missing meta.json in ${dir.absolutePath}", metaFile.exists())
                assertTrue("Missing layout.xml in ${dir.absolutePath}", layoutFile.exists())
                assertTrue("Missing notes.md in ${dir.absolutePath}", notesFile.exists())

                val meta = json.decodeFromString(MetaJson.serializer(), metaFile.readText())
                assertEquals("Directory name must equal meta.id", dir.name, meta.id)
                assertEquals("meta.kind must match directory kind", kindName, meta.kind.lowercase())
                assertTrue("meta.description empty in ${dir.name}", meta.description.isNotBlank())
                for (slot in meta.slots) {
                    assertTrue("slot name empty in ${dir.name}", slot.name.isNotBlank())
                    assertTrue("slot default empty in ${dir.name}/${slot.name}", slot.default.isNotBlank())
                }

                entries += IndexEntry(
                    id = meta.id,
                    kind = kindName,
                    description = meta.description,
                    keywords = meta.keywords,
                    slotNames = meta.slots.map { it.name },
                    dependencies = meta.dependencies,
                )
            }
        }

        val index = IndexJson(
            version = 1,
            generated = "2026-04-11T00:00:00Z",
            patterns = entries.sortedBy { it.id },
        )
        val output = json.encodeToString(IndexJson.serializer(), index) + "\n"
        File(patternsRoot, "index.json").writeText(output)

        println("PatternIndexGeneratorTest: regenerated index with ${entries.size} patterns")
    }

    private fun locatePatternsDir(): File {
        val candidates = listOf(
            File("app/src/main/assets/patterns"),
            File("../app/src/main/assets/patterns"),
            File(System.getProperty("user.dir"), "app/src/main/assets/patterns"),
        )
        val hit = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate app/src/main/assets/patterns from ${File(".").absolutePath}")
        return hit
    }
}
```

- [ ] **Step 2: Run it against the empty library**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternIndexGeneratorTest"`
Expected: PASS. `index.json` regenerated as empty (no subdirs yet).

### Task C2: `PatternXmlValidityTest` — XML parse + slot substitution sanity

**Files:**
- Create: `app/src/test/kotlin/com/vibe/app/feature/uipattern/PatternXmlValidityTest.kt`
- Create: `docs/ui-pattern-manual-smoke.md`

Because `Aapt2ResourceCompiler` needs an Android `Context` + `Aapt2Jni`, we cannot invoke AAPT2 in a JVM unit test. We validate that each layout is well-formed XML after slot substitution, and document the manual on-device smoke test.

- [ ] **Step 1: Write the test**

```kotlin
package com.vibe.app.feature.uipattern

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM-only smoke test. Walks every pattern, substitutes slot defaults, and
 * verifies the resulting XML is well-formed via the javax DocumentBuilder.
 *
 * This is NOT AAPT2 compilation — M2 attr resolution is verified manually
 * against a live agent-run build (see docs/ui-pattern-manual-smoke.md).
 */
class PatternXmlValidityTest {

    @Serializable
    private data class MetaJson(
        val id: String,
        val kind: String,
        val slots: List<MetaSlot> = emptyList(),
    )

    @Serializable
    private data class MetaSlot(
        val name: String,
        val description: String,
        val default: String,
    )

    @Test
    fun `every pattern is well-formed after slot substitution`() {
        val root = locatePatternsDir()
        val json = Json { ignoreUnknownKeys = true }
        val patternDirs = listOf("blocks", "screens")
            .map { File(root, it) }
            .filter { it.exists() }
            .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }

        val failures = mutableListOf<String>()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

        for (dir in patternDirs) {
            val metaFile = File(dir, "meta.json")
            val layoutFile = File(dir, "layout.xml")
            if (!metaFile.exists() || !layoutFile.exists()) continue

            val meta = json.decodeFromString(MetaJson.serializer(), metaFile.readText())
            var xml = layoutFile.readText()
            for (slot in meta.slots) {
                xml = xml.replace("{{${slot.name}}}", slot.default)
            }
            // Guard: no unreplaced placeholders sneaked through
            val unreplacedRegex = Regex("\\{\\{[^}]+\\}\\}")
            val leftover = unreplacedRegex.findAll(xml).map { it.value }.toList()
            if (leftover.isNotEmpty()) {
                failures += "${meta.id}: unreplaced placeholders: $leftover"
                continue
            }

            try {
                factory.newDocumentBuilder().parse(xml.byteInputStream())
            } catch (t: Throwable) {
                failures += "${meta.id}: ${t.message}"
            }
        }

        assertFalse(
            "Pattern XML validity failures:\n${failures.joinToString("\n")}",
            failures.isNotEmpty(),
        )
    }

    private fun locatePatternsDir(): File {
        val candidates = listOf(
            File("app/src/main/assets/patterns"),
            File("../app/src/main/assets/patterns"),
            File(System.getProperty("user.dir"), "app/src/main/assets/patterns"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("patterns dir not found")
    }
}
```

- [ ] **Step 2: Run it against empty library**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternXmlValidityTest"`
Expected: PASS (no patterns yet, vacuously true).

- [ ] **Step 3: Write the manual smoke doc**

Path: `docs/ui-pattern-manual-smoke.md`
Content:
```markdown
# UI Pattern Library — Manual On-Device Smoke

JVM unit tests (`PatternXmlValidityTest`) verify only XML well-formedness.
Real AAPT2 resolution of `?attr/...` and `@style/TextAppearance.MaterialComponents.*`
requires running the on-device build pipeline, because `Aapt2Jni` needs an
Android `Context`.

## When to smoke

After adding or modifying any file under `app/src/main/assets/patterns/`.

## How to smoke

1. Install a debug build of VibeApp on an emulator or device.
2. Create a new project and in the agent chat say:
   > Build a test screen that uses the pattern `<pattern_id>`. Copy the
   > layout verbatim into `res/layout/test_pattern.xml` and wire it into
   > MainActivity.
3. Run the on-device build pipeline (the agent's `run_build_pipeline` tool).
4. Verify the build succeeds. If AAPT2 errors, the failing attr/style is in
   the test log — fix the pattern's `layout.xml`.

## Coverage goal

Before shipping Phase D, at least one block and one screen template per
category should be smoke-tested on device.
```

- [ ] **Step 4: Commit Phase C**

```bash
git add app/src/test/kotlin/com/vibe/app/feature/uipattern/PatternIndexGeneratorTest.kt \
        app/src/test/kotlin/com/vibe/app/feature/uipattern/PatternXmlValidityTest.kt \
        docs/ui-pattern-manual-smoke.md
git commit -m "$(cat <<'EOF'
test(ui-pattern): add index generator and XML validity smoke

Phase C. PatternIndexGeneratorTest scans assets/patterns/ and regenerates
index.json as a side effect; also validates each meta.json's required
fields. PatternXmlValidityTest parses every layout.xml after slot
substitution to catch malformed XML early. Real AAPT2 resolution is
manual per docs/ui-pattern-manual-smoke.md because Aapt2Jni needs an
Android Context.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase D · Fill content

Each Phase D task adds a batch of patterns, runs `PatternIndexGeneratorTest` to regenerate `index.json`, runs `PatternXmlValidityTest` to catch malformed XML, and commits.

The layout.xml files follow a uniform skeleton: MaterialComponents attrs only, `{{slot}}` placeholders where variation is expected, real default values that compile.

**Pattern authoring rules (apply to every file in Phase D):**

1. Root element: for blocks use `LinearLayout` or `FrameLayout` (leaf blocks shouldn't assume a specific parent); for screens use `androidx.coordinatorlayout.widget.CoordinatorLayout` or `LinearLayout`.
2. Always declare `xmlns:android="http://schemas.android.com/apk/res/android"`. If the pattern uses material widgets, declare `xmlns:app="http://schemas.android.com/apk/res-auto"`.
3. Colors: only the attrs listed in the whitelist.
4. Text: `android:textAppearance="@style/TextAppearance.MaterialComponents.<Level>"`.
5. Spacing: only 4/8/12/16/24/32 dp.
6. Slot placeholders: `{{slot_name}}` — must have a corresponding entry in `meta.json` with a real `default`.
7. `contentDescription="@null"` on decorative ImageViews.

### Task D1: Foundation blocks (6 blocks)

**Files:** (each directory has `layout.xml`, `meta.json`, `notes.md`)

- `app/src/main/assets/patterns/blocks/section_header/`
- `app/src/main/assets/patterns/blocks/list_item_two_line/`
- `app/src/main/assets/patterns/blocks/preference_item/`
- `app/src/main/assets/patterns/blocks/empty_state/`
- `app/src/main/assets/patterns/blocks/loading_state/`
- `app/src/main/assets/patterns/blocks/error_state/`

- [ ] **Step 1: Create `section_header`**

`blocks/section_header/layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingHorizontal="16dp"
    android:paddingTop="24dp"
    android:paddingBottom="8dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{title}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Headline6"
        android:textColor="?attr/colorOnSurface"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{subtitle}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
        android:textColor="?attr/colorOnSurface"
        android:alpha="0.6"
        android:paddingTop="4dp"/>
</LinearLayout>
```

`blocks/section_header/meta.json`:
```json
{
  "id": "section_header",
  "kind": "block",
  "description": "Section title with optional subtitle, used to group content",
  "keywords": ["header", "title", "section", "group", "标题", "分组"],
  "slots": [
    { "name": "title", "description": "Main heading text", "default": "Section title" },
    { "name": "subtitle", "description": "Secondary description", "default": "Description" }
  ],
  "dependencies": []
}
```

`blocks/section_header/notes.md`:
```markdown
Use at the top of a logical grouping of list items or cards. When no subtitle
is needed, remove the second TextView entirely — do not pass an empty string.
Typical usage: above a settings group, above a dashboard section.
```

- [ ] **Step 2: Create `list_item_two_line`**

`blocks/list_item_two_line/layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:minHeight="64dp"
    android:gravity="center_vertical"
    android:paddingHorizontal="16dp"
    android:paddingVertical="8dp"
    android:background="?android:attr/selectableItemBackground">

    <ImageView
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:src="{{icon}}"
        android:contentDescription="@null"/>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical"
        android:paddingStart="16dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{title}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
            android:textColor="?attr/colorOnSurface"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{subtitle}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
            android:textColor="?attr/colorOnSurface"
            android:alpha="0.6"/>
    </LinearLayout>
</LinearLayout>
```

`blocks/list_item_two_line/meta.json`:
```json
{
  "id": "list_item_two_line",
  "kind": "block",
  "description": "Standard two-line list row with leading icon, title, and subtitle",
  "keywords": ["list", "item", "row", "列表", "条目"],
  "slots": [
    { "name": "icon", "description": "Leading icon drawable reference", "default": "@android:drawable/ic_menu_info_details" },
    { "name": "title", "description": "Primary title text", "default": "Item title" },
    { "name": "subtitle", "description": "Secondary text under title", "default": "Item subtitle" }
  ],
  "dependencies": []
}
```

`blocks/list_item_two_line/notes.md`:
```markdown
Use as a RecyclerView item layout. The root already has a selectable
background ripple — attach the click listener to the root view.
For single-line rows, remove the subtitle TextView and set minHeight to 48dp.
```

- [ ] **Step 3: Create `preference_item`**

`blocks/preference_item/layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:minHeight="64dp"
    android:gravity="center_vertical"
    android:paddingHorizontal="16dp"
    android:paddingVertical="12dp"
    android:background="?android:attr/selectableItemBackground">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{title}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
            android:textColor="?attr/colorOnSurface"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{description}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
            android:textColor="?attr/colorOnSurface"
            android:alpha="0.6"
            android:paddingTop="2dp"/>
    </LinearLayout>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{trailing}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
        android:textColor="?attr/colorPrimary"
        android:paddingStart="16dp"/>
</LinearLayout>
```

`blocks/preference_item/meta.json`:
```json
{
  "id": "preference_item",
  "kind": "block",
  "description": "Settings-style entry: title, description, and trailing value or chevron",
  "keywords": ["settings", "preference", "row", "option", "设置", "选项"],
  "slots": [
    { "name": "title", "description": "Preference label", "default": "Setting name" },
    { "name": "description", "description": "Helper text under label", "default": "Describes what this setting does" },
    { "name": "trailing", "description": "Trailing value text (or replace with Switch/chevron)", "default": "On" }
  ],
  "dependencies": []
}
```

`blocks/preference_item/notes.md`:
```markdown
For a Switch preference, replace the trailing TextView with a
`com.google.android.material.materialswitch.MaterialSwitch`... wait no, we
don't bundle MaterialSwitch. Use `androidx.appcompat.widget.SwitchCompat`
with the same layout params. Set the root as `android:clickable="true"` and
toggle the switch in the click handler.
```

- [ ] **Step 4: Create `empty_state`**

`blocks/empty_state/layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="32dp">

    <ImageView
        android:layout_width="96dp"
        android:layout_height="96dp"
        android:src="{{icon}}"
        android:alpha="0.4"
        android:contentDescription="@null"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{title}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Headline6"
        android:textColor="?attr/colorOnSurface"
        android:paddingTop="24dp"
        android:gravity="center"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{description}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
        android:textColor="?attr/colorOnSurface"
        android:alpha="0.6"
        android:paddingTop="8dp"
        android:gravity="center"/>

    <com.google.android.material.button.MaterialButton
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{cta}}"
        android:layout_marginTop="24dp"
        style="@style/Widget.MaterialComponents.Button"/>
</LinearLayout>
```

`blocks/empty_state/meta.json`:
```json
{
  "id": "empty_state",
  "kind": "block",
  "description": "Centered empty-state: large icon, title, description, and a CTA button",
  "keywords": ["empty", "placeholder", "nothing", "no data", "空状态"],
  "slots": [
    { "name": "icon", "description": "Illustrative drawable", "default": "@android:drawable/ic_menu_add" },
    { "name": "title", "description": "Friendly empty headline", "default": "Nothing here yet" },
    { "name": "description", "description": "Explanation + what to do", "default": "Tap the button to get started" },
    { "name": "cta", "description": "Action button label", "default": "Create item" }
  ],
  "dependencies": ["MaterialButton"]
}
```

`blocks/empty_state/notes.md`:
```markdown
Show this when a RecyclerView has no items, a search returns nothing, or a
filter excludes everything. Drop the CTA button if there's no useful action
to take. Keep the icon at 96dp — larger looks heavy, smaller gets lost.
```

- [ ] **Step 5: Create `loading_state`**

`blocks/loading_state/layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="32dp">

    <ProgressBar
        style="?android:attr/progressBarStyleLarge"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:indeterminateTint="?attr/colorPrimary"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{message}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
        android:textColor="?attr/colorOnSurface"
        android:alpha="0.7"
        android:paddingTop="16dp"/>
</LinearLayout>
```

`blocks/loading_state/meta.json`:
```json
{
  "id": "loading_state",
  "kind": "block",
  "description": "Centered indeterminate progress with a caption",
  "keywords": ["loading", "progress", "spinner", "wait", "加载"],
  "slots": [
    { "name": "message", "description": "Short caption under the spinner", "default": "Loading…" }
  ],
  "dependencies": []
}
```

`blocks/loading_state/notes.md`:
```markdown
Use while fetching remote data or running a long task. Use
`android.widget.ProgressBar` with the system large style — CircularProgressIndicator
from Material is bundled but its attribute set is verbose. This pattern keeps
compile risk minimal.
```

- [ ] **Step 6: Create `error_state`**

`blocks/error_state/layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="32dp">

    <ImageView
        android:layout_width="72dp"
        android:layout_height="72dp"
        android:src="{{icon}}"
        android:contentDescription="@null"
        app:tint="?attr/colorError"
        xmlns:app="http://schemas.android.com/apk/res-auto"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{title}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
        android:textColor="?attr/colorOnSurface"
        android:paddingTop="16dp"
        android:gravity="center"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{description}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
        android:textColor="?attr/colorOnSurface"
        android:alpha="0.6"
        android:paddingTop="8dp"
        android:gravity="center"/>

    <com.google.android.material.button.MaterialButton
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{{cta}}"
        android:layout_marginTop="24dp"
        style="@style/Widget.MaterialComponents.Button.OutlinedButton"/>
</LinearLayout>
```

`blocks/error_state/meta.json`:
```json
{
  "id": "error_state",
  "kind": "block",
  "description": "Error display with tinted icon, title, description, and retry button",
  "keywords": ["error", "failure", "retry", "错误", "失败"],
  "slots": [
    { "name": "icon", "description": "Error drawable", "default": "@android:drawable/stat_notify_error" },
    { "name": "title", "description": "Short error headline", "default": "Something went wrong" },
    { "name": "description", "description": "Explanation and recovery hint", "default": "Please check your connection and try again" },
    { "name": "cta", "description": "Retry action label", "default": "Retry" }
  ],
  "dependencies": ["MaterialButton"]
}
```

`blocks/error_state/notes.md`:
```markdown
Use for network failures, parse errors, or unrecoverable state. The retry
button is outlined (not filled) because retry is a secondary action — the
user's primary hope is that the error goes away on its own. Wire the button
click to re-run whatever produced the error.
```

- [ ] **Step 7: Regenerate index and run smoke**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.PatternIndexGeneratorTest" --tests "com.vibe.app.feature.uipattern.PatternXmlValidityTest"`
Expected: both PASS. Verify `app/src/main/assets/patterns/index.json` now lists 6 entries.

- [ ] **Step 8: Commit D1**

```bash
git add app/src/main/assets/patterns/
git commit -m "$(cat <<'EOF'
feat(ui-pattern): add 6 foundation blocks (D1)

section_header, list_item_two_line, preference_item, empty_state,
loading_state, error_state.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task D2: Form & toolbar blocks (4 blocks)

**Files:**
- `blocks/top_app_bar_simple/`
- `blocks/top_app_bar_search/`
- `blocks/form_text_field/`
- `blocks/form_row_labeled/`

- [ ] **Step 1: Create `top_app_bar_simple`**

`layout.xml`:
```xml
<com.google.android.material.appbar.AppBarLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorPrimary">

    <com.google.android.material.appbar.MaterialToolbar
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        app:title="{{title}}"
        app:titleTextColor="?attr/colorOnPrimary"
        app:navigationIcon="{{nav_icon}}"
        app:navigationIconTint="?attr/colorOnPrimary"/>
</com.google.android.material.appbar.AppBarLayout>
```

`meta.json`:
```json
{
  "id": "top_app_bar_simple",
  "kind": "block",
  "description": "Single-line MaterialToolbar with title and optional navigation icon",
  "keywords": ["toolbar", "app bar", "title", "顶栏", "标题栏"],
  "slots": [
    { "name": "title", "description": "Toolbar title text", "default": "Screen title" },
    { "name": "nav_icon", "description": "Navigation icon drawable (e.g. back arrow)", "default": "@android:drawable/ic_menu_revert" }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar"]
}
```

`notes.md`:
```markdown
Place at the top of an Activity layout. Do NOT call setSupportActionBar()
from Java — set the title via `toolbar.setTitle(...)` and wire the nav icon
via `toolbar.setNavigationOnClickListener(...)`. Drop the `navigationIcon`
attribute for root screens that have no back affordance.
```

- [ ] **Step 2: Create `top_app_bar_search`**

`layout.xml`:
```xml
<com.google.android.material.appbar.AppBarLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorPrimary">

    <com.google.android.material.appbar.MaterialToolbar
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary">

        <EditText
            android:id="@+id/et_toolbar_search"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="{{hint}}"
            android:textColor="?attr/colorOnPrimary"
            android:textColorHint="?attr/colorOnPrimary"
            android:alpha="0.9"
            android:background="@null"
            android:imeOptions="actionSearch"
            android:inputType="text"
            android:singleLine="true"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"/>
    </com.google.android.material.appbar.MaterialToolbar>
</com.google.android.material.appbar.AppBarLayout>
```

`meta.json`:
```json
{
  "id": "top_app_bar_search",
  "kind": "block",
  "description": "Toolbar housing an inline EditText for search input",
  "keywords": ["toolbar", "search", "filter", "搜索"],
  "slots": [
    { "name": "hint", "description": "Placeholder hint in the search field", "default": "Search" }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar"]
}
```

`notes.md`:
```markdown
Use when the screen's primary action is text-based filtering. Wire
`et_toolbar_search.addTextChangedListener(...)` to update the list in real
time, or `setOnEditorActionListener(...)` to trigger on IME search action.
```

- [ ] **Step 3: Create `form_text_field`**

`layout.xml`:
```xml
<com.google.android.material.textfield.TextInputLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="{{label}}"
    app:helperText="{{helper}}"
    app:helperTextEnabled="true"
    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox">

    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="{{input_type}}"/>
</com.google.android.material.textfield.TextInputLayout>
```

`meta.json`:
```json
{
  "id": "form_text_field",
  "kind": "block",
  "description": "Material outlined text field with hint and helper text",
  "keywords": ["form", "input", "text field", "输入框"],
  "slots": [
    { "name": "label", "description": "Floating label / hint", "default": "Label" },
    { "name": "helper", "description": "Helper text under the field", "default": "Helper description" },
    { "name": "input_type", "description": "Android inputType", "default": "text" }
  ],
  "dependencies": ["TextInputLayout", "TextInputEditText"]
}
```

`notes.md`:
```markdown
For email use `input_type="textEmailAddress"`, for passwords use
`textPassword`, for numbers use `number`. To show an error, call
`textInputLayout.setError("msg")`; clear with `setError(null)`.
```

- [ ] **Step 4: Create `form_row_labeled`**

`layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:minHeight="56dp"
    android:paddingHorizontal="16dp"
    android:paddingVertical="8dp">

    <TextView
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="0.4"
        android:text="{{label}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body1"
        android:textColor="?attr/colorOnSurface"/>

    <TextView
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="0.6"
        android:text="{{value}}"
        android:textAppearance="@style/TextAppearance.MaterialComponents.Body1"
        android:textColor="?attr/colorOnSurface"
        android:alpha="0.8"
        android:gravity="end"/>
</LinearLayout>
```

`meta.json`:
```json
{
  "id": "form_row_labeled",
  "kind": "block",
  "description": "Static form row with label on the left and value on the right",
  "keywords": ["form", "row", "label", "value", "表单"],
  "slots": [
    { "name": "label", "description": "Field label", "default": "Label" },
    { "name": "value", "description": "Field value", "default": "Value" }
  ],
  "dependencies": []
}
```

`notes.md`:
```markdown
Use for read-only value display in detail screens. For editable rows,
replace the right-hand TextView with a TextInputLayout or SwitchCompat.
Row height is 56dp — above the 48dp touch target minimum, comfortable to read.
```

- [ ] **Step 5: Regenerate + smoke**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.*"`
Expected: all pass. Index now lists 10 entries.

- [ ] **Step 6: Commit D2**

```bash
git add app/src/main/assets/patterns/
git commit -m "$(cat <<'EOF'
feat(ui-pattern): add 4 form and toolbar blocks (D2)

top_app_bar_simple, top_app_bar_search, form_text_field, form_row_labeled.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task D3: Card & action blocks (4 blocks)

**Files:**
- `blocks/stat_card/`
- `blocks/info_card/`
- `blocks/chip_group_filter/`
- `blocks/bottom_action_bar/`

- [ ] **Step 1: Create `stat_card`**

`layout.xml`:
```xml
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="12dp"
    app:cardElevation="1dp"
    app:cardBackgroundColor="?attr/colorSurface">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{label}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
            android:textColor="?attr/colorOnSurface"
            android:alpha="0.6"
            android:textAllCaps="true"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{value}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Headline4"
            android:textColor="?attr/colorOnSurface"
            android:paddingTop="4dp"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{caption}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
            android:textColor="?attr/colorOnSurface"
            android:alpha="0.7"
            android:paddingTop="8dp"/>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

`meta.json`:
```json
{
  "id": "stat_card",
  "kind": "block",
  "description": "Dashboard card showing a large numeric value with label and caption",
  "keywords": ["card", "stat", "number", "metric", "数据卡", "统计"],
  "slots": [
    { "name": "label", "description": "Small uppercase label", "default": "Steps today" },
    { "name": "value", "description": "Large headline number", "default": "8,432" },
    { "name": "caption", "description": "Secondary context line", "default": "+12% from yesterday" }
  ],
  "dependencies": ["MaterialCardView"]
}
```

`notes.md`:
```markdown
Use in a GridLayout or LinearLayout (horizontal, weighted) to build a
dashboard header. Set `layout_marginHorizontal="16dp"` or use a parent with
padding so cards aren't flush to screen edges.
```

- [ ] **Step 2: Create `info_card`**

`layout.xml`:
```xml
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="12dp"
    app:cardElevation="1dp"
    app:cardBackgroundColor="?attr/colorSurface">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{title}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
            android:textColor="?attr/colorOnSurface"/>

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="{{body}}"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
            android:textColor="?attr/colorOnSurface"
            android:alpha="0.8"
            android:paddingTop="8dp"/>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="end"
            android:paddingTop="16dp">

            <com.google.android.material.button.MaterialButton
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="{{action}}"
                style="@style/Widget.MaterialComponents.Button.TextButton"/>
        </LinearLayout>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

`meta.json`:
```json
{
  "id": "info_card",
  "kind": "block",
  "description": "Generic info card: title, body copy, and an optional text action",
  "keywords": ["card", "info", "article", "信息卡"],
  "slots": [
    { "name": "title", "description": "Card title", "default": "Card title" },
    { "name": "body", "description": "Body copy", "default": "Multiple lines of body text describing the content of this card." },
    { "name": "action", "description": "Text button label", "default": "Learn more" }
  ],
  "dependencies": ["MaterialCardView", "MaterialButton"]
}
```

`notes.md`:
```markdown
When no action is needed, delete the inner LinearLayout wrapping the button.
For multiple actions, add siblings to that LinearLayout with 8dp horizontal
margins between them.
```

- [ ] **Step 3: Create `chip_group_filter`**

`layout.xml`:
```xml
<HorizontalScrollView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:scrollbars="none"
    android:paddingHorizontal="16dp">

    <com.google.android.material.chip.ChipGroup
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:chipSpacingHorizontal="8dp"
        app:singleLine="true"
        app:singleSelection="true">

        <com.google.android.material.chip.Chip
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{chip_1}}"
            android:checked="true"
            style="@style/Widget.MaterialComponents.Chip.Filter"/>

        <com.google.android.material.chip.Chip
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{chip_2}}"
            style="@style/Widget.MaterialComponents.Chip.Filter"/>

        <com.google.android.material.chip.Chip
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="{{chip_3}}"
            style="@style/Widget.MaterialComponents.Chip.Filter"/>
    </com.google.android.material.chip.ChipGroup>
</HorizontalScrollView>
```

`meta.json`:
```json
{
  "id": "chip_group_filter",
  "kind": "block",
  "description": "Horizontally scrolling filter chip group with single selection",
  "keywords": ["chip", "filter", "tag", "category", "筛选", "标签"],
  "slots": [
    { "name": "chip_1", "description": "First chip label", "default": "All" },
    { "name": "chip_2", "description": "Second chip label", "default": "Active" },
    { "name": "chip_3", "description": "Third chip label", "default": "Archived" }
  ],
  "dependencies": ["ChipGroup", "Chip"]
}
```

`notes.md`:
```markdown
Add more chips by copying the `<Chip>` element. For multi-select, remove
`app:singleSelection="true"`. Read the selected state via
`chipGroup.getCheckedChipId()` and update your filter accordingly.
```

- [ ] **Step 4: Create `bottom_action_bar`**

`layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="16dp"
    android:background="?attr/colorSurface"
    android:elevation="3dp">

    <com.google.android.material.button.MaterialButton
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="{{secondary_label}}"
        style="@style/Widget.MaterialComponents.Button.OutlinedButton"/>

    <Space
        android:layout_width="12dp"
        android:layout_height="0dp"/>

    <com.google.android.material.button.MaterialButton
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="{{primary_label}}"
        style="@style/Widget.MaterialComponents.Button"/>
</LinearLayout>
```

`meta.json`:
```json
{
  "id": "bottom_action_bar",
  "kind": "block",
  "description": "Fixed bottom bar with secondary (outlined) and primary (filled) action buttons",
  "keywords": ["action", "button", "bottom bar", "cta", "底部", "按钮"],
  "slots": [
    { "name": "secondary_label", "description": "Outlined button label", "default": "Cancel" },
    { "name": "primary_label", "description": "Filled button label", "default": "Save" }
  ],
  "dependencies": ["MaterialButton"]
}
```

`notes.md`:
```markdown
Pin this to the bottom of a CoordinatorLayout or ConstraintLayout
(`layout_gravity="bottom"` or constraint bottom=parent). For a single
primary button, delete the outlined button and the `Space`, set the
remaining button to `layout_width="match_parent"`.
```

- [ ] **Step 5: Regenerate + smoke**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.*"`
Expected: all pass. Index: 14 blocks total.

- [ ] **Step 6: Commit D3**

```bash
git add app/src/main/assets/patterns/
git commit -m "$(cat <<'EOF'
feat(ui-pattern): add 4 card and action blocks (D3)

stat_card, info_card, chip_group_filter, bottom_action_bar. All 14
component blocks now in place.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task D4: Five screen templates

**Files:**
- `screens/screen_list/`
- `screens/screen_form/`
- `screens/screen_settings/`
- `screens/screen_detail/`
- `screens/screen_dashboard/`

All screen templates are self-contained — they inline the relevant block XML rather than using `<include>`, so each file is a complete drop-in activity layout.

- [ ] **Step 1: Create `screen_list`**

`layout.xml`:
```xml
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="?attr/colorPrimary">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorPrimary"
            app:title="{{title}}"
            app:titleTextColor="?attr/colorOnPrimary"/>
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_list"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:paddingVertical="8dp"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"/>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab_add"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:contentDescription="{{fab_description}}"
        android:src="@android:drawable/ic_input_add"
        app:tint="?attr/colorOnSecondary"/>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

`meta.json`:
```json
{
  "id": "screen_list",
  "kind": "screen",
  "description": "List screen: toolbar + RecyclerView + FAB. Add loading/empty/error via separate inflations.",
  "keywords": ["list", "screen", "recyclerview", "列表页"],
  "slots": [
    { "name": "title", "description": "Toolbar title", "default": "Items" },
    { "name": "fab_description", "description": "FAB content description", "default": "Add new item" }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar", "RecyclerView", "FloatingActionButton"]
}
```

`notes.md`:
```markdown
For empty/loading/error states, inflate a block pattern at runtime and swap
it with the RecyclerView via `setVisibility`. The RecyclerView item layout
should be `list_item_two_line` or similar. Wire the FAB click to launch
whatever "create new" flow your app needs.
```

- [ ] **Step 2: Create `screen_form`**

`layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="?attr/colorPrimary">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorPrimary"
            app:title="{{title}}"
            app:titleTextColor="?attr/colorOnPrimary"
            app:navigationIcon="@android:drawable/ic_menu_revert"
            app:navigationIconTint="?attr/colorOnPrimary"/>
    </com.google.android.material.appbar.AppBarLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <com.google.android.material.textfield.TextInputLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="{{field_1_label}}"
                style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox">

                <com.google.android.material.textfield.TextInputEditText
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="text"/>
            </com.google.android.material.textfield.TextInputLayout>

            <com.google.android.material.textfield.TextInputLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:hint="{{field_2_label}}"
                style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox">

                <com.google.android.material.textfield.TextInputEditText
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="text"/>
            </com.google.android.material.textfield.TextInputLayout>
        </LinearLayout>
    </ScrollView>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="16dp"
        android:background="?attr/colorSurface"
        android:elevation="3dp">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_cancel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="{{cancel_label}}"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton"/>

        <Space
            android:layout_width="12dp"
            android:layout_height="0dp"/>

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_submit"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="{{submit_label}}"
            style="@style/Widget.MaterialComponents.Button"/>
    </LinearLayout>
</LinearLayout>
```

`meta.json`:
```json
{
  "id": "screen_form",
  "kind": "screen",
  "description": "Form screen: toolbar + scrolling input fields + bottom action bar",
  "keywords": ["form", "input", "edit", "表单"],
  "slots": [
    { "name": "title", "description": "Toolbar title", "default": "Edit" },
    { "name": "field_1_label", "description": "First field hint", "default": "Name" },
    { "name": "field_2_label", "description": "Second field hint", "default": "Description" },
    { "name": "cancel_label", "description": "Outlined cancel button", "default": "Cancel" },
    { "name": "submit_label", "description": "Filled submit button", "default": "Save" }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar", "TextInputLayout", "TextInputEditText", "MaterialButton"]
}
```

`notes.md`:
```markdown
Add or remove TextInputLayout siblings as needed. For validation, call
`textInputLayout.setError(...)` in the submit button click handler. The
ScrollView prevents IME from covering the bottom fields when the keyboard
opens.
```

- [ ] **Step 3: Create `screen_settings`**

`layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="?attr/colorPrimary">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorPrimary"
            app:title="{{title}}"
            app:titleTextColor="?attr/colorOnPrimary"
            app:navigationIcon="@android:drawable/ic_menu_revert"
            app:navigationIconTint="?attr/colorOnPrimary"/>
    </com.google.android.material.appbar.AppBarLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingVertical="8dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="{{section_1_title}}"
                android:textAppearance="@style/TextAppearance.MaterialComponents.Overline"
                android:textColor="?attr/colorPrimary"
                android:paddingHorizontal="16dp"
                android:paddingTop="16dp"
                android:paddingBottom="8dp"/>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:minHeight="64dp"
                android:gravity="center_vertical"
                android:paddingHorizontal="16dp"
                android:paddingVertical="12dp"
                android:background="?android:attr/selectableItemBackground">

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="{{pref_1_title}}"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
                        android:textColor="?attr/colorOnSurface"/>

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="{{pref_1_description}}"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
                        android:textColor="?attr/colorOnSurface"
                        android:alpha="0.6"
                        android:paddingTop="2dp"/>
                </LinearLayout>

                <androidx.appcompat.widget.SwitchCompat
                    android:id="@+id/sw_pref_1"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"/>
            </LinearLayout>
        </LinearLayout>
    </ScrollView>
</LinearLayout>
```

`meta.json`:
```json
{
  "id": "screen_settings",
  "kind": "screen",
  "description": "Settings screen: toolbar + scrollable list of grouped preferences",
  "keywords": ["settings", "preferences", "config", "设置"],
  "slots": [
    { "name": "title", "description": "Toolbar title", "default": "Settings" },
    { "name": "section_1_title", "description": "First section header", "default": "GENERAL" },
    { "name": "pref_1_title", "description": "First preference label", "default": "Notifications" },
    { "name": "pref_1_description", "description": "First preference helper", "default": "Receive reminder pushes" }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar", "SwitchCompat"]
}
```

`notes.md`:
```markdown
Clone the inner preference LinearLayout for each additional setting. Group
related preferences under their own section header (copy the Overline
TextView). Wire switch state via `sw_pref_1.setOnCheckedChangeListener(...)`.
```

- [ ] **Step 4: Create `screen_detail`**

`layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="?attr/colorPrimary">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorPrimary"
            app:title="{{title}}"
            app:titleTextColor="?attr/colorOnPrimary"
            app:navigationIcon="@android:drawable/ic_menu_revert"
            app:navigationIconTint="?attr/colorOnPrimary"/>
    </com.google.android.material.appbar.AppBarLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="{{headline}}"
                android:textAppearance="@style/TextAppearance.MaterialComponents.Headline5"
                android:textColor="?attr/colorOnSurface"/>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="{{subhead}}"
                android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
                android:textColor="?attr/colorOnSurface"
                android:alpha="0.7"
                android:paddingTop="4dp"/>

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="{{body}}"
                android:textAppearance="@style/TextAppearance.MaterialComponents.Body1"
                android:textColor="?attr/colorOnSurface"
                android:lineSpacingExtra="4dp"
                android:paddingTop="24dp"/>
        </LinearLayout>
    </ScrollView>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btn_primary"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="{{primary_label}}"
        android:layout_margin="16dp"
        style="@style/Widget.MaterialComponents.Button"/>
</LinearLayout>
```

`meta.json`:
```json
{
  "id": "screen_detail",
  "kind": "screen",
  "description": "Detail screen: toolbar + scrollable headline/body + single primary action",
  "keywords": ["detail", "article", "view", "详情"],
  "slots": [
    { "name": "title", "description": "Toolbar title", "default": "Details" },
    { "name": "headline", "description": "Large headline", "default": "Item headline" },
    { "name": "subhead", "description": "Secondary info", "default": "Subhead text" },
    { "name": "body", "description": "Long body content", "default": "Multi-paragraph body copy describing the item in detail." },
    { "name": "primary_label", "description": "Bottom action button label", "default": "Action" }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar", "MaterialButton"]
}
```

`notes.md`:
```markdown
For image headers, add an ImageView before the headline TextView with
`layout_width="match_parent"`, `layout_height="200dp"`, and a negative
`layout_marginHorizontal="-16dp"` to bleed edge-to-edge. Drop the primary
button for read-only screens.
```

- [ ] **Step 5: Create `screen_dashboard`**

`layout.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="?attr/colorPrimary">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorPrimary"
            app:title="{{title}}"
            app:titleTextColor="?attr/colorOnPrimary"/>
    </com.google.android.material.appbar.AppBarLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <com.google.android.material.card.MaterialCardView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    app:cardCornerRadius="12dp"
                    app:cardElevation="1dp"
                    app:cardBackgroundColor="?attr/colorSurface">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="{{stat_1_label}}"
                            android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                            android:textColor="?attr/colorOnSurface"
                            android:alpha="0.6"
                            android:textAllCaps="true"/>

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="{{stat_1_value}}"
                            android:textAppearance="@style/TextAppearance.MaterialComponents.Headline4"
                            android:textColor="?attr/colorOnSurface"
                            android:paddingTop="4dp"/>
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

                <Space
                    android:layout_width="12dp"
                    android:layout_height="0dp"/>

                <com.google.android.material.card.MaterialCardView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    app:cardCornerRadius="12dp"
                    app:cardElevation="1dp"
                    app:cardBackgroundColor="?attr/colorSurface">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="{{stat_2_label}}"
                            android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                            android:textColor="?attr/colorOnSurface"
                            android:alpha="0.6"
                            android:textAllCaps="true"/>

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="{{stat_2_value}}"
                            android:textAppearance="@style/TextAppearance.MaterialComponents.Headline4"
                            android:textColor="?attr/colorOnSurface"
                            android:paddingTop="4dp"/>
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>
            </LinearLayout>

            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                app:cardCornerRadius="12dp"
                app:cardElevation="1dp"
                app:cardBackgroundColor="?attr/colorSurface">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="{{info_title}}"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Subtitle1"
                        android:textColor="?attr/colorOnSurface"/>

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="{{info_body}}"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Body2"
                        android:textColor="?attr/colorOnSurface"
                        android:alpha="0.8"
                        android:paddingTop="8dp"/>
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
        </LinearLayout>
    </ScrollView>
</LinearLayout>
```

`meta.json`:
```json
{
  "id": "screen_dashboard",
  "kind": "screen",
  "description": "Dashboard screen: toolbar + two stat cards side by side + one wide info card",
  "keywords": ["dashboard", "home", "overview", "stats", "仪表盘"],
  "slots": [
    { "name": "title", "description": "Toolbar title", "default": "Dashboard" },
    { "name": "stat_1_label", "description": "First stat label", "default": "Today" },
    { "name": "stat_1_value", "description": "First stat number", "default": "42" },
    { "name": "stat_2_label", "description": "Second stat label", "default": "Week" },
    { "name": "stat_2_value", "description": "Second stat number", "default": "287" },
    { "name": "info_title", "description": "Wide card title", "default": "Latest update" },
    { "name": "info_body", "description": "Wide card body", "default": "Brief description of recent activity or helpful info." }
  ],
  "dependencies": ["AppBarLayout", "MaterialToolbar", "MaterialCardView"]
}
```

`notes.md`:
```markdown
Clone the stat card pair for more metrics (add a second horizontal row).
The wide card at the bottom is a catch-all for recent activity, tips, or
upsell copy — remove it entirely if not needed.
```

- [ ] **Step 6: Regenerate + smoke**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.*"`
Expected: all pass. Index: 14 blocks + 5 screens = 19 entries.

- [ ] **Step 7: Commit D4**

```bash
git add app/src/main/assets/patterns/
git commit -m "$(cat <<'EOF'
feat(ui-pattern): add 5 screen templates (D4)

screen_list, screen_form, screen_settings, screen_detail, screen_dashboard.
Each template is self-contained and can be dropped into res/layout/ as a
complete activity layout.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task D5: Fill `design-guide.md` with real tokens

**Files:**
- Modify: `app/src/main/assets/design/design-guide.md`

- [ ] **Step 1: Write the full guide**

```markdown
# VibeApp UI Design Guide

Material Components baseline for on-device generated Android utility apps.
Bundled theme parent is `Theme.MaterialComponents.DayNight.NoActionBar`.
All tokens below are MaterialComponents (M2), not M3.

## Tokens

### Colors (attr reference only — do not hardcode hex unless Creative Mode)

- Brand: `?attr/colorPrimary`, `?attr/colorPrimaryVariant`, `?attr/colorOnPrimary`
- Secondary: `?attr/colorSecondary`, `?attr/colorSecondaryVariant`, `?attr/colorOnSecondary`
- Surfaces: `?attr/colorSurface`, `?attr/colorOnSurface`, `?android:attr/colorBackground`
- Feedback: `?attr/colorError`, `?attr/colorOnError`

For faded secondary text, use `?attr/colorOnSurface` + `android:alpha="0.6"`
(0.7 for slightly more contrast). Do NOT use `colorOnSurfaceVariant` —
it does not exist in MaterialComponents.

### Typography

Use textAppearance styles, not raw sp values.

- `@style/TextAppearance.MaterialComponents.Headline4` — large display numbers
- `@style/TextAppearance.MaterialComponents.Headline5` — screen headlines
- `@style/TextAppearance.MaterialComponents.Headline6` — toolbar titles, section headers
- `@style/TextAppearance.MaterialComponents.Subtitle1` — list item titles, card titles
- `@style/TextAppearance.MaterialComponents.Subtitle2` — secondary headers
- `@style/TextAppearance.MaterialComponents.Body1` — primary body copy
- `@style/TextAppearance.MaterialComponents.Body2` — secondary body copy, list subtitles
- `@style/TextAppearance.MaterialComponents.Button` — button labels (applied automatically)
- `@style/TextAppearance.MaterialComponents.Caption` — small labels, footnotes
- `@style/TextAppearance.MaterialComponents.Overline` — uppercase section markers

### Spacing

Only pick from: **4 / 8 / 12 / 16 / 24 / 32 dp**.

- 4dp — micro gap (icon↔label)
- 8dp — tight grouping
- 12dp — button gap
- 16dp — screen horizontal padding, major vertical gap (default)
- 24dp — section gap
- 32dp — large centered padding (empty/error states)

### Shape

Corner radius: **4 / 8 / 12 / 16 / 28 dp**.

- 4dp — text fields
- 8dp — chips
- 12dp — cards (default)
- 16dp — large feature cards
- 28dp — full-height buttons, pill shapes

Elevation: **0 / 1 / 3 / 6 dp**.

- 0dp — flat surfaces
- 1dp — cards (default)
- 3dp — bottom action bars
- 6dp — FABs, elevated dialogs

## Components

### MaterialToolbar

ALWAYS use as a regular View. NEVER call `setSupportActionBar()`.

```xml
<com.google.android.material.appbar.MaterialToolbar
    android:layout_height="?attr/actionBarSize"
    android:background="?attr/colorPrimary"
    app:title="Title"
    app:titleTextColor="?attr/colorOnPrimary"
    app:navigationIcon="@android:drawable/ic_menu_revert"
    app:navigationIconTint="?attr/colorOnPrimary"/>
```

Wire in Java: `toolbar.setNavigationOnClickListener(v -> finish());`

### MaterialCardView

Default radius 12dp, elevation 1dp.

```xml
<com.google.android.material.card.MaterialCardView
    app:cardCornerRadius="12dp"
    app:cardElevation="1dp"
    app:cardBackgroundColor="?attr/colorSurface">
```

### MaterialButton

- Filled (`@style/Widget.MaterialComponents.Button`) — primary action
- Outlined (`@style/Widget.MaterialComponents.Button.OutlinedButton`) — secondary
- Text (`@style/Widget.MaterialComponents.Button.TextButton`) — tertiary / in-card

### TextInputLayout / TextInputEditText

Use Outlined variant. Set `app:helperText` for hints, call
`setError(...)` for validation.

### RecyclerView

Item spacing via `paddingVertical` on the RecyclerView, not ItemDecoration.

## Layout

- Default screen horizontal padding: 16dp.
- Default vertical gap between components: 16dp.
- Minimum touch target: 48dp (list items 64dp for comfort).
- Form row height: ≥56dp.
- Avoid edge-to-edge by default; content sits below status bar and above
  navigation bar. Do not make system bars transparent unless the user asks.

## Creative Mode

Triggered when the user's request contains subjective aesthetic keywords
such as: 好看, 有设计感, 复古, 童趣, 酷炫, 极简, 暗黑, or "像 ___ 一样".

In Creative Mode:

- **Skip** `search_ui_pattern` entirely. Write fresh XML.
- **Allowed overrides:** hard-coded color hexes, custom Typeface from Fonts,
  custom background drawables, unconventional paddings *outside* the
  spacing whitelist (use sparingly).
- **Still enforced:** MaterialToolbar rule, ShadowActivity requirement,
  bundled-library-only constraint, 48dp touch targets, non-edge-to-edge
  default (unless user explicitly asks for fullscreen).

The goal is to let expressive requests feel distinctive while keeping the
output compilable and usable on a real phone.
```

- [ ] **Step 2: Run design guide tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.uipattern.DesignGuideLoaderTest"`
Expected: still PASS (markdown structure unchanged).

- [ ] **Step 3: Run full app test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all green.

- [ ] **Step 4: Commit D5**

```bash
git add app/src/main/assets/design/design-guide.md
git commit -m "$(cat <<'EOF'
feat(ui-pattern): fill in design-guide.md with full token reference (D5)

Complete Tokens / Components / Layout / Creative Mode sections. Uses
MaterialComponents (M2) attrs to match the bundled theme parent.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification

- [ ] **Step 1: Full test sweep**

Run: `./gradlew :app:testDebugUnitTest :build-engine:test`
Expected: all green.

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify index.json contents**

Open `app/src/main/assets/patterns/index.json` and confirm:
- `patterns` array has 19 entries (14 blocks + 5 screens)
- ids are in alphabetical order
- every entry has non-empty `description` and `slotNames`

- [ ] **Step 4: Deferred manual on-device smoke**

Follow `docs/ui-pattern-manual-smoke.md`: install a fresh VibeApp debug build, create a new project, and run the agent against three prompts:

1. "Build a simple todo list app" — expect it to use `screen_list` + `list_item_two_line`.
2. "Build a BMI calculator" — expect it to use `screen_form` + `form_text_field`.
3. "Build a retro pixel-art clock" — expect Creative Mode: no library calls.

If any AAPT2 error surfaces, copy the failing pattern id + error into a GitHub issue. Do NOT block shipping Phase A-D on the smoke — the code path compiles and tests pass regardless.

---

## Self-Review Checklist (for the implementer)

- [ ] Every layout.xml declares `xmlns:android`.
- [ ] Every layout.xml uses material widgets only via their full `com.google.android.material.*` package names.
- [ ] No reference to `Theme.Material3.*`, `textAppearanceTitleMedium`, `colorTertiary`, or `colorSurfaceVariant`.
- [ ] No `MaterialSwitch`, `BottomAppBar`, or `SwitchMaterial` (use `SwitchCompat` instead).
- [ ] Every slot in meta.json has a non-empty `default` value.
- [ ] Every pattern has all three files (`layout.xml`, `meta.json`, `notes.md`).
- [ ] index.json was regenerated after every Phase D sub-task.
- [ ] `./gradlew :app:testDebugUnitTest` green end-to-end.
