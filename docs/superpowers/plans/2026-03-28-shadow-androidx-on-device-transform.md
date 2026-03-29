# Shadow AndroidX On-Device ASM Transform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable plugin-mode builds by transforming `androidx-classes.jar` into `shadow-androidx-classes.jar` on-device using ASM, with MD5-based caching so the transform only runs once.

**Architecture:** Add ASM dependency to `build-engine`. Create `ShadowAndroidxTransformer` that reads `androidx-classes.jar`, applies class-name remapping via ASM `ClassRemapper`, and writes the result to a cached JAR. Add `BuildMode` enum to `CompileInput`. `BuildWorkspace` resolves the correct AndroidX JAR based on build mode. `JavacCompiler` and `D8DexConverter` already use `workspace.androidxClassesJar` — they require no changes beyond what `BuildWorkspace` provides.

**Tech Stack:** ASM 9.7.1 (`org.ow2.asm:asm` + `asm-commons`), Kotlin, Android SDK

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `build-engine/build.gradle.kts` | Modify | Add ASM dependencies |
| `build-engine/src/main/java/com/vibe/build/engine/model/BuildModels.kt` | Modify | Add `BuildMode` enum + field to `CompileInput` |
| `build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowClassRemapper.kt` | Create | ASM `Remapper` with Shadow class name mappings |
| `build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformer.kt` | Create | JAR-to-JAR transform with MD5 cache |
| `build-tools/build-logic/src/main/java/com/tyron/builder/BuildModule.java` | Modify | Add `getShadowAndroidxClassesJar()` method |
| `build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt` | Modify | Select correct JAR based on `BuildMode` |
| `build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowClassRemapperTest.kt` | Create | Unit tests for remapper |
| `build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformerTest.kt` | Create | Unit tests for JAR transform + cache |

---

### Task 1: Add ASM Dependencies

**Files:**
- Modify: `build-engine/build.gradle.kts:33-43`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add ASM version to version catalog**

In `gradle/libs.versions.toml`, add to the `[versions]` section:

```toml
asm = "9.7.1"
```

Add to the `[libraries]` section:

```toml
asm = { group = "org.ow2.asm", name = "asm", version.ref = "asm" }
asm-commons = { group = "org.ow2.asm", name = "asm-commons", version.ref = "asm" }
```

- [ ] **Step 2: Add ASM to build-engine dependencies**

In `build-engine/build.gradle.kts`, add inside the `dependencies` block:

```kotlin
implementation(libs.asm)
implementation(libs.asm.commons)
```

- [ ] **Step 3: Sync and verify**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:dependencies --configuration releaseRuntimeClasspath 2>&1 | grep asm`

Expected: lines showing `org.ow2.asm:asm:9.7.1` and `org.ow2.asm:asm-commons:9.7.1`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build-engine/build.gradle.kts
git commit -m "build: add ASM 9.7.1 dependency to build-engine"
```

---

### Task 2: Add `BuildMode` to `CompileInput`

**Files:**
- Modify: `build-engine/src/main/java/com/vibe/build/engine/model/BuildModels.kt`

- [ ] **Step 1: Add `BuildMode` enum and field**

In `BuildModels.kt`, add the enum after `EngineBuildType`:

```kotlin
enum class BuildMode {
    STANDALONE,
    PLUGIN,
}
```

Add a `buildMode` field to `CompileInput` (after `signingConfig`):

```kotlin
val buildMode: BuildMode = BuildMode.STANDALONE,
```

- [ ] **Step 2: Verify build**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:compileReleaseKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/model/BuildModels.kt
git commit -m "feat: add BuildMode enum (STANDALONE/PLUGIN) to CompileInput"
```

---

### Task 3: Implement `ShadowClassRemapper`

**Files:**
- Create: `build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowClassRemapper.kt`
- Create: `build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowClassRemapperTest.kt`

- [ ] **Step 1: Write the failing test**

Create `build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowClassRemapperTest.kt`:

```kotlin
package com.vibe.build.engine.shadow

import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ShadowClassRemapperTest {

    @Test
    fun `remaps Activity superclass to ShadowActivity`() {
        // Create a class that extends android.app.Activity
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MainActivity",
            null,
            "android/app/Activity",  // superclass
            null,
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        // Apply remapper
        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        // Verify superclass was remapped
        val verifyReader = ClassReader(transformed)
        var superName: String? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superNameParam: String?, interfaces: Array<out String>?,
            ) {
                superName = superNameParam
            }
        }, 0)

        assertEquals(
            "com/tencent/shadow/core/runtime/ShadowActivity",
            superName,
        )
    }

    @Test
    fun `remaps Application superclass to ShadowApplication`() {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MyApp",
            null,
            "android/app/Application",
            null,
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        val verifyReader = ClassReader(transformed)
        var superName: String? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superNameParam: String?, interfaces: Array<out String>?,
            ) {
                superName = superNameParam
            }
        }, 0)

        assertEquals(
            "com/tencent/shadow/core/runtime/ShadowApplication",
            superName,
        )
    }

    @Test
    fun `remaps ActivityLifecycleCallbacks interface`() {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MyCallbacks",
            null,
            "java/lang/Object",
            arrayOf("android/app/Application\$ActivityLifecycleCallbacks"),
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        val verifyReader = ClassReader(transformed)
        var interfaces: Array<out String>? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superName: String?, interfacesParam: Array<out String>?,
            ) {
                interfaces = interfacesParam
            }
        }, 0)

        assertEquals(
            "com/tencent/shadow/core/runtime/ShadowActivityLifecycleCallbacks",
            interfaces?.firstOrNull(),
        )
    }

    @Test
    fun `does not remap unrelated classes`() {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MyView",
            null,
            "android/view/View",
            null,
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        val verifyReader = ClassReader(transformed)
        var superName: String? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superNameParam: String?, interfaces: Array<out String>?,
            ) {
                superName = superNameParam
            }
        }, 0)

        assertEquals("android/view/View", superName)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:testReleaseUnitTest --tests "com.vibe.build.engine.shadow.ShadowClassRemapperTest" 2>&1 | tail -10`

Expected: FAIL — `ShadowClassRemapper` class doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowClassRemapper.kt`:

```kotlin
package com.vibe.build.engine.shadow

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

class ShadowClassRemapper(cv: ClassVisitor) : ClassRemapper(cv, ShadowRemapper)

object ShadowRemapper : Remapper() {

    private val mapping = mapOf(
        "android/app/Activity"
            to "com/tencent/shadow/core/runtime/ShadowActivity",
        "android/app/Application"
            to "com/tencent/shadow/core/runtime/ShadowApplication",
        "android/app/Service"
            to "com/tencent/shadow/core/runtime/ShadowService",
        "android/app/IntentService"
            to "com/tencent/shadow/core/runtime/ShadowIntentService",
        "android/app/Application\$ActivityLifecycleCallbacks"
            to "com/tencent/shadow/core/runtime/ShadowActivityLifecycleCallbacks",
    )

    override fun map(internalName: String): String = mapping[internalName] ?: internalName
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:testReleaseUnitTest --tests "com.vibe.build.engine.shadow.ShadowClassRemapperTest" 2>&1 | tail -10`

Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowClassRemapper.kt \
       build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowClassRemapperTest.kt
git commit -m "feat: ShadowClassRemapper using ASM to remap Android framework classes to Shadow runtime"
```

---

### Task 4: Implement `ShadowAndroidxTransformer`

**Files:**
- Create: `build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformer.kt`
- Create: `build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformerTest.kt`:

```kotlin
package com.vibe.build.engine.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class ShadowAndroidxTransformerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createTestJar(file: File) {
        // Create a minimal JAR with a class extending android.app.Activity
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "androidx/appcompat/app/AppCompatActivity",
            null,
            "android/app/Activity",
            null,
        )
        cw.visitEnd()
        val classBytes = cw.toByteArray()

        JarOutputStream(file.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("androidx/appcompat/app/AppCompatActivity.class"))
            jar.write(classBytes)
            jar.closeEntry()
            // Non-class entries should be copied as-is
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
    }

    @Test
    fun `transforms JAR and remaps Activity to ShadowActivity`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val result = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)

        assertTrue(result.exists())
        // Verify the class inside has been remapped
        JarFile(result).use { jar ->
            val entry = jar.getJarEntry("androidx/appcompat/app/AppCompatActivity.class")
            val bytes = jar.getInputStream(entry).readBytes()
            val reader = ClassReader(bytes)
            var superName: String? = null
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, access: Int, name: String?,
                    signature: String?, superNameParam: String?, interfaces: Array<out String>?,
                ) {
                    superName = superNameParam
                }
            }, 0)
            assertEquals(
                "com/tencent/shadow/core/runtime/ShadowActivity",
                superName,
            )
        }
    }

    @Test
    fun `cache hit returns existing JAR without re-transform`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val first = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        val firstModified = first.lastModified()

        // Second call should be a cache hit
        val second = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        assertEquals(firstModified, second.lastModified())
    }

    @Test
    fun `cache invalidated when input JAR changes`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val first = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        assertTrue(first.exists())

        // Modify the input JAR (different content → different MD5)
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "androidx/appcompat/app/AppCompatActivity",
            null,
            "android/app/Activity",
            arrayOf("java/io/Serializable"),  // added interface
        )
        cw.visitEnd()
        JarOutputStream(inputJar.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("androidx/appcompat/app/AppCompatActivity.class"))
            jar.write(cw.toByteArray())
            jar.closeEntry()
        }

        val second = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        assertTrue(second.exists())
        // Verify the new interface is present (proves re-transform happened)
        JarFile(second).use { jar ->
            val entry = jar.getJarEntry("androidx/appcompat/app/AppCompatActivity.class")
            val bytes = jar.getInputStream(entry).readBytes()
            val reader = ClassReader(bytes)
            var interfaces: Array<out String>? = null
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, access: Int, name: String?,
                    signature: String?, superName: String?, interfacesParam: Array<out String>?,
                ) {
                    interfaces = interfacesParam
                }
            }, 0)
            assertTrue(interfaces?.contains("java/io/Serializable") == true)
        }
    }

    @Test
    fun `non-class entries are preserved`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val result = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)

        JarFile(result).use { jar ->
            val manifest = jar.getJarEntry("META-INF/MANIFEST.MF")
            val content = jar.getInputStream(manifest).readBytes().decodeToString()
            assertTrue(content.contains("Manifest-Version: 1.0"))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:testReleaseUnitTest --tests "com.vibe.build.engine.shadow.ShadowAndroidxTransformerTest" 2>&1 | tail -10`

Expected: FAIL — `ShadowAndroidxTransformer` class doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformer.kt`:

```kotlin
package com.vibe.build.engine.shadow

import android.util.Log
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

object ShadowAndroidxTransformer {

    private const val TAG = "ShadowTransformer"
    private const val CACHE_FILE_NAME = "shadow-androidx-classes.jar"
    private const val HASH_FILE_NAME = "shadow-androidx-classes.jar.md5"

    /**
     * Returns a Shadow-transformed copy of [androidxJar].
     * The result is cached in [cacheDir]; subsequent calls return the cached file
     * unless [androidxJar] content has changed (detected by MD5 hash).
     */
    fun getOrTransform(androidxJar: File, cacheDir: File): File {
        val cached = File(cacheDir, CACHE_FILE_NAME)
        val hashFile = File(cacheDir, HASH_FILE_NAME)

        val currentHash = md5(androidxJar)
        if (cached.exists() && hashFile.exists() && hashFile.readText() == currentHash) {
            Log.d(TAG, "Cache hit for shadow-androidx-classes.jar")
            return cached
        }

        Log.d(TAG, "Transforming androidx-classes.jar with ASM (${androidxJar.length() / 1024}KB)...")
        val startTime = System.currentTimeMillis()

        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "$CACHE_FILE_NAME.tmp")
        try {
            transformJar(androidxJar, tempFile)
            tempFile.renameTo(cached)
            hashFile.writeText(currentHash)
        } finally {
            tempFile.delete()
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Transform complete in ${elapsed}ms, output ${cached.length() / 1024}KB")
        return cached
    }

    private fun transformJar(input: File, output: File) {
        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    jar.getInputStream(entry).use { inputStream ->
                        if (entry.name.endsWith(".class")) {
                            val transformed = transformClass(inputStream)
                            out.putNextEntry(JarEntry(entry.name))
                            out.write(transformed)
                        } else {
                            out.putNextEntry(JarEntry(entry.name))
                            inputStream.copyTo(out)
                        }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    private fun transformClass(input: InputStream): ByteArray {
        val reader = ClassReader(input)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        return writer.toByteArray()
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:testReleaseUnitTest --tests "com.vibe.build.engine.shadow.ShadowAndroidxTransformerTest" 2>&1 | tail -15`

Expected: all 4 tests PASS. Note: `android.util.Log` calls will be no-ops in unit tests — if they fail due to `Log` not being mocked, replace with `println` or add a Robolectric dependency. In that case, wrap the Log call:

```kotlin
private fun log(msg: String) {
    try { Log.d(TAG, msg) } catch (_: Throwable) { /* unit test environment */ }
}
```

- [ ] **Step 5: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformer.kt \
       build-engine/src/test/java/com/vibe/build/engine/shadow/ShadowAndroidxTransformerTest.kt
git commit -m "feat: ShadowAndroidxTransformer with MD5-based JAR cache"
```

---

### Task 5: Add `getShadowAndroidxClassesJar()` to `BuildModule`

**Files:**
- Modify: `build-tools/build-logic/src/main/java/com/tyron/builder/BuildModule.java:57-66`
- Modify: `build-tools/build-logic/build.gradle`

- [ ] **Step 1: Add build-engine dependency to build-logic**

In `build-tools/build-logic/build.gradle`, add to the `dependencies` block:

```groovy
implementation project(path: ':build-engine')
```

**Important:** Check for circular dependency. `build-engine` depends on `build-logic` (line 39 in `build-engine/build.gradle.kts`). Adding `build-logic → build-engine` would create a cycle.

**If circular:** Instead of modifying `BuildModule`, create the accessor directly in `BuildWorkspace.kt` (see alternative in Step 3 below). Skip Step 2 and go to Step 3 alternative.

- [ ] **Step 2: (If no circular dependency) Add method to BuildModule**

In `BuildModule.java`, add a new static field and method after `getAndroidxClassesJar()`:

```java
private static File sShadowAndroidxClassesJar;

public static File getShadowAndroidxClassesJar() {
    if (sShadowAndroidxClassesJar == null) {
        File androidxJar = getAndroidxClassesJar();
        if (androidxJar == null || !androidxJar.exists()) {
            return null;
        }
        File cacheDir = new File(sApplicationContext.getFilesDir(), "shadow-cache");
        sShadowAndroidxClassesJar = com.vibe.build.engine.shadow.ShadowAndroidxTransformer.INSTANCE
            .getOrTransform(androidxJar, cacheDir);
    }
    return sShadowAndroidxClassesJar;
}
```

- [ ] **Step 3: (Alternative — if circular dependency) Add resolver in BuildWorkspace**

Since `build-engine` already depends on `build-logic`, and `build-logic` cannot depend on `build-engine`, put the Shadow JAR resolution directly in `BuildWorkspace.kt`. In the `Companion.from()` method, after the existing `androidxClassesJar` assignment:

```kotlin
fun from(input: CompileInput): BuildWorkspace {
    val rootDir = File(input.workingDirectory)
    // ... existing code ...

    val androidxJar = BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() }

    val effectiveAndroidxJar = if (input.buildMode == BuildMode.PLUGIN && androidxJar != null) {
        val cacheDir = File(BuildModule.getContext().filesDir, "shadow-cache")
        ShadowAndroidxTransformer.getOrTransform(androidxJar, cacheDir)
    } else {
        androidxJar
    }

    return BuildWorkspace(
        // ... existing fields ...
        androidxClassesJar = effectiveAndroidxJar,
        // ...
    )
}
```

This is the preferred approach — it avoids circular dependencies and keeps the BuildMode logic in build-engine where it belongs.

- [ ] **Step 4: Verify build compiles**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:compileReleaseKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt
git commit -m "feat: BuildWorkspace selects shadow-transformed AndroidX JAR for plugin builds"
```

---

### Task 6: Wire `BuildMode` Through `BuildWorkspace`

**Files:**
- Modify: `build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt:44-83`

- [ ] **Step 1: Update `BuildWorkspace.from()` to accept and use BuildMode**

Replace the `from()` method in `BuildWorkspace.Companion`:

```kotlin
fun from(input: CompileInput): BuildWorkspace {
    val rootDir = File(input.workingDirectory)
    val sourceDir = File(rootDir, "src/main/java")
    val resDir = File(rootDir, "src/main/res")
    val assetsDir = File(rootDir, "src/main/assets")
    val nativeLibsDir = File(rootDir, "src/main/jniLibs")
    val javaResourcesDir = File(rootDir, "src/main/resources")
    val manifestFile = File(rootDir, "src/main/AndroidManifest.xml")
    val buildDir = File(rootDir, "build")
    val binDir = File(buildDir, "bin")
    val generatedSourcesDir = File(buildDir, "gen")
    val classesDir = File(buildDir, "bin/java/classes")
    val compiledResZip = File(buildDir, "bin/res/project.zip")
    val resourcePackage = File(binDir, "generated.apk.res")
    val rTxtFile = File(buildDir, "bin/res/R.txt")
    val unsignedApk = File(binDir, "generated.apk")
    val signedApk = File(binDir, "signed.apk")

    val androidxJar = BuildModule.getAndroidxClassesJar()?.takeIf { it.exists() }
    val effectiveAndroidxJar = if (input.buildMode == BuildMode.PLUGIN && androidxJar != null) {
        val cacheDir = File(BuildModule.getContext().filesDir, "shadow-cache")
        ShadowAndroidxTransformer.getOrTransform(androidxJar, cacheDir)
    } else {
        androidxJar
    }

    return BuildWorkspace(
        rootDir = rootDir,
        sourceDir = sourceDir,
        resDir = resDir,
        assetsDir = assetsDir,
        nativeLibsDir = nativeLibsDir,
        javaResourcesDir = javaResourcesDir,
        manifestFile = manifestFile,
        buildDir = buildDir,
        binDir = binDir,
        generatedSourcesDir = generatedSourcesDir,
        classesDir = classesDir,
        compiledResZip = compiledResZip,
        resourcePackage = resourcePackage,
        rTxtFile = rTxtFile,
        unsignedApk = unsignedApk,
        signedApk = signedApk,
        bootstrapJar = BuildModule.getAndroidJar(),
        lambdaStubsJar = BuildModule.getLambdaStubs(),
        androidxClassesJar = effectiveAndroidxJar,
        androidxResCompiledDir = BuildModule.getAndroidxResCompiledDir()?.takeIf { it.exists() && it.isDirectory },
    )
}
```

Note: This requires adding the import:

```kotlin
import com.vibe.build.engine.model.BuildMode
import com.vibe.build.engine.shadow.ShadowAndroidxTransformer
```

- [ ] **Step 2: Verify build**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:compileReleaseKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Verify full project builds**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew assembleDebug 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt
git commit -m "feat: wire BuildMode into BuildWorkspace to select correct AndroidX JAR"
```

---

### Task 7: Full Integration Verification

- [ ] **Step 1: Run all build-engine tests**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :build-engine:test 2>&1 | tail -15`

Expected: all tests pass.

- [ ] **Step 2: Run full assembleDebug**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew assembleDebug 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Verify default behavior is unchanged**

Confirm that `CompileInput()` defaults to `BuildMode.STANDALONE` — existing code paths that don't pass `buildMode` will continue to use `androidx-classes.jar` directly, with zero behavioral change.

Grep for all call sites of `CompileInput(` to confirm none need updating:

Run: `cd /Users/skykai/Documents/work/VibeApp && grep -rn "CompileInput(" --include="*.kt" | grep -v "test" | grep -v "build/"`

Expected: All existing `CompileInput` constructions omit `buildMode`, which defaults to `STANDALONE`.

- [ ] **Step 4: Final commit (if any fixups needed)**

```bash
git add -A
git commit -m "fix: integration fixes for shadow transform pipeline"
```

---

## Summary of Changes

After all tasks, the flow for a **plugin-mode** build is:

```
CompileInput(buildMode = BuildMode.PLUGIN)
  → BuildWorkspace.from(input)
    → detects PLUGIN mode
    → calls ShadowAndroidxTransformer.getOrTransform()
      → first call: ASM transforms androidx-classes.jar → shadow-androidx-classes.jar (cached)
      → subsequent calls: returns cached JAR instantly
    → sets workspace.androidxClassesJar = shadow-androidx-classes.jar
  → JavacCompiler uses workspace.androidxClassesJar on classpath (no changes needed)
  → D8DexConverter uses workspace.androidxClassesJar as program input (no changes needed)
```

For **standalone** builds (default): behavior is completely unchanged.
