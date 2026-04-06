# Jsoup Network Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable generated apps to make HTTP requests and parse HTML using Jsoup, a zero-dependency Java library.

**Architecture:** Jsoup JAR is bundled as a build-engine asset (like androidx-classes.jar). It gets extracted at build time, added to the javac classpath for compilation, included in D8 dexing for runtime, and included in the pre-dex cache for fast rebuilds. The template AndroidManifest.xml gains INTERNET permission, and the agent system prompt documents Jsoup availability.

**Tech Stack:** Jsoup 1.18.3, Java 8, on-device javac + D8 pipeline

---

### Task 1: Download and bundle Jsoup JAR

**Files:**
- Create: `build-engine/src/main/assets/jsoup.jar.zip`

- [ ] **Step 1: Download Jsoup JAR and create zip**

```bash
# Download Jsoup 1.18.3 from Maven Central
curl -L -o /tmp/jsoup-1.18.3.jar "https://repo1.maven.org/maven2/org/jsoup/jsoup/1.18.3/jsoup-1.18.3.jar"
# Verify it's a valid JAR (~440KB)
ls -la /tmp/jsoup-1.18.3.jar
file /tmp/jsoup-1.18.3.jar
# Zip it (matching the naming convention of other assets)
cd /tmp && zip jsoup.jar.zip jsoup-1.18.3.jar
# Copy to build-engine assets
cp /tmp/jsoup.jar.zip /path/to/VibeApp/build-engine/src/main/assets/jsoup.jar.zip
```

**Important:** The zip must contain a file named `jsoup.jar` (not `jsoup-1.18.3.jar`). The `Decompress.unzipFromAssets()` extracts with original filenames, and `BuildModule.getJsoupJar()` expects `jsoup.jar`. So either rename the JAR before zipping, or zip with the correct name:

```bash
curl -L -o /tmp/jsoup.jar "https://repo1.maven.org/maven2/org/jsoup/jsoup/1.18.3/jsoup-1.18.3.jar"
cd /tmp && zip jsoup.jar.zip jsoup.jar
cp /tmp/jsoup.jar.zip build-engine/src/main/assets/jsoup.jar.zip
```

- [ ] **Step 2: Verify the asset is in place**

```bash
ls -la build-engine/src/main/assets/jsoup.jar.zip
# Expected: ~300-450KB zip file
unzip -l build-engine/src/main/assets/jsoup.jar.zip
# Expected: contains jsoup.jar
```

- [ ] **Step 3: Commit**

```bash
git add build-engine/src/main/assets/jsoup.jar.zip
git commit -m "feat: bundle jsoup.jar.zip as build-engine asset"
```

---

### Task 2: Add Jsoup extraction to BuildModule.java

**Files:**
- Modify: `build-tools/build-logic/src/main/java/com/tyron/builder/BuildModule.java`

- [ ] **Step 1: Add static field for Jsoup JAR**

Add after the existing `sShadowRuntimeJar` field (line 18):

```java
private static File sJsoupJar;
```

- [ ] **Step 2: Add getJsoupJar() method**

Add after `getShadowRuntimeJar()` method (after line 103), following the same pattern as `getAndroidxClassesJar()`:

```java
public static File getJsoupJar() {
    if (sJsoupJar == null) {
        Context context = BuildModule.getContext();
        if (context == null) {
            return null;
        }
        sJsoupJar = new File(context.getFilesDir(), "jsoup.jar");
        if (!sJsoupJar.exists()) {
            Decompress.unzipFromAssets(BuildModule.getContext(),
                    "jsoup.jar.zip",
                    sJsoupJar.getParentFile().getAbsolutePath());
        }
    }
    return sJsoupJar;
}
```

- [ ] **Step 3: Commit**

```bash
git add build-tools/build-logic/src/main/java/com/tyron/builder/BuildModule.java
git commit -m "feat: add Jsoup JAR extraction to BuildModule"
```

---

### Task 3: Add jsoupJar property to BuildWorkspace

**Files:**
- Modify: `build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt`

- [ ] **Step 1: Add jsoupJar property to data class**

Add after `shadowRuntimeJar` (line 30):

```kotlin
val jsoupJar: File?,
```

The data class constructor becomes:
```kotlin
data class BuildWorkspace(
    // ... existing properties ...
    val shadowRuntimeJar: File?,
    val jsoupJar: File?,       // Jsoup HTTP + HTML parsing library
)
```

- [ ] **Step 2: Wire jsoupJar in the `from()` factory method**

Add after the `shadowRuntimeJar` local variable (line 65), and add it to the constructor call:

After line 65, add:
```kotlin
val jsoupJar = BuildModule.getJsoupJar()?.takeIf { it.exists() }
```

In the `return BuildWorkspace(...)` block, add after `shadowRuntimeJar = shadowRuntimeJar,` (line 88):
```kotlin
jsoupJar = jsoupJar,
```

- [ ] **Step 3: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt
git commit -m "feat: add jsoupJar property to BuildWorkspace"
```

---

### Task 4: Add Jsoup to javac classpath

**Files:**
- Modify: `build-engine/src/main/java/com/vibe/build/engine/compiler/JavacCompiler.kt:108-111`

- [ ] **Step 1: Add jsoupJar to classpath construction**

Change the classpath construction (lines 108-111) from:

```kotlin
val classpath = input.classpathEntries.map(::File).filter { it.exists() } +
    listOfNotNull(workspace.androidxClassesJar) +
    listOfNotNull(workspace.shadowRuntimeJar) +
    workspace.classesDir
```

To:

```kotlin
val classpath = input.classpathEntries.map(::File).filter { it.exists() } +
    listOfNotNull(workspace.androidxClassesJar) +
    listOfNotNull(workspace.shadowRuntimeJar) +
    listOfNotNull(workspace.jsoupJar) +
    workspace.classesDir
```

- [ ] **Step 2: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/compiler/JavacCompiler.kt
git commit -m "feat: add Jsoup to javac compilation classpath"
```

---

### Task 5: Add Jsoup to D8 dex conversion

**Files:**
- Modify: `build-engine/src/main/java/com/vibe/build/engine/dex/D8DexConverter.kt:62-66,101-107`

- [ ] **Step 1: Add jsoupJar to pre-dex classpath path (line ~65)**

In the pre-dex branch, add jsoupJar to the `classpathFiles` list. Change:

```kotlin
val classpathFiles = buildList {
    addAll(input.classpathEntries.map(::File).filter { it.exists() }.map { it.toPath() })
    workspace.androidxClassesJar?.let { add(it.toPath()) }
    workspace.shadowRuntimeJar?.let { add(it.toPath()) }
}
```

To:

```kotlin
val classpathFiles = buildList {
    addAll(input.classpathEntries.map(::File).filter { it.exists() }.map { it.toPath() })
    workspace.androidxClassesJar?.let { add(it.toPath()) }
    workspace.shadowRuntimeJar?.let { add(it.toPath()) }
    workspace.jsoupJar?.let { add(it.toPath()) }
}
```

- [ ] **Step 2: Add jsoupJar to fallback dex path (line ~101-107)**

In the fallback branch (no pre-dex cache), add jsoupJar as a program file. After the existing shadowRuntimeJar block:

```kotlin
if (workspace.shadowRuntimeJar != null) {
    programFiles.add(workspace.shadowRuntimeJar.toPath())
}
```

Add:

```kotlin
if (workspace.jsoupJar != null) {
    programFiles.add(workspace.jsoupJar.toPath())
}
```

- [ ] **Step 3: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/dex/D8DexConverter.kt
git commit -m "feat: include Jsoup in D8 dex conversion"
```

---

### Task 6: Add Jsoup to PreDexCache

**Files:**
- Modify: `build-engine/src/main/java/com/vibe/build/engine/dex/PreDexCache.kt:45-47,106-108`

- [ ] **Step 1: Add jsoupJar to jarsToPreDex list in getOrCreateLibraryDex()**

Change the `jarsToPreDex` list (lines 45-47) from:

```kotlin
val jarsToPreDex = buildList {
    BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() }?.let { add(it) }
    BuildModule.getShadowRuntimeJar()?.takeIf { it.exists() }?.let { add(it) }
}
```

To:

```kotlin
val jarsToPreDex = buildList {
    BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() }?.let { add(it) }
    BuildModule.getShadowRuntimeJar()?.takeIf { it.exists() }?.let { add(it) }
    BuildModule.getJsoupJar()?.takeIf { it.exists() }?.let { add(it) }
}
```

- [ ] **Step 2: Add jsoupJar to isCacheValid() JAR list**

Change the `jars` list in `isCacheValid()` (lines 106-108) from:

```kotlin
val jars = buildList {
    BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() }?.let { add(it) }
    BuildModule.getShadowRuntimeJar()?.takeIf { it.exists() }?.let { add(it) }
}
```

To:

```kotlin
val jars = buildList {
    BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() }?.let { add(it) }
    BuildModule.getShadowRuntimeJar()?.takeIf { it.exists() }?.let { add(it) }
    BuildModule.getJsoupJar()?.takeIf { it.exists() }?.let { add(it) }
}
```

- [ ] **Step 3: Bump CACHE_VERSION**

The Jsoup JAR is now part of the pre-dex set, so bump `CACHE_VERSION` from `2` to `3` (line 30) to invalidate stale caches:

```kotlin
private const val CACHE_VERSION = 3
```

- [ ] **Step 4: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/dex/PreDexCache.kt
git commit -m "feat: include Jsoup in pre-dex cache"
```

---

### Task 7: Add INTERNET permission to AndroidManifest.xml template

**Files:**
- Modify: `app/src/main/assets/templates/EmptyActivity/app/src/main/AndroidManifest.xml`
- Modify: `build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspacePreparer.kt:60-72`

- [ ] **Step 1: Update the template AndroidManifest.xml**

Add the INTERNET permission after the `<uses-sdk>` line. Change `app/src/main/assets/templates/EmptyActivity/app/src/main/AndroidManifest.xml` from:

```xml
    <uses-sdk android:minSdkVersion="${minSdkVersion}" android:targetSdkVersion="${targetSdkVersion}"/>

    <application
```

To:

```xml
    <uses-sdk android:minSdkVersion="${minSdkVersion}" android:targetSdkVersion="${targetSdkVersion}"/>

    <uses-permission android:name="android.permission.INTERNET"/>

    <application
```

- [ ] **Step 2: Update BuildWorkspacePreparer.defaultManifest()**

The `defaultManifest()` method in `BuildWorkspacePreparer.kt` generates a fallback manifest. Add the INTERNET permission there too. Change lines 60-72 from:

```kotlin
private fun defaultManifest(input: CompileInput): String {
    return """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="${input.packageName}">
            <uses-sdk
                android:minSdkVersion="${input.minSdk}"
                android:targetSdkVersion="${input.targetSdk}" />
            <application
                android:allowBackup="true"
                android:label="${xmlEscape(input.projectName)}"
                android:supportsRtl="true" />
        </manifest>
    """.trimIndent()
}
```

To:

```kotlin
private fun defaultManifest(input: CompileInput): String {
    return """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="${input.packageName}">
            <uses-sdk
                android:minSdkVersion="${input.minSdk}"
                android:targetSdkVersion="${input.targetSdk}" />
            <uses-permission android:name="android.permission.INTERNET"/>
            <application
                android:allowBackup="true"
                android:label="${xmlEscape(input.projectName)}"
                android:supportsRtl="true" />
        </manifest>
    """.trimIndent()
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/templates/EmptyActivity/app/src/main/AndroidManifest.xml
git add build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspacePreparer.kt
git commit -m "feat: add INTERNET permission to generated app manifests"
```

---

### Task 8: Update agent system prompt with Jsoup documentation

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md`

- [ ] **Step 1: Add Jsoup to the bundled libraries section**

After the existing bundled libraries list (after line 43, before the "All standard Android SDK APIs" paragraph), add:

```markdown
- org.jsoup.Jsoup — HTTP requests + HTML parsing (Jsoup.connect(url).get(), .select("css"), etc.)
```

- [ ] **Step 2: Add network capability section**

Add a new section after the "Bundled AndroidX & Material libraries" section (after line 44), before "## Pre-configured Template Files":

```markdown
## Network Access (Jsoup)

The project includes the Jsoup library for HTTP requests and HTML parsing. `import org.jsoup.Jsoup` is available with no extra setup. INTERNET permission is already declared.

### Key Rules
- **Network requests MUST run on a background thread** — Android throws NetworkOnMainThreadException on the main thread
- Use `new Thread(() -> { ... }).start()` to run network code (remember: no lambda syntax — use anonymous Runnable)
- Use `runOnUiThread(() -> { ... })` to update UI with results (again, use anonymous Runnable)

### Usage Patterns

Fetch and parse HTML:
```java
new Thread(new Runnable() {
    public void run() {
        try {
            org.jsoup.nodes.Document doc = Jsoup.connect("https://example.com").get();
            org.jsoup.select.Elements items = doc.select(".item-class");
            final String text = items.first().text();
            runOnUiThread(new Runnable() {
                public void run() {
                    textView.setText(text);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}).start();
```

Fetch JSON/API response (parse with org.json.JSONObject — built into Android):
```java
String json = Jsoup.connect("https://api.example.com/data")
    .ignoreContentType(true)
    .execute()
    .body();
JSONObject obj = new JSONObject(json);
```

POST request:
```java
org.jsoup.nodes.Document doc = Jsoup.connect("https://example.com/api")
    .data("key", "value")
    .post();
```
```

- [ ] **Step 3: Update the NEVER rules to remove the blanket no-network restriction**

In the "NEVER do these" section, the line "NEVER add dependencies or libraries beyond what is bundled" is still correct — Jsoup IS bundled. No change needed to the NEVER rules.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/agent-system-prompt.md
git commit -m "feat: document Jsoup network capabilities in agent system prompt"
```

---

### Task 9: Build verification

- [ ] **Step 1: Run assembleDebug to verify no compilation errors**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. The jsoup.jar.zip is a raw asset — it just needs to be present in the assets directory. The Kotlin code changes (BuildWorkspace, JavacCompiler, D8DexConverter, PreDexCache) should compile without issues since they follow existing patterns.

- [ ] **Step 2: Run build-engine tests**

```bash
./gradlew :build-engine:test
```

Expected: All existing tests pass. No new tests needed — the integration is wiring-only (adding a JAR to existing classpath/dex lists).

- [ ] **Step 3: Final commit if any fixes were needed**

Only if previous steps required fixes.
