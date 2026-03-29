# Shadow Plugin Full Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable "Run" button in ChatScreen to build and run generated apps inside VibeApp as plugins (no installation required), with "Install APK" moved to the more options menu.

**Architecture:** Create a `shadow-runtime` module containing `ShadowActivity`/`ShadowApplication` with a host-delegation pattern. Bundle its JAR in build-engine assets for on-device plugin compilation. Add `PluginContainerActivity` (in a `:plugin` process) that loads plugin APKs via `DexClassLoader`, creates plugin `Resources` via `AssetManager` reflection, and delegates lifecycle to the plugin's `ShadowActivity`. Modify `ChatViewModel.runBuild()` to build in `PLUGIN` mode and launch via `PluginManager` instead of triggering the system installer.

**Tech Stack:** DexClassLoader, AssetManager reflection, Kotlin, Jetpack Compose, Hilt

**Prerequisites (already done):**
- `BuildMode` enum (STANDALONE/PLUGIN) in `CompileInput`
- `ShadowAndroidxTransformer` with ASM + MD5 cache
- `BuildWorkspace.from()` auto-selects shadow-transformed AndroidX JAR for PLUGIN mode

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `shadow-runtime/build.gradle.kts` | Create | Android library module for Shadow runtime classes |
| `shadow-runtime/src/main/java/.../ShadowActivity.java` | Create | Plugin Activity base with host delegation |
| `shadow-runtime/src/main/java/.../ShadowApplication.java` | Create | Plugin Application base |
| `shadow-runtime/src/main/java/.../ShadowService.java` | Create | Plugin Service stub |
| `shadow-runtime/src/main/java/.../ShadowIntentService.java` | Create | Plugin IntentService stub |
| `shadow-runtime/src/main/java/.../ShadowActivityLifecycleCallbacks.java` | Create | Plugin lifecycle callbacks interface |
| `shadow-runtime/src/main/java/.../HostActivityDelegator.java` | Create | Interface for host → plugin delegation |
| `settings.gradle.kts` | Modify | Include shadow-runtime module |
| `build-engine/build.gradle.kts` | Modify | Add task to copy shadow-runtime.jar to assets |
| `build-tools/build-logic/.../BuildModule.java` | Modify | Add `getShadowRuntimeJar()` |
| `build-engine/.../BuildWorkspace.kt` | Modify | Add `shadowRuntimeJar` field, use in PLUGIN mode |
| `build-engine/.../compiler/JavacCompiler.kt` | Modify | Include shadowRuntimeJar in classpath for PLUGIN |
| `build-engine/.../dex/D8DexConverter.kt` | Modify | Include shadowRuntimeJar as program file for PLUGIN |
| `app/.../plugin/PluginContainerActivity.kt` | Create | Proxy Activity that loads & delegates to plugin |
| `app/.../plugin/PluginResourceLoader.kt` | Create | Creates Resources for plugin APK via AssetManager reflection |
| `app/.../plugin/PluginManager.kt` | Create | Manages plugin loading/launching/stopping |
| `app/src/main/AndroidManifest.xml` | Modify | Add PluginContainerActivity in :plugin process |
| `app/.../projectinit/ProjectInitializer.kt` | Modify | Add `buildMode` param to `buildProject()` |
| `app/.../ui/chat/ChatViewModel.kt` | Modify | Split run → plugin, add installApk to more options |
| `app/.../ui/chat/ChatScreen.kt` | Modify | Run button → plugin, Install → dropdown |
| `app/src/main/res/values/strings.xml` | Modify | Add "install_apk" string |

---

### Task 1: Create shadow-runtime Module

**Files:**
- Create: `shadow-runtime/build.gradle.kts`
- Create: `shadow-runtime/src/main/AndroidManifest.xml`
- Create: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/HostActivityDelegator.java`
- Create: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java`
- Create: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowApplication.java`
- Create: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowService.java`
- Create: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowIntentService.java`
- Create: `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivityLifecycleCallbacks.java`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create module build file**

Create `shadow-runtime/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
}

android {
    namespace = "com.tencent.shadow.core.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {}
```

Create `shadow-runtime/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Create HostActivityDelegator interface**

Create `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/HostActivityDelegator.java`:

```java
package com.tencent.shadow.core.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Interface that the host PluginContainerActivity implements.
 * ShadowActivity delegates framework calls through this.
 */
public interface HostActivityDelegator {
    Context getHostContext();
    Resources getHostResources();
    LayoutInflater getHostLayoutInflater();
    Window getHostWindow();
    WindowManager getHostWindowManager();
    ClassLoader getPluginClassLoader();
    void superSetContentView(int layoutResID);
    void superSetContentView(View view);
    <T extends View> T superFindViewById(int id);
    void superStartActivity(Intent intent);
    void superFinish();
    void setPluginResult(int resultCode, Intent data);
    Intent getHostIntent();
}
```

- [ ] **Step 3: Create ShadowActivity**

Create `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivity.java`:

```java
package com.tencent.shadow.core.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Base class for plugin Activities. The ASM transform rewrites
 * android.app.Activity → ShadowActivity in the AndroidX class hierarchy.
 *
 * In plugin mode: hostDelegator is set, all calls delegate to the host.
 * In standalone mode: hostDelegator is null, behaves as a normal Activity.
 */
public class ShadowActivity extends Activity {

    private HostActivityDelegator hostDelegator;

    public void setHostDelegator(HostActivityDelegator delegator) {
        this.hostDelegator = delegator;
    }

    public HostActivityDelegator getHostDelegator() {
        return hostDelegator;
    }

    public boolean isPluginMode() {
        return hostDelegator != null;
    }

    // --- Lifecycle (called by PluginContainerActivity) ---

    public void performCreate(Bundle savedInstanceState) {
        onCreate(savedInstanceState);
    }

    public void performResume() {
        onResume();
    }

    public void performPause() {
        onPause();
    }

    public void performStop() {
        onStop();
    }

    public void performDestroy() {
        onDestroy();
    }

    // --- Resource access ---

    @Override
    public Resources getResources() {
        if (hostDelegator != null) {
            return hostDelegator.getHostResources();
        }
        return super.getResources();
    }

    @Override
    public Context getApplicationContext() {
        if (hostDelegator != null) {
            return hostDelegator.getHostContext().getApplicationContext();
        }
        return super.getApplicationContext();
    }

    @Override
    public LayoutInflater getLayoutInflater() {
        if (hostDelegator != null) {
            return hostDelegator.getHostLayoutInflater();
        }
        return super.getLayoutInflater();
    }

    // --- View management ---

    @Override
    public void setContentView(int layoutResID) {
        if (hostDelegator != null) {
            hostDelegator.superSetContentView(layoutResID);
        } else {
            super.setContentView(layoutResID);
        }
    }

    @Override
    public void setContentView(View view) {
        if (hostDelegator != null) {
            hostDelegator.superSetContentView(view);
        } else {
            super.setContentView(view);
        }
    }

    @Override
    public <T extends View> T findViewById(int id) {
        if (hostDelegator != null) {
            return hostDelegator.superFindViewById(id);
        }
        return super.findViewById(id);
    }

    // --- Window ---

    @Override
    public Window getWindow() {
        if (hostDelegator != null) {
            return hostDelegator.getHostWindow();
        }
        return super.getWindow();
    }

    @Override
    public WindowManager getWindowManager() {
        if (hostDelegator != null) {
            return hostDelegator.getHostWindowManager();
        }
        return super.getWindowManager();
    }

    // --- Navigation ---

    @Override
    public void startActivity(Intent intent) {
        if (hostDelegator != null) {
            hostDelegator.superStartActivity(intent);
        } else {
            super.startActivity(intent);
        }
    }

    @Override
    public void finish() {
        if (hostDelegator != null) {
            hostDelegator.superFinish();
        } else {
            super.finish();
        }
    }

    @Override
    public Intent getIntent() {
        if (hostDelegator != null) {
            return hostDelegator.getHostIntent();
        }
        return super.getIntent();
    }

    // --- Context ---

    @Override
    public ClassLoader getClassLoader() {
        if (hostDelegator != null) {
            return hostDelegator.getPluginClassLoader();
        }
        return super.getClassLoader();
    }

    @Override
    public String getPackageName() {
        if (hostDelegator != null) {
            return hostDelegator.getHostContext().getPackageName();
        }
        return super.getPackageName();
    }

    public void setResult(int resultCode, Intent data) {
        if (hostDelegator != null) {
            hostDelegator.setPluginResult(resultCode, data);
        } else {
            super.setResult(resultCode, data);
        }
    }
}
```

- [ ] **Step 4: Create ShadowApplication**

Create `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowApplication.java`:

```java
package com.tencent.shadow.core.runtime;

import android.app.Application;
import android.content.Context;

/**
 * Base class for plugin Applications.
 * In plugin mode, onCreate is called by the plugin loader.
 * In standalone mode, behaves as a normal Application.
 */
public class ShadowApplication extends Application {

    private Context hostContext;

    public void setHostContext(Context context) {
        this.hostContext = context;
    }

    public boolean isPluginMode() {
        return hostContext != null;
    }

    @Override
    public Context getApplicationContext() {
        if (hostContext != null) {
            return hostContext.getApplicationContext();
        }
        return super.getApplicationContext();
    }
}
```

- [ ] **Step 5: Create remaining stubs**

Create `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowService.java`:

```java
package com.tencent.shadow.core.runtime;

import android.app.Service;

public class ShadowService extends Service {
    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
```

Create `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowIntentService.java`:

```java
package com.tencent.shadow.core.runtime;

import android.app.IntentService;

public class ShadowIntentService extends IntentService {
    public ShadowIntentService() {
        super("ShadowIntentService");
    }

    public ShadowIntentService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(android.content.Intent intent) {
    }
}
```

Create `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/ShadowActivityLifecycleCallbacks.java`:

```java
package com.tencent.shadow.core.runtime;

import android.app.Activity;
import android.os.Bundle;

/**
 * Replacement for Application.ActivityLifecycleCallbacks in plugin mode.
 */
public interface ShadowActivityLifecycleCallbacks {
    default void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    default void onActivityStarted(Activity activity) {}
    default void onActivityResumed(Activity activity) {}
    default void onActivityPaused(Activity activity) {}
    default void onActivityStopped(Activity activity) {}
    default void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    default void onActivityDestroyed(Activity activity) {}
}
```

- [ ] **Step 6: Add module to settings.gradle.kts**

In `settings.gradle.kts`, add:

```kotlin
include(":shadow-runtime")
```

- [ ] **Step 7: Verify module compiles**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :shadow-runtime:assembleRelease 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add shadow-runtime/ settings.gradle.kts
git commit -m "feat: create shadow-runtime module with ShadowActivity delegation pattern"
```

---

### Task 2: Bundle shadow-runtime.jar and Wire into Build Pipeline

**Files:**
- Modify: `build-engine/build.gradle.kts`
- Modify: `build-tools/build-logic/src/main/java/com/tyron/builder/BuildModule.java`
- Modify: `build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt`
- Modify: `build-engine/src/main/java/com/vibe/build/engine/compiler/JavacCompiler.kt`
- Modify: `build-engine/src/main/java/com/vibe/build/engine/dex/D8DexConverter.kt`

- [ ] **Step 1: Add Gradle task to bundle shadow-runtime.jar**

In `build-engine/build.gradle.kts`, add after the `plugins` block and before `android`:

```kotlin
val copyShadowRuntime by tasks.registering(Copy::class) {
    dependsOn(":shadow-runtime:bundleLibCompileToJarRelease")
    from(project(":shadow-runtime").layout.buildDirectory.file("intermediates/compile_library_classes_jar/release/classes.jar"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "shadow-runtime.jar" }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyShadowRuntime)
}
```

- [ ] **Step 2: Add getShadowRuntimeJar() to BuildModule**

In `BuildModule.java`, add a new field and method after `getAndroidxResCompiledDir()`:

```java
private static File sShadowRuntimeJar;

public static File getShadowRuntimeJar() {
    if (sShadowRuntimeJar == null) {
        Context context = BuildModule.getContext();
        if (context == null) return null;
        sShadowRuntimeJar = new File(context.getFilesDir(), "shadow-runtime.jar");
        if (!sShadowRuntimeJar.exists()) {
            Decompress.copyFileFromAssets(context, "shadow-runtime.jar",
                    sShadowRuntimeJar.getAbsolutePath());
        }
    }
    return sShadowRuntimeJar;
}
```

Check if `Decompress.copyFileFromAssets` exists; if not use:

```java
public static File getShadowRuntimeJar() {
    if (sShadowRuntimeJar == null) {
        Context context = BuildModule.getContext();
        if (context == null) return null;
        sShadowRuntimeJar = new File(context.getFilesDir(), "shadow-runtime.jar");
        if (!sShadowRuntimeJar.exists()) {
            try {
                java.io.InputStream is = context.getAssets().open("shadow-runtime.jar");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(sShadowRuntimeJar);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.close();
                is.close();
            } catch (java.io.IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }
    return sShadowRuntimeJar;
}
```

- [ ] **Step 3: Add shadowRuntimeJar to BuildWorkspace**

In `BuildWorkspace.kt`, add field to the data class (after `androidxResCompiledDir`):

```kotlin
val shadowRuntimeJar: File?,
```

In `BuildWorkspace.from()`, after the `effectiveAndroidxJar` block, add:

```kotlin
val shadowRuntimeJar = if (input.buildMode == BuildMode.PLUGIN) {
    BuildModule.getShadowRuntimeJar()?.takeIf { it.exists() }
} else {
    null
}
```

And in the constructor call, add:

```kotlin
shadowRuntimeJar = shadowRuntimeJar,
```

- [ ] **Step 4: Wire shadowRuntimeJar into JavacCompiler classpath**

In `JavacCompiler.kt`, in the `compileFiles` method, change the classpath construction:

```kotlin
val classpath = input.classpathEntries.map(::File).filter { it.exists() } +
    listOfNotNull(workspace.androidxClassesJar) +
    listOfNotNull(workspace.shadowRuntimeJar) +
    workspace.classesDir
```

- [ ] **Step 5: Wire shadowRuntimeJar into D8DexConverter**

In `D8DexConverter.kt`, after the `androidxClassesJar` block:

```kotlin
if (workspace.shadowRuntimeJar != null) {
    programFiles.add(workspace.shadowRuntimeJar.toPath())
}
```

- [ ] **Step 6: Verify build**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew assembleDebug 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

Verify shadow-runtime.jar is in assets:

Run: `ls -la /Users/skykai/Documents/work/VibeApp/build-engine/src/main/assets/shadow-runtime.jar`

Expected: file exists (non-zero size)

- [ ] **Step 7: Commit**

```bash
git add build-engine/build.gradle.kts build-tools/build-logic/src/main/java/com/tyron/builder/BuildModule.java \
       build-engine/src/main/java/com/vibe/build/engine/internal/BuildWorkspace.kt \
       build-engine/src/main/java/com/vibe/build/engine/compiler/JavacCompiler.kt \
       build-engine/src/main/java/com/vibe/build/engine/dex/D8DexConverter.kt
git commit -m "feat: bundle shadow-runtime.jar and wire into PLUGIN build classpath"
```

---

### Task 3: Create Plugin Container Activity and Resource Loader

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/plugin/PluginResourceLoader.kt`
- Create: `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`

- [ ] **Step 1: Create PluginResourceLoader**

Create `app/src/main/kotlin/com/vibe/app/plugin/PluginResourceLoader.kt`:

```kotlin
package com.vibe.app.plugin

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import java.io.File

object PluginResourceLoader {

    private const val TAG = "PluginResourceLoader"

    /**
     * Creates a [Resources] object that can load resources from a plugin APK.
     * Uses AssetManager.addAssetPath() via reflection.
     */
    fun loadPluginResources(hostContext: Context, apkPath: String): Resources {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true
        addAssetPath.invoke(assetManager, apkPath)

        return Resources(
            assetManager,
            hostContext.resources.displayMetrics,
            hostContext.resources.configuration,
        )
    }

    /**
     * Creates a DexClassLoader for a plugin APK.
     */
    fun createPluginClassLoader(
        context: Context,
        apkPath: String,
        parentClassLoader: ClassLoader,
    ): ClassLoader {
        val dexOutputDir = context.getDir("plugin_dex", Context.MODE_PRIVATE)
        // Clean stale optimized dex files
        dexOutputDir.listFiles()?.forEach { it.delete() }
        return dalvik.system.DexClassLoader(
            apkPath,
            dexOutputDir.absolutePath,
            null,
            parentClassLoader,
        )
    }
}
```

- [ ] **Step 2: Create PluginContainerActivity**

Create `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`:

```kotlin
package com.vibe.app.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.tencent.shadow.core.runtime.HostActivityDelegator
import com.tencent.shadow.core.runtime.ShadowActivity

/**
 * Proxy Activity that hosts a plugin Activity. Declared in the manifest with
 * android:process=":plugin" for crash isolation.
 *
 * Receives the plugin APK path and main class name via Intent extras,
 * loads the plugin classes and resources, and delegates lifecycle calls
 * to the plugin's ShadowActivity.
 */
class PluginContainerActivity : Activity(), HostActivityDelegator {

    private var pluginActivity: ShadowActivity? = null
    private var pluginResources: Resources? = null
    private var pluginClassLoader: ClassLoader? = null
    private var pluginLayoutInflater: LayoutInflater? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS)

        if (apkPath == null || mainClass == null) {
            Log.e(TAG, "Missing apkPath or mainClass in intent")
            finish()
            return
        }

        try {
            // 1. Create plugin ClassLoader
            pluginClassLoader = PluginResourceLoader.createPluginClassLoader(
                context = this,
                apkPath = apkPath,
                parentClassLoader = classLoader,
            )

            // 2. Load plugin resources
            pluginResources = PluginResourceLoader.loadPluginResources(this, apkPath)

            // 3. Create plugin LayoutInflater with plugin resources and classloader
            pluginLayoutInflater = LayoutInflater.from(this).cloneInContext(object : android.content.ContextWrapper(this) {
                override fun getResources(): Resources = pluginResources!!
                override fun getClassLoader(): ClassLoader = pluginClassLoader!!
            })

            // 4. Load and instantiate plugin Activity
            val clazz = pluginClassLoader!!.loadClass(mainClass)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance is ShadowActivity) {
                pluginActivity = instance
                instance.setHostDelegator(this)
                Log.d(TAG, "Plugin activity loaded: $mainClass")
                instance.performCreate(savedInstanceState)
            } else {
                Log.e(TAG, "$mainClass is not a ShadowActivity subclass")
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        pluginActivity?.performResume()
    }

    override fun onPause() {
        pluginActivity?.performPause()
        super.onPause()
    }

    override fun onStop() {
        pluginActivity?.performStop()
        super.onStop()
    }

    override fun onDestroy() {
        pluginActivity?.performDestroy()
        pluginActivity = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        pluginActivity?.onBackPressed() ?: super.onBackPressed()
    }

    // --- HostActivityDelegator implementation ---

    override fun getHostContext(): Context = this

    override fun getHostResources(): Resources = pluginResources ?: super.getResources()

    override fun getHostLayoutInflater(): LayoutInflater = pluginLayoutInflater ?: layoutInflater

    override fun getHostWindow(): Window = window

    override fun getHostWindowManager(): WindowManager = windowManager

    override fun getPluginClassLoader(): ClassLoader = pluginClassLoader ?: classLoader

    override fun superSetContentView(layoutResID: Int) {
        val view = (pluginLayoutInflater ?: layoutInflater).inflate(layoutResID, null)
        super.setContentView(view)
    }

    override fun superSetContentView(view: View) {
        super.setContentView(view)
    }

    override fun <T : View> superFindViewById(id: Int): T = findViewById(id)

    override fun superStartActivity(intent: Intent) {
        super.startActivity(intent)
    }

    override fun superFinish() {
        super.finish()
    }

    override fun setPluginResult(resultCode: Int, data: Intent?) {
        setResult(resultCode, data)
    }

    override fun getHostIntent(): Intent = intent

    companion object {
        private const val TAG = "PluginContainer"
        const val EXTRA_APK_PATH = "plugin_apk_path"
        const val EXTRA_MAIN_CLASS = "plugin_main_class"

        fun createLaunchIntent(
            context: Context,
            apkPath: String,
            mainClassName: String,
        ): Intent = Intent(context, PluginContainerActivity::class.java).apply {
            putExtra(EXTRA_APK_PATH, apkPath)
            putExtra(EXTRA_MAIN_CLASS, mainClassName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL` (app module must depend on shadow-runtime — check and add if needed)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/plugin/
git commit -m "feat: PluginContainerActivity with DexClassLoader and resource loading"
```

---

### Task 4: Create PluginManager and Register in Manifest

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts` (add shadow-runtime dependency)

- [ ] **Step 1: Add shadow-runtime dependency to app module**

In `app/build.gradle.kts` dependencies block, add:

```kotlin
implementation(project(":shadow-runtime"))
```

- [ ] **Step 2: Create PluginManager**

Create `app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt`:

```kotlin
package com.vibe.app.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Launches a plugin APK inside VibeApp via PluginContainerActivity.
     *
     * @param apkPath Absolute path to the signed plugin APK.
     * @param packageName The plugin's package name (used to find the main Activity).
     */
    fun launchPlugin(apkPath: String, packageName: String) {
        val mainClassName = findMainActivity(apkPath, packageName)
        Log.d(TAG, "Launching plugin: apk=$apkPath, main=$mainClassName")

        val intent = PluginContainerActivity.createLaunchIntent(
            context = context,
            apkPath = apkPath,
            mainClassName = mainClassName,
        )
        context.startActivity(intent)
    }

    /**
     * Finds the main Activity class name from the plugin APK's manifest.
     * Falls back to `{packageName}.MainActivity` if parsing fails.
     */
    private fun findMainActivity(apkPath: String, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            val mainActivity = info?.activities?.firstOrNull()?.name
            mainActivity ?: "$packageName.MainActivity"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse plugin manifest, using default", e)
            "$packageName.MainActivity"
        }
    }

    companion object {
        private const val TAG = "PluginManager"
    }
}
```

- [ ] **Step 3: Register PluginContainerActivity in manifest**

In `app/src/main/AndroidManifest.xml`, inside the `<application>` tag, after the `<provider>` block, add:

```xml
<activity
    android:name="com.vibe.app.plugin.PluginContainerActivity"
    android:process=":plugin"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@style/Theme.AppCompat.Light.NoActionBar" />
```

- [ ] **Step 4: Verify build**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew assembleDebug 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt \
       app/src/main/AndroidManifest.xml
git commit -m "feat: PluginManager and manifest registration for plugin container"
```

---

### Task 5: Add BuildMode Support to ProjectInitializer

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/projectinit/ProjectInitializer.kt`

- [ ] **Step 1: Add buildMode parameter to buildProject methods**

In `ProjectInitializer.kt`, modify `TemplateProject.toCompileInput()` to accept `buildMode`:

```kotlin
fun toCompileInput(buildMode: BuildMode = BuildMode.STANDALONE): CompileInput = CompileInput(
    projectId = projectId,
    projectName = projectName,
    packageName = packageName,
    workingDirectory = appModuleDir.absolutePath,
    minSdk = minSdk,
    targetSdk = targetSdk,
    buildType = EngineBuildType.DEBUG,
    buildMode = buildMode,
)
```

Add the `BuildMode` import at the top of the file:

```kotlin
import com.vibe.build.engine.model.BuildMode
```

Modify the public `buildProject` method signature:

```kotlin
suspend fun buildProject(
    projectId: String,
    triggerSource: String = BuildTriggerSource.CHAT_BUTTON,
    progressListener: BuildProgressListener? = null,
    buildMode: BuildMode = BuildMode.STANDALONE,
): BuildResult = withContext(Dispatchers.IO) {
    val project = ensureProject(projectId)
    buildProject(project, triggerSource, progressListener, buildMode)
}
```

Modify the private `buildProject` method:

```kotlin
private suspend fun buildProject(
    project: TemplateProject,
    triggerSource: String = BuildTriggerSource.CHAT_BUTTON,
    progressListener: BuildProgressListener? = null,
    buildMode: BuildMode = BuildMode.STANDALONE,
): BuildResult {
    // ... existing timing code ...
    val result = buildPipeline.run(project.toCompileInput(buildMode), wrappedListener)
    // ... rest unchanged ...
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/projectinit/ProjectInitializer.kt
git commit -m "feat: add BuildMode parameter to ProjectInitializer.buildProject()"
```

---

### Task 6: Modify ChatViewModel for Plugin Run Mode

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt`

- [ ] **Step 1: Add PluginManager dependency and new BuildEvent**

In `ChatViewModel`, add `PluginManager` to constructor:

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val projectRepository: ProjectRepository,
    private val projectInitializer: ProjectInitializer,
    private val diagnosticLogger: ChatDiagnosticLogger,
    private val sessionManager: AgentSessionManager,
    private val buildMutex: BuildMutex,
    private val pluginManager: PluginManager,
) : ViewModel() {
```

Add import:

```kotlin
import com.vibe.app.plugin.PluginManager
import com.vibe.build.engine.model.BuildMode
```

Change `BuildEvent` sealed class to support both modes:

```kotlin
sealed class BuildEvent {
    data class InstallApk(val apkPath: String) : BuildEvent()
    data class LaunchPlugin(val apkPath: String, val packageName: String) : BuildEvent()
}
```

- [ ] **Step 2: Modify runBuild to use PLUGIN mode**

Replace the `runBuild()` method:

```kotlin
fun runBuild() {
    val projectId = _currentProjectId.value
    Log.d("RunBuild", "runBuild called, projectId=$projectId")
    if (projectId == null) {
        Log.w("RunBuild", "projectId is null, aborting")
        return
    }
    _isBuildRunning.update { true }
    _buildProgress.update { BuildProgressUiState(isVisible = true) }
    viewModelScope.launch {
        try {
            Log.d("RunBuild", "Starting PLUGIN build for projectId=$projectId")
            val result = buildMutex.withBuildLock {
                projectInitializer.buildProject(
                    projectId = projectId,
                    triggerSource = BuildTriggerSource.CHAT_BUTTON,
                    progressListener = buildProgressListener(),
                    buildMode = BuildMode.PLUGIN,
                )
            }
            Log.d("RunBuild", "buildProject finished: status=${result.status}")
            if (result.status == BuildStatus.SUCCESS) {
                val signedApkPath = result.artifacts
                    .firstOrNull { it.stage == BuildStage.SIGN }?.path
                if (signedApkPath != null) {
                    val packageName = projectInitializer.projectPackageName(projectId)
                    _buildEvent.emit(BuildEvent.LaunchPlugin(signedApkPath, packageName))
                }
            } else {
                sendBuildErrorToChat(buildBuildErrorMessage(result))
            }
        } finally {
            _isBuildRunning.update { false }
            _buildProgress.update { BuildProgressUiState() }
        }
    }
}
```

- [ ] **Step 3: Add installBuild method for standalone install**

Add a new method:

```kotlin
fun installBuild() {
    val projectId = _currentProjectId.value
    if (projectId == null) return
    _isBuildRunning.update { true }
    _buildProgress.update { BuildProgressUiState(isVisible = true) }
    viewModelScope.launch {
        try {
            val result = buildMutex.withBuildLock {
                projectInitializer.buildProject(
                    projectId = projectId,
                    triggerSource = BuildTriggerSource.CHAT_BUTTON,
                    progressListener = buildProgressListener(),
                    buildMode = BuildMode.STANDALONE,
                )
            }
            if (result.status == BuildStatus.SUCCESS) {
                val signedApkPath = result.artifacts
                    .firstOrNull { it.stage == BuildStage.SIGN }?.path
                if (signedApkPath != null) {
                    _buildEvent.emit(BuildEvent.InstallApk(signedApkPath))
                }
            } else {
                sendBuildErrorToChat(buildBuildErrorMessage(result))
            }
        } finally {
            _isBuildRunning.update { false }
            _buildProgress.update { BuildProgressUiState() }
        }
    }
}
```

- [ ] **Step 4: Extract shared progress listener**

Add a private helper:

```kotlin
private fun buildProgressListener() = BuildProgressListener { update ->
    val progress = if (update.totalSteps == 0) 0f
    else update.completedSteps.toFloat() / update.totalSteps
    _buildProgress.update {
        it.copy(
            isVisible = true,
            progress = progress.coerceIn(0f, 1f),
            currentStage = update.stage,
        )
    }
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt
git commit -m "feat: ChatViewModel supports plugin run mode and standalone install"
```

---

### Task 7: Update ChatScreen UI

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add install string resource**

In `app/src/main/res/values/strings.xml`, add after the `run` string:

```xml
<string name="install_apk">Install APK</string>
```

- [ ] **Step 2: Update BuildEvent collector to handle LaunchPlugin**

In `ChatScreen.kt`, update the `LaunchedEffect(Unit)` block that collects `buildEvent`:

```kotlin
LaunchedEffect(Unit) {
    chatViewModel.buildEvent.collect { event ->
        when (event) {
            is ChatViewModel.BuildEvent.InstallApk -> installApk(context, event.apkPath)
            is ChatViewModel.BuildEvent.LaunchPlugin -> {
                chatViewModel.pluginManager.launchPlugin(event.apkPath, event.packageName)
            }
        }
    }
}
```

To make `pluginManager` accessible, expose it from ChatViewModel:

In `ChatViewModel.kt`, add a public getter:

```kotlin
val pluginManager: PluginManager get() = pluginManager
```

Wait, the field is already `private val pluginManager`. Rename the injected field and expose:

Actually, simpler: just handle plugin launch in the ViewModel directly. Change `runBuild()` to call `pluginManager.launchPlugin()` directly instead of emitting an event:

In `ChatViewModel.runBuild()`, replace the `_buildEvent.emit(BuildEvent.LaunchPlugin(...))` line with:

```kotlin
pluginManager.launchPlugin(signedApkPath, packageName)
```

Then the BuildEvent collector in ChatScreen only needs to handle `InstallApk`.

- [ ] **Step 3: Add "Install APK" to ChatDropdownMenu**

In `ChatScreen.kt`, modify `ChatTopBar` to accept the new callback:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String,
    isChatMenuEnabled: Boolean,
    isRunEnabled: Boolean,
    isMoreOptionsEnabled: Boolean,
    isProjectMenuEnabled: Boolean,
    buildProgress: Float,
    isBuildProgressVisible: Boolean,
    onBackAction: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onUpdateProjectNameClick: () -> Unit,
    onRunClick: () -> Unit,
    onInstallApkClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onExportSourceCodeItemClick: () -> Unit,
    onExportApkItemClick: () -> Unit,
)
```

Pass `onInstallApkClick` through to `ChatDropdownMenu`:

```kotlin
ChatDropdownMenu(
    isDropdownMenuExpanded = isDropDownMenuExpanded,
    isChatMenuEnabled = isChatMenuEnabled,
    isProjectMenuEnabled = isProjectMenuEnabled,
    onDismissRequest = { isDropDownMenuExpanded = false },
    onUpdateProjectNameClick = {
        onUpdateProjectNameClick.invoke()
        isDropDownMenuExpanded = false
    },
    onInstallApkClick = {
        onInstallApkClick()
        isDropDownMenuExpanded = false
    },
    onExportChatItemClick = onExportChatItemClick,
    onExportSourceCodeItemClick = {
        onExportSourceCodeItemClick()
        isDropDownMenuExpanded = false
    },
    onExportApkItemClick = {
        onExportApkItemClick()
        isDropDownMenuExpanded = false
    }
)
```

- [ ] **Step 4: Add Install APK item to ChatDropdownMenu**

Modify `ChatDropdownMenu` composable to include `onInstallApkClick`:

```kotlin
@Composable
fun ChatDropdownMenu(
    isDropdownMenuExpanded: Boolean,
    isChatMenuEnabled: Boolean,
    isProjectMenuEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onUpdateProjectNameClick: () -> Unit,
    onInstallApkClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onExportSourceCodeItemClick: () -> Unit,
    onExportApkItemClick: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.update_project_name)) },
            onClick = onUpdateProjectNameClick
        )
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.install_apk)) },
            onClick = onInstallApkClick
        )
        DropdownMenuItem(
            enabled = isChatMenuEnabled,
            text = { Text(text = stringResource(R.string.export_chat)) },
            onClick = {
                onExportChatItemClick()
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.export_source_code)) },
            onClick = onExportSourceCodeItemClick
        )
        DropdownMenuItem(
            enabled = isProjectMenuEnabled,
            text = { Text(text = stringResource(R.string.export_apk)) },
            onClick = onExportApkItemClick
        )
    }
}
```

- [ ] **Step 5: Update ChatScreen to pass the new callback**

In the `ChatScreen` composable, update the `ChatTopBar` call inside the `Scaffold.topBar`:

```kotlin
ChatTopBar(
    projectName ?: chatRoom.title,
    isChatMenuEnabled,
    runButtonEnabled,
    isMoreOptionsEnabled = isIdle,
    isProjectMenuEnabled,
    buildProgress = buildProgress.progress,
    isBuildProgressVisible = buildProgress.isVisible,
    onBackAction,
    scrollBehavior,
    chatViewModel::openProjectNameDialog,
    chatViewModel::runBuild,
    onInstallApkClick = { chatViewModel.installBuild() },
    onExportChatItemClick = {
        scope.launch { exportChat(context, chatViewModel) }
    },
    onExportSourceCodeItemClick = {
        val projectId = currentProjectId ?: return@ChatTopBar
        scope.launch { exportSourceCode(context, projectId) }
    },
    onExportApkItemClick = {
        scope.launch {
            val apkPath = withContext(Dispatchers.IO) { chatViewModel.getSignedApkPath() }
            if (apkPath != null) {
                shareApk(context, apkPath)
            } else {
                Toast.makeText(context, "No built APK found. Please run a build first.", Toast.LENGTH_SHORT).show()
            }
        }
    }
)
```

- [ ] **Step 6: Verify full build**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew assembleDebug 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatScreen.kt \
       app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt \
       app/src/main/res/values/strings.xml
git commit -m "feat: Run button uses plugin mode, Install APK moved to more options"
```

---

### Task 8: Integration Verification

- [ ] **Step 1: Run all tests**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew test 2>&1 | tail -15`

Expected: all tests pass.

- [ ] **Step 2: Run full assembleDebug**

Run: `cd /Users/skykai/Documents/work/VibeApp && ./gradlew assembleDebug 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Verify shadow-runtime.jar is bundled**

Run: `cd /Users/skykai/Documents/work/VibeApp && ls -la build-engine/src/main/assets/shadow-runtime.jar`

Expected: file exists, non-zero size.

- [ ] **Step 4: Verify manifest contains PluginContainerActivity**

Run: `cd /Users/skykai/Documents/work/VibeApp && grep -A3 "PluginContainerActivity" app/src/main/AndroidManifest.xml`

Expected: Activity declaration with `android:process=":plugin"`

- [ ] **Step 5: Verify default behavior unchanged for agent tool builds**

Check that `RunBuildPipelineTool` and `DefaultProjectWorkspace.buildProject()` still use STANDALONE mode by default (they don't pass `buildMode`):

Run: `cd /Users/skykai/Documents/work/VibeApp && grep -n "buildProject" app/src/main/kotlin/com/vibe/app/feature/project/DefaultProjectWorkspace.kt`

Expected: Call without `buildMode` parameter (defaults to STANDALONE).

- [ ] **Step 6: Final commit if fixups needed**

```bash
git add -A
git commit -m "fix: integration fixes for shadow plugin system"
```

---

## Summary

After all tasks, the user flow is:

```
[Run Button] (ChatScreen TopAppBar)
  → ChatViewModel.runBuild()
  → ProjectInitializer.buildProject(buildMode = PLUGIN)
  → BuildWorkspace uses shadow-androidx-classes.jar + shadow-runtime.jar
  → Build succeeds → PluginManager.launchPlugin(apkPath, packageName)
  → PluginContainerActivity starts in :plugin process
  → Loads plugin classes via DexClassLoader
  → Loads plugin resources via AssetManager reflection
  → Delegates lifecycle to plugin's ShadowActivity
  → Plugin UI renders inside VibeApp

[More Options → Install APK]
  → ChatViewModel.installBuild()
  → ProjectInitializer.buildProject(buildMode = STANDALONE)
  → Build succeeds → Intent(ACTION_VIEW) → System installer
```
