# Icon Preset Library Implementation Plan

> **For agentic workers:** Implement this plan task-by-task in order. Checkbox (`- [ ]`) steps. Commit at the end of each task.

**Goal:** Replace hand-written `pathData` icon generation with a preset icon library (Lucide) + parameterized assembly, so the agent can reliably produce launcher icons whose graphics are correct.

**Architecture:** Bundle Lucide's Iconify JSON in `app/src/main/assets/icons/`. Add an in-process `IconLibrary` (load + keyword search) and a pure SVG→Android `pathData` converter. Expose three agent tools: `search_icon`, `update_project_icon` (new signature: iconId + colors + background style) and `update_project_icon_custom` (escape hatch preserving the old raw-XML behavior). Keep the existing `ProjectIconRenderer` for PNG fallback.

**Tech Stack:** Kotlin, Jetpack (AssetManager), kotlinx.serialization, Hilt, JUnit4.

**Out of scope (v1):** Material Symbols / Phosphor sets, fuzzy search, recoloring the non-`currentColor` Lucide icons (Lucide is 100% `currentColor`, so N/A), runtime icon preview in chat UI.

---

## Background

Current `UpdateProjectIconTool` accepts two raw `<vector>` XML strings authored by the LLM. LLMs generate path coordinate geometry poorly, so the resulting launcher icons are visually broken. Research and design rationale: see `docs/icon-preset-library-strategy.md`.

## File Structure

### New files

| File | Responsibility |
|---|---|
| `app/src/main/assets/icons/lucide.json` | Full Iconify Lucide icon set (~550 KB, ISC license). |
| `app/src/main/assets/icons/LICENSE-lucide.txt` | ISC license copy. |
| `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifyJson.kt` | `@Serializable` models for the JSON schema. |
| `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconLibrary.kt` | Lazy loader + `search(keyword)` + `get(id)`. Takes an `InputStream` provider so tests can bypass Android `AssetManager`. |
| `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifySvgConverter.kt` | Pure function: `convert(body: String): List<PathEntry>`. Parses `<path>/<circle>/<rect>/<line>/<polyline>/<polygon>` from an SVG body fragment, converts each to Android `pathData`, preserves per-element stroke attrs. No Android dependencies — unit-testable. |
| `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconVectorDrawableBuilder.kt` | Assembles full foreground & background vector-drawable XML strings. Scales the 24×24 icon into the 108×108 canvas inside the 66×66 safe zone (translate=21, scale=2.75). Produces background XML for `solid`, `linearGradient`, `radialGradient` styles. |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/IconTools.kt` | New home for icon-related agent tools: `SearchIconTool`, refactored `UpdateProjectIconTool`, `UpdateProjectIconCustomTool`. |
| `app/src/main/kotlin/com/vibe/app/di/IconLibraryModule.kt` | Hilt `@Provides @Singleton` for `IconLibrary`, constructing it with an `AssetManager`-backed stream provider. |
| `app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifySvgConverterTest.kt` | Covers each SVG primitive. |
| `app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconLibraryTest.kt` | Parses a minimal in-memory JSON, checks `search` + `get`. |
| `app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconVectorDrawableBuilderTest.kt` | Golden XML snapshot for a known icon + each background style. |

### Modified files

| File | Change |
|---|---|
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectManagementTools.kt` | Remove `UpdateProjectIconTool` (moved to `IconTools.kt`). |
| `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt` | Add `@Binds` entries for `SearchIconTool` and `UpdateProjectIconCustomTool`; update import for `UpdateProjectIconTool` to new package location. |
| `app/src/main/assets/agent-system-prompt.md` | Rewrite the "App Icon Requests" section to teach the new workflow. |

---

## Task 1: Bundle Lucide assets

**Files:**
- Create: `app/src/main/assets/icons/lucide.json`
- Create: `app/src/main/assets/icons/LICENSE-lucide.txt`

- [ ] **Step 1: Create the assets directory and download the icon set**

Run:
```bash
mkdir -p app/src/main/assets/icons
curl -sL -o app/src/main/assets/icons/lucide.json \
  https://raw.githubusercontent.com/iconify/icon-sets/master/json/lucide.json
curl -sL -o app/src/main/assets/icons/LICENSE-lucide.txt \
  https://raw.githubusercontent.com/lucide-icons/lucide/main/LICENSE
```

Expected: `lucide.json` ≈ 550 KB, `LICENSE-lucide.txt` short ISC text. Verify with `ls -lh app/src/main/assets/icons/`.

- [ ] **Step 2: Spot-check contents**

Expected `lucide.json` begins with `{"prefix": "lucide",`, contains `"icons":`, and includes at least icons `house`, `check`, `star`, `user`, `sun`, `cloud`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/icons/lucide.json app/src/main/assets/icons/LICENSE-lucide.txt
git commit -m "feat(icon): bundle Lucide iconify JSON (ISC) under assets/icons"
```

---

## Task 2: Iconify data models

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifyJson.kt`

- [ ] **Step 1: Write the models**

```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the Iconify JSON schema we actually need.
 * Fields we don't use are intentionally omitted — ignoreUnknownKeys on the Json instance.
 */
@Serializable
internal data class IconifyIconSet(
    val prefix: String,
    val icons: Map<String, IconifyIconEntry> = emptyMap(),
    val aliases: Map<String, IconifyAlias> = emptyMap(),
    val categories: Map<String, List<String>> = emptyMap(),
    val width: Int = 24,
    val height: Int = 24,
)

@Serializable
internal data class IconifyIconEntry(
    val body: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class IconifyAlias(
    val parent: String,
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifyJson.kt
git commit -m "feat(icon): add Iconify JSON data models"
```

---

## Task 3: IconLibrary loader + search

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconLibrary.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconLibraryTest.kt`

- [ ] **Step 1: Write the library**

`IconLibrary.kt`:
```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

import java.io.InputStream
import kotlinx.serialization.json.Json

/**
 * In-process catalogue of preset launcher icons.
 *
 * Loads lazily on first access. The stream is only read once. Tests inject a
 * fake [InputStreamProvider] so they don't need Android AssetManager.
 */
class IconLibrary(
    private val streamProvider: InputStreamProvider,
) {
    fun interface InputStreamProvider {
        fun open(): InputStream
    }

    data class IconRecord(
        val id: String,             // e.g. "house"
        val body: String,           // raw SVG inner content
        val width: Int,
        val height: Int,
        val categories: List<String>,
    )

    data class SearchHit(
        val id: String,
        val categories: List<String>,
    )

    private val data: Loaded by lazy { load() }

    private data class Loaded(
        val byId: Map<String, IconRecord>,
        val orderedIds: List<String>,
    )

    private fun load(): Loaded {
        val json = Json { ignoreUnknownKeys = true }
        val raw = streamProvider.open().use { it.readBytes().decodeToString() }
        val set = json.decodeFromString(IconifyIconSet.serializer(), raw)

        // Build reverse index: icon id -> list of categories containing it.
        val iconToCategories = HashMap<String, MutableList<String>>()
        for ((cat, ids) in set.categories) {
            for (id in ids) {
                iconToCategories.getOrPut(id) { mutableListOf() }.add(cat)
            }
        }

        val byId = LinkedHashMap<String, IconRecord>(set.icons.size)
        for ((id, entry) in set.icons) {
            byId[id] = IconRecord(
                id = id,
                body = entry.body,
                width = entry.width ?: set.width,
                height = entry.height ?: set.height,
                categories = iconToCategories[id].orEmpty(),
            )
        }
        // Expand aliases pointing at a resolvable parent.
        for ((alias, target) in set.aliases) {
            val parent = byId[target.parent] ?: continue
            byId.putIfAbsent(alias, parent.copy(id = alias))
        }
        return Loaded(byId = byId, orderedIds = byId.keys.toList())
    }

    fun get(id: String): IconRecord? = data.byId[id]

    fun size(): Int = data.byId.size

    /**
     * Substring search over icon id + category names (case-insensitive).
     * Matches that hit the id are ranked first, then category matches.
     * Returns at most [limit] hits.
     */
    fun search(keyword: String, limit: Int = 20): List<SearchHit> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val idHits = ArrayList<SearchHit>()
        val categoryHits = ArrayList<SearchHit>()

        for (id in data.orderedIds) {
            val record = data.byId[id] ?: continue
            val idMatch = id.contains(needle)
            val catMatch = !idMatch && record.categories.any { it.lowercase().contains(needle) }
            if (idMatch) {
                idHits += SearchHit(id, record.categories)
                if (idHits.size >= limit) break
            } else if (catMatch) {
                categoryHits += SearchHit(id, record.categories)
            }
        }

        val combined = ArrayList<SearchHit>(idHits.size + categoryHits.size)
        combined.addAll(idHits)
        for (hit in categoryHits) {
            if (combined.size >= limit) break
            combined.add(hit)
        }
        return combined
    }
}
```

- [ ] **Step 2: Write the test**

`IconLibraryTest.kt`:
```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconLibraryTest {

    private val sampleJson = """
        {
          "prefix": "lucide",
          "icons": {
            "house":     { "body": "<path d='M1 1'/>" },
            "home-plus": { "body": "<path d='M2 2'/>" },
            "star":      { "body": "<path d='M3 3'/>" }
          },
          "aliases": {
            "home": { "parent": "house" }
          },
          "categories": {
            "Buildings": ["house", "home-plus"],
            "Shapes":    ["star"]
          },
          "width": 24,
          "height": 24
        }
    """.trimIndent()

    private fun library() = IconLibrary { ByteArrayInputStream(sampleJson.toByteArray()) }

    @Test
    fun `loads icons and expands aliases`() {
        val lib = library()
        assertEquals(4, lib.size()) // house, home-plus, star, home(alias)
        assertNotNull(lib.get("house"))
        assertNotNull(lib.get("home"))
        assertEquals("<path d='M1 1'/>", lib.get("home")?.body)
        assertNull(lib.get("nope"))
    }

    @Test
    fun `search ranks id matches before category matches`() {
        val hits = library().search("house")
        assertEquals("house", hits.first().id)
    }

    @Test
    fun `search matches by category name when id does not contain keyword`() {
        val hits = library().search("building")
        assertTrue(hits.any { it.id == "house" })
        assertTrue(hits.any { it.id == "home-plus" })
    }

    @Test
    fun `search respects the limit`() {
        val hits = library().search("h", limit = 2)
        assertEquals(2, hits.size)
    }
}
```

- [ ] **Step 3: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "*IconLibraryTest*"
```

Expected: all four tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconLibrary.kt \
        app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconLibraryTest.kt
git commit -m "feat(icon): add IconLibrary loader with keyword search"
```

---

## Task 4: IconifySvgConverter

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifySvgConverter.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifySvgConverterTest.kt`

**Why:** Lucide icons use 6 SVG primitives: `<path>`, `<circle>`, `<rect>`, `<line>`, `<polyline>`, `<polygon>`. We convert each to an Android `<path android:pathData>` entry, preserving per-element stroke attributes (`stroke-width`, `stroke-linecap`, `stroke-linejoin`). Fill is ignored for v1 because Lucide sets `fill="none"` everywhere. `<g transform>` is not supported — if encountered, the entry is still parsed but transform is dropped with a logged warning (Lucide does not use `<g>` wrappers).

- [ ] **Step 1: Write the converter**

```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Pure SVG-body → Android vector-drawable path converter.
 *
 * Input: the inner SVG body of an Iconify icon (e.g. <path .../><circle .../>)
 * Output: a list of [PathEntry] each ready to be rendered as <path android:pathData=...>.
 *
 * Handles: path, circle, rect (with rx/ry), line, polyline, polygon.
 * Ignores: <g transform> (logged at call site if needed).
 */
object IconifySvgConverter {

    data class PathEntry(
        val pathData: String,
        val strokeWidth: Float,
        val strokeLineCap: String,    // "round" | "square" | "butt"
        val strokeLineJoin: String,   // "round" | "bevel" | "miter"
    )

    private val factory: XmlPullParserFactory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = false
    }

    fun convert(body: String): List<PathEntry> {
        // Wrap in a synthetic root so the pull parser has a single document element.
        val wrapped = "<svgroot>$body</svgroot>"
        val parser = factory.newPullParser()
        parser.setInput(StringReader(wrapped))

        val out = ArrayList<PathEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val data = when (parser.name) {
                    "path"     -> parser.getAttributeValue(null, "d")
                    "circle"   -> circleToPath(parser)
                    "rect"     -> rectToPath(parser)
                    "line"     -> lineToPath(parser)
                    "polyline" -> polylineToPath(parser, close = false)
                    "polygon"  -> polylineToPath(parser, close = true)
                    else -> null
                }
                if (!data.isNullOrBlank()) {
                    out += PathEntry(
                        pathData = data.trim(),
                        strokeWidth = parser.getAttributeValue(null, "stroke-width")
                            ?.toFloatOrNull() ?: 2f,
                        strokeLineCap = parser.getAttributeValue(null, "stroke-linecap")
                            ?: "round",
                        strokeLineJoin = parser.getAttributeValue(null, "stroke-linejoin")
                            ?: "round",
                    )
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun XmlPullParser.numAttr(name: String, default: Float = 0f): Float =
        getAttributeValue(null, name)?.toFloatOrNull() ?: default

    private fun circleToPath(p: XmlPullParser): String {
        val cx = p.numAttr("cx")
        val cy = p.numAttr("cy")
        val r = p.numAttr("r")
        if (r <= 0f) return ""
        // Two half-arcs make a full circle.
        return "M${cx - r},${cy}a${r},${r} 0 1,0 ${r * 2},0a${r},${r} 0 1,0 -${r * 2},0Z"
    }

    private fun rectToPath(p: XmlPullParser): String {
        val x = p.numAttr("x")
        val y = p.numAttr("y")
        val w = p.numAttr("width")
        val h = p.numAttr("height")
        if (w <= 0f || h <= 0f) return ""
        val rxAttr = p.getAttributeValue(null, "rx")?.toFloatOrNull()
        val ryAttr = p.getAttributeValue(null, "ry")?.toFloatOrNull()
        val rx = (rxAttr ?: ryAttr ?: 0f).coerceAtMost(w / 2f).coerceAtLeast(0f)
        val ry = (ryAttr ?: rxAttr ?: 0f).coerceAtMost(h / 2f).coerceAtLeast(0f)
        if (rx == 0f && ry == 0f) {
            return "M$x,${y}h${w}v${h}h-${w}Z"
        }
        // Rounded rectangle with four arcs.
        return buildString {
            append("M${x + rx},$y")
            append("h${w - 2 * rx}")
            append("a$rx,$ry 0 0 1 $rx,$ry")
            append("v${h - 2 * ry}")
            append("a$rx,$ry 0 0 1 -$rx,$ry")
            append("h-${w - 2 * rx}")
            append("a$rx,$ry 0 0 1 -$rx,-$ry")
            append("v-${h - 2 * ry}")
            append("a$rx,$ry 0 0 1 $rx,-$ry")
            append("Z")
        }
    }

    private fun lineToPath(p: XmlPullParser): String {
        val x1 = p.numAttr("x1"); val y1 = p.numAttr("y1")
        val x2 = p.numAttr("x2"); val y2 = p.numAttr("y2")
        return "M$x1,${y1}L$x2,$y2"
    }

    private fun polylineToPath(p: XmlPullParser, close: Boolean): String {
        val points = p.getAttributeValue(null, "points")?.trim().orEmpty()
        if (points.isEmpty()) return ""
        // Normalize separators (commas and whitespace).
        val nums = points.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (nums.size < 4 || nums.size % 2 != 0) return ""
        return buildString {
            append("M${nums[0]},${nums[1]}")
            var i = 2
            while (i < nums.size) {
                append("L${nums[i]},${nums[i + 1]}")
                i += 2
            }
            if (close) append("Z")
        }
    }
}
```

- [ ] **Step 2: Write the test**

```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconifySvgConverterTest {

    @Test
    fun `path element copies pathData verbatim`() {
        val out = IconifySvgConverter.convert(
            """<path fill="none" stroke="currentColor" stroke-width="2" d="M1 2L3 4"/>"""
        )
        assertEquals(1, out.size)
        assertEquals("M1 2L3 4", out[0].pathData)
        assertEquals(2f, out[0].strokeWidth, 0f)
    }

    @Test
    fun `default stroke width is 2 and linecap round`() {
        val out = IconifySvgConverter.convert("""<path d="M0 0"/>""")
        assertEquals(2f, out[0].strokeWidth, 0f)
        assertEquals("round", out[0].strokeLineCap)
        assertEquals("round", out[0].strokeLineJoin)
    }

    @Test
    fun `circle becomes two half-arc path`() {
        val out = IconifySvgConverter.convert("""<circle cx="12" cy="12" r="10"/>""")
        assertEquals(1, out.size)
        val d = out[0].pathData
        assertTrue(d.startsWith("M2.0,12"))
        assertTrue(d.contains("a10.0,10.0 0 1,0 20.0,0"))
        assertTrue(d.endsWith("Z"))
    }

    @Test
    fun `rect without radii becomes M h v h Z`() {
        val out = IconifySvgConverter.convert("""<rect x="1" y="2" width="10" height="20"/>""")
        assertEquals("M1.0,2.0h10.0v20.0h-10.0Z", out[0].pathData)
    }

    @Test
    fun `rect with rx uses four arcs`() {
        val out = IconifySvgConverter.convert(
            """<rect x="0" y="0" width="10" height="10" rx="2"/>"""
        )
        val d = out[0].pathData
        assertTrue(d.startsWith("M2.0,0"))
        assertTrue(d.contains("a2.0,2.0 0 0 1 2.0,2.0"))
        assertTrue(d.endsWith("Z"))
    }

    @Test
    fun `line becomes move-then-line`() {
        val out = IconifySvgConverter.convert("""<line x1="1" y1="2" x2="3" y2="4"/>""")
        assertEquals("M1.0,2.0L3.0,4.0", out[0].pathData)
    }

    @Test
    fun `polyline is open sequence`() {
        val out = IconifySvgConverter.convert("""<polyline points="1,2 3,4 5,6"/>""")
        assertEquals("M1,2L3,4L5,6", out[0].pathData)
    }

    @Test
    fun `polygon closes with Z`() {
        val out = IconifySvgConverter.convert("""<polygon points="0,0 10,0 5,10"/>""")
        assertEquals("M0,0L10,0L5,10Z", out[0].pathData)
    }

    @Test
    fun `multiple elements preserved in order`() {
        val out = IconifySvgConverter.convert(
            """<path d="M1 1"/><circle cx="5" cy="5" r="2"/>"""
        )
        assertEquals(2, out.size)
        assertEquals("M1 1", out[0].pathData)
        assertTrue(out[1].pathData.startsWith("M3.0,5"))
    }
}
```

- [ ] **Step 3: Run**

```bash
./gradlew :app:testDebugUnitTest --tests "*IconifySvgConverterTest*"
```

Expected: all 9 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifySvgConverter.kt \
        app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconifySvgConverterTest.kt
git commit -m "feat(icon): add SVG-body to vector-drawable path converter"
```

---

## Task 5: IconVectorDrawableBuilder

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconVectorDrawableBuilder.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconVectorDrawableBuilderTest.kt`

**Canvas math:** adaptive icon foreground is 108×108 dp with a 66×66 dp safe zone. Lucide icons are 24×24. To center the icon in the safe zone we translate by `(108 - 66)/2 = 21` and scale by `66 / 24 = 2.75`.

**Stroke width note:** after `scaleX=2.75`, an SVG `stroke-width=2` visually becomes `5.5 px` on the 108-unit canvas — that matches Lucide's intended line weight at the adaptive icon scale.

**Background styles:**
- `solid`: one `<path android:pathData="M0,0h108v108h-108Z" android:fillColor="#AARRGGBB"/>`
- `linearGradient`: full-canvas rect with `<aapt:attr name="android:fillColor"><gradient type="linear">` (top-left → bottom-right)
- `radialGradient`: full-canvas rect with `<gradient type="radial" centerX="54" centerY="54" gradientRadius="76">`

- [ ] **Step 1: Write the builder**

```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

/**
 * Builds background + foreground adaptive-icon vector-drawable XML
 * from an IconLibrary record and user-supplied colors.
 *
 * All output targets viewportWidth/Height=108 to match Android adaptive icons.
 */
object IconVectorDrawableBuilder {

    enum class BackgroundStyle { SOLID, LINEAR_GRADIENT, RADIAL_GRADIENT }

    data class Request(
        val icon: IconLibrary.IconRecord,
        val foregroundColor: String,        // "#RRGGBB" or "#AARRGGBB"
        val backgroundStyle: BackgroundStyle,
        val backgroundColor1: String,
        val backgroundColor2: String? = null,
    )

    data class Result(
        val backgroundXml: String,
        val foregroundXml: String,
    )

    private const val CANVAS = 108f
    private const val SAFE_ZONE = 66f

    fun build(request: Request): Result {
        val paths = IconifySvgConverter.convert(request.icon.body)
        val foreground = buildForeground(paths, request.icon, request.foregroundColor)
        val background = buildBackground(request)
        return Result(backgroundXml = background, foregroundXml = foreground)
    }

    private fun buildForeground(
        paths: List<IconifySvgConverter.PathEntry>,
        icon: IconLibrary.IconRecord,
        colorHex: String,
    ): String {
        val color = normalizeColor(colorHex)
        val scale = SAFE_ZONE / icon.width.toFloat()   // Lucide: 66/24 = 2.75
        val translate = (CANVAS - SAFE_ZONE) / 2f       // 21
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        sb.append("""<vector xmlns:android="http://schemas.android.com/apk/res/android"""")
        sb.append(' ')
        sb.append("""android:width="108dp" android:height="108dp"""")
        sb.append(' ')
        sb.append("""android:viewportWidth="108" android:viewportHeight="108">""").append('\n')
        sb.append("""    <group android:translateX="$translate" android:translateY="$translate" """)
        sb.append("""android:scaleX="$scale" android:scaleY="$scale">""").append('\n')
        for (entry in paths) {
            sb.append("""        <path android:pathData="${escapeXml(entry.pathData)}"""")
            sb.append(' ')
            sb.append("""android:strokeColor="$color"""")
            sb.append(' ')
            sb.append("""android:strokeWidth="${entry.strokeWidth}"""")
            sb.append(' ')
            sb.append("""android:strokeLineCap="${entry.strokeLineCap}"""")
            sb.append(' ')
            sb.append("""android:strokeLineJoin="${entry.strokeLineJoin}"/>""").append('\n')
        }
        sb.append("    </group>\n")
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun buildBackground(request: Request): String {
        val rectPath = "M0,0h108v108h-108Z"
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        sb.append("""<vector xmlns:android="http://schemas.android.com/apk/res/android"""")
        sb.append(' ')
        sb.append("""xmlns:aapt="http://schemas.android.com/aapt"""")
        sb.append(' ')
        sb.append("""android:width="108dp" android:height="108dp"""")
        sb.append(' ')
        sb.append("""android:viewportWidth="108" android:viewportHeight="108">""").append('\n')

        when (request.backgroundStyle) {
            BackgroundStyle.SOLID -> {
                sb.append("""    <path android:pathData="$rectPath" """)
                sb.append("""android:fillColor="${normalizeColor(request.backgroundColor1)}"/>""").append('\n')
            }
            BackgroundStyle.LINEAR_GRADIENT -> {
                val c1 = normalizeColor(request.backgroundColor1)
                val c2 = normalizeColor(request.backgroundColor2 ?: request.backgroundColor1)
                sb.append("""    <path android:pathData="$rectPath">""").append('\n')
                sb.append("""        <aapt:attr name="android:fillColor">""").append('\n')
                sb.append("""            <gradient android:type="linear" """)
                sb.append("""android:startX="0" android:startY="0" """)
                sb.append("""android:endX="108" android:endY="108">""").append('\n')
                sb.append("""                <item android:offset="0.0" android:color="$c1"/>""").append('\n')
                sb.append("""                <item android:offset="1.0" android:color="$c2"/>""").append('\n')
                sb.append("""            </gradient>""").append('\n')
                sb.append("""        </aapt:attr>""").append('\n')
                sb.append("""    </path>""").append('\n')
            }
            BackgroundStyle.RADIAL_GRADIENT -> {
                val c1 = normalizeColor(request.backgroundColor1)
                val c2 = normalizeColor(request.backgroundColor2 ?: request.backgroundColor1)
                sb.append("""    <path android:pathData="$rectPath">""").append('\n')
                sb.append("""        <aapt:attr name="android:fillColor">""").append('\n')
                sb.append("""            <gradient android:type="radial" """)
                sb.append("""android:centerX="54" android:centerY="54" """)
                sb.append("""android:gradientRadius="76">""").append('\n')
                sb.append("""                <item android:offset="0.0" android:color="$c1"/>""").append('\n')
                sb.append("""                <item android:offset="1.0" android:color="$c2"/>""").append('\n')
                sb.append("""            </gradient>""").append('\n')
                sb.append("""        </aapt:attr>""").append('\n')
                sb.append("""    </path>""").append('\n')
            }
        }
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun normalizeColor(input: String): String {
        val trimmed = input.trim()
        val hex = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        // Accept #RGB, #RRGGBB, #AARRGGBB; uppercase for consistency.
        return hex.uppercase()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
         .replace("<", "&lt;")
         .replace(">", "&gt;")
         .replace("\"", "&quot;")
         .replace("'", "&apos;")
}
```

- [ ] **Step 2: Write the test**

```kotlin
package com.vibe.app.feature.projecticon.iconlibrary

import org.junit.Assert.assertTrue
import org.junit.Test

class IconVectorDrawableBuilderTest {

    private val plusIcon = IconLibrary.IconRecord(
        id = "plus",
        body = """<path d="M5 12h14M12 5v14"/>""",
        width = 24,
        height = 24,
        categories = listOf("Math"),
    )

    @Test
    fun `foreground wraps paths in 108 canvas with safe-zone group`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.SOLID,
                backgroundColor1 = "#2196F3",
            ),
        )
        val fg = result.foregroundXml
        assertTrue(fg.contains("""android:viewportWidth="108""""))
        assertTrue(fg.contains("""android:translateX="21"""))
        assertTrue(fg.contains("""android:scaleX="2.75"""))
        assertTrue(fg.contains("""android:strokeColor="#FFFFFF""""))
        assertTrue(fg.contains("""android:pathData="M5 12h14M12 5v14""""))
    }

    @Test
    fun `solid background uses fillColor path`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.SOLID,
                backgroundColor1 = "#2196F3",
            ),
        )
        val bg = result.backgroundXml
        assertTrue(bg.contains("""android:fillColor="#2196F3""""))
        assertTrue(bg.contains("""M0,0h108v108h-108Z"""))
    }

    @Test
    fun `linear gradient background emits aapt gradient`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.LINEAR_GRADIENT,
                backgroundColor1 = "#ff2196f3",
                backgroundColor2 = "#ff0d47a1",
            ),
        )
        val bg = result.backgroundXml
        assertTrue(bg.contains("""xmlns:aapt="http://schemas.android.com/aapt""""))
        assertTrue(bg.contains("""android:type="linear""""))
        assertTrue(bg.contains("""android:color="#FF2196F3""""))
        assertTrue(bg.contains("""android:color="#FF0D47A1""""))
    }

    @Test
    fun `radial gradient background uses radial type`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.RADIAL_GRADIENT,
                backgroundColor1 = "#FFFFFFFF",
                backgroundColor2 = "#FF000000",
            ),
        )
        assertTrue(result.backgroundXml.contains("""android:type="radial""""))
        assertTrue(result.backgroundXml.contains("""android:gradientRadius="76""""))
    }

    @Test
    fun `color normalization uppercases and prefixes hash`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "ff00aa",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.SOLID,
                backgroundColor1 = "112233",
            ),
        )
        assertTrue(result.foregroundXml.contains("""android:strokeColor="#FF00AA""""))
        assertTrue(result.backgroundXml.contains("""android:fillColor="#112233""""))
    }
}
```

- [ ] **Step 3: Run**

```bash
./gradlew :app:testDebugUnitTest --tests "*IconVectorDrawableBuilderTest*"
```

Expected: all 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconVectorDrawableBuilder.kt \
        app/src/test/kotlin/com/vibe/app/feature/projecticon/iconlibrary/IconVectorDrawableBuilderTest.kt
git commit -m "feat(icon): add IconVectorDrawableBuilder with solid/linear/radial backgrounds"
```

---

## Task 6: Hilt wiring (asset-backed IconLibrary)

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/di/IconLibraryModule.kt`

- [ ] **Step 1: Write the module**

```kotlin
package com.vibe.app.di

import android.content.Context
import com.vibe.app.feature.projecticon.iconlibrary.IconLibrary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IconLibraryModule {

    @Provides
    @Singleton
    fun provideIconLibrary(@ApplicationContext context: Context): IconLibrary =
        IconLibrary { context.assets.open("icons/lucide.json") }
}
```

- [ ] **Step 2: Commit (will compile together with Task 7 tools)**

```bash
git add app/src/main/kotlin/com/vibe/app/di/IconLibraryModule.kt
git commit -m "feat(icon): provide IconLibrary via Hilt backed by app assets"
```

---

## Task 7: Agent tools (search, update, custom)

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/IconTools.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectManagementTools.kt` (delete old `UpdateProjectIconTool`, keep `RenameProjectTool`)

**Tool shapes:**

- `search_icon(keyword: string, limit?: int=20)` → returns `{ hits: [{id, categories}] }`
- `update_project_icon(iconId: string, foregroundColor: string, backgroundStyle: "solid"|"linearGradient"|"radialGradient", backgroundColor1: string, backgroundColor2?: string)` → writes background + foreground, re-renders PNG fallbacks.
- `update_project_icon_custom(backgroundXml: string, foregroundXml: string)` — escape hatch, preserves the pre-refactor behavior for genuinely custom graphics.

- [ ] **Step 1: Create `IconTools.kt`**

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.projecticon.ProjectIconRenderer
import com.vibe.app.feature.projecticon.iconlibrary.IconLibrary
import com.vibe.app.feature.projecticon.iconlibrary.IconVectorDrawableBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private const val ICON_BACKGROUND_PATH = "src/main/res/drawable/ic_launcher_background.xml"
private const val ICON_FOREGROUND_PATH = "src/main/res/drawable/ic_launcher_foreground.xml"

@Singleton
class SearchIconTool @Inject constructor(
    private val iconLibrary: IconLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "search_icon",
        description = "Search the bundled Lucide icon library by keyword. " +
            "Returns a list of icon ids you can pass to update_project_icon. " +
            "Prefer this over writing raw vector XML — the preset icons are " +
            "guaranteed to render correctly. Try a few broad keywords (e.g. " +
            "'house', 'calculator', 'cloud').",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("keyword", stringProp("Keyword to search, e.g. 'house' or 'calculator'."))
                    put("limit", intProp("Maximum hits to return, default 20, max 50."))
                },
            )
            put("required", requiredFields("keyword"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val keyword = call.arguments.requireString("keyword")
        val limit = call.arguments.optionalInt("limit", 20).coerceIn(1, 50)
        val hits = iconLibrary.search(keyword, limit)
        val output = buildJsonObject {
            put("total", JsonPrimitive(hits.size))
            put(
                "hits",
                buildJsonArray {
                    for (hit in hits) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(hit.id))
                                if (hit.categories.isNotEmpty()) {
                                    put(
                                        "categories",
                                        buildJsonArray { hit.categories.forEach { add(JsonPrimitive(it)) } },
                                    )
                                }
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
class UpdateProjectIconTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val iconLibrary: IconLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_project_icon",
        description = "Set the launcher icon from the preset icon library. " +
            "First call search_icon to pick an iconId, then call this with " +
            "a foreground color and a background style. Colors are #RRGGBB hex.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("iconId", stringProp("Icon id from search_icon, e.g. 'house'."))
                    put("foregroundColor", stringProp("Stroke color for the icon, #RRGGBB hex."))
                    put(
                        "backgroundStyle",
                        stringProp("One of: solid | linearGradient | radialGradient."),
                    )
                    put("backgroundColor1", stringProp("Primary background color, #RRGGBB hex."))
                    put(
                        "backgroundColor2",
                        stringProp("Secondary color for gradients. Optional for solid."),
                    )
                },
            )
            put(
                "required",
                requiredFields("iconId", "foregroundColor", "backgroundStyle", "backgroundColor1"),
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val iconId = call.arguments.requireString("iconId")
        val foreground = call.arguments.requireString("foregroundColor")
        val styleStr = call.arguments.requireString("backgroundStyle")
        val bg1 = call.arguments.requireString("backgroundColor1")
        val bg2 = call.arguments.optionalString("backgroundColor2")

        val icon = iconLibrary.get(iconId)
            ?: return call.errorResult(
                "Unknown iconId '$iconId'. Call search_icon first to pick a valid id.",
            )

        val style = when (styleStr.lowercase()) {
            "solid" -> IconVectorDrawableBuilder.BackgroundStyle.SOLID
            "lineargradient", "linear", "linear_gradient" ->
                IconVectorDrawableBuilder.BackgroundStyle.LINEAR_GRADIENT
            "radialgradient", "radial", "radial_gradient" ->
                IconVectorDrawableBuilder.BackgroundStyle.RADIAL_GRADIENT
            else -> return call.errorResult(
                "backgroundStyle must be one of: solid, linearGradient, radialGradient.",
            )
        }

        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = icon,
                foregroundColor = foreground,
                backgroundStyle = style,
                backgroundColor1 = bg1,
                backgroundColor2 = bg2,
            ),
        )

        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(ICON_BACKGROUND_PATH, result.backgroundXml)
        workspace.writeTextFile(ICON_FOREGROUND_PATH, result.foregroundXml)
        ProjectIconRenderer.renderPngIcons(workspace.rootDir.absolutePath)
        return call.okResult()
    }
}

@Singleton
class UpdateProjectIconCustomTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_project_icon_custom",
        description = "Escape hatch: set the launcher icon by writing raw " +
            "vector-drawable XML directly. ONLY use this when search_icon " +
            "has no suitable icon. Prefer update_project_icon in all other " +
            "cases — hand-written path geometry is rarely correct.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "backgroundXml",
                        stringProp("Complete XML for src/main/res/drawable/ic_launcher_background.xml"),
                    )
                    put(
                        "foregroundXml",
                        stringProp("Complete XML for src/main/res/drawable/ic_launcher_foreground.xml"),
                    )
                },
            )
            put("required", requiredFields("backgroundXml", "foregroundXml"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val backgroundXml = call.arguments.requireString("backgroundXml")
        val foregroundXml = call.arguments.requireString("foregroundXml")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(ICON_BACKGROUND_PATH, backgroundXml)
        workspace.writeTextFile(ICON_FOREGROUND_PATH, foregroundXml)
        ProjectIconRenderer.renderPngIcons(workspace.rootDir.absolutePath)
        return call.okResult()
    }
}
```

- [ ] **Step 2: Delete the old `UpdateProjectIconTool` from `ProjectManagementTools.kt`**

Keep only `RenameProjectTool` (and its imports). Remove:
- The `UpdateProjectIconTool` class block (lines 56-93).
- Imports `ProjectIconRenderer`, `ProjectManager` if no longer used — `RenameProjectTool` doesn't need them, so those imports can be dropped.
- The two path constants at the top.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/IconTools.kt \
        app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectManagementTools.kt
git commit -m "feat(icon): add search_icon, preset update_project_icon, and escape-hatch custom tool"
```

---

## Task 8: Register new tools in AgentToolModule

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`

- [ ] **Step 1: Add imports and `@Binds` entries**

Add imports:
```kotlin
import com.vibe.app.feature.agent.tool.SearchIconTool
import com.vibe.app.feature.agent.tool.UpdateProjectIconCustomTool
```

(The `UpdateProjectIconTool` import path does NOT change — it still lives in package `com.vibe.app.feature.agent.tool`.)

Add inside the abstract module class, near the existing icon binding:
```kotlin
@Binds @IntoSet abstract fun bindSearchIcon(tool: SearchIconTool): AgentTool
@Binds @IntoSet abstract fun bindUpdateProjectIconCustom(tool: UpdateProjectIconCustomTool): AgentTool
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt
git commit -m "feat(icon): register search_icon and update_project_icon_custom tools"
```

---

## Task 9: Update agent system prompt

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md` (replace the "## App Icon Requests" section)

- [ ] **Step 1: Replace lines 104-109 with the new icon workflow**

New content:
```markdown
## App Icon Requests

Preferred workflow (use this almost always):
1. `search_icon(keyword)` — try 1-3 broad keywords for the app's topic (e.g. "calculator", "house", "cloud sun"). Returns a list of icon ids.
2. `update_project_icon(iconId, foregroundColor, backgroundStyle, backgroundColor1, backgroundColor2?)`:
   - `iconId` from step 1.
   - `foregroundColor`: `#RRGGBB`, usually white `#FFFFFF` or a light tint.
   - `backgroundStyle`: `solid` | `linearGradient` | `radialGradient`.
   - `backgroundColor1` / `backgroundColor2`: `#RRGGBB`. For gradients, pick two colors from the same hue family.

Never hand-write icon XML unless `search_icon` returns nothing usable across several keywords. In that rare case, use `update_project_icon_custom(backgroundXml, foregroundXml)` with a 108x108 viewport and a 66x66 foreground safe zone.
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/assets/agent-system-prompt.md
git commit -m "docs(agent): steer icon workflow to search_icon + preset update tool"
```

---

## Task 10: Build + test verification

- [ ] **Step 1: Run all affected unit tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*iconlibrary*"
```

Expected: all tests from Tasks 3/4/5 green.

- [ ] **Step 2: Full app assembleDebug**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Confirms Hilt wiring, new tools, and the asset bundle all compile together.

- [ ] **Step 3: Sanity check the APK contains the asset**

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'assets/icons/lucide.json'
```

Expected: one line showing `assets/icons/lucide.json` with size ≈ 550 KB.

- [ ] **Step 4: Final commit if any fixups were needed, otherwise done**

---

## Self-review notes

- **Spec coverage:** Design doc (`docs/icon-preset-library-strategy.md`) asks for: bundle Lucide subset ✓ (full Lucide for v1 — ~550 KB is acceptable), SVG→VD conversion in Kotlin ✓, `search_icon` + `update_project_icon` (id + colors) ✓, escape hatch ✓, system prompt update ✓, avoid network access ✓, avoid bitmap/AI routes ✓.
- **Out of scope (explicitly deferred):** Material Symbols / Phosphor sets, fuzzy match, chat-UI icon preview, runtime icon curation/allowlist. These are easy additions later.
- **Risks:** (1) `xmlns:aapt` gradient may not round-trip through `ProjectIconRenderer`'s simple parser for PNG fallback — the renderer only reads `fillColor`/`strokeColor` attrs and will silently skip the gradient path, producing a transparent background in the PNG fallback. Acceptable for v1 because Android 26+ launchers use the vector adaptive icon; pre-26 launchers get the fallback. If this becomes a real issue, extend `ProjectIconRenderer` in a follow-up. (2) Some Lucide icons may have been added after the committed JSON snapshot — acceptable, refresh on demand.
