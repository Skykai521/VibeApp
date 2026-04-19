package com.vibe.app.plugin.legacy

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File

object PluginResourceLoader {

    /**
     * Creates Resources backed by the plugin APK only.
     *
     * The plugin APK already contains AndroidX/Material Components resources
     * (compiled in via AAPT2 -R overlay). A theme must be applied separately
     * via [Resources.newTheme] + [Resources.Theme.applyStyle] to make
     * ?attr/ references resolvable.
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
     * Creates a ClassLoader for plugin APKs.
     *
     * [ShadowBridgeClassLoader] sits between boot and DexClassLoader:
     * - com.tencent.shadow.core.runtime.* → host (shared class identity for delegation)
     * - everything else → boot classloader (framework only)
     *
     * AndroidX / Material classes are loaded from the plugin DEX (not shared
     * with the host) so that their R-class constants match the plugin's own
     * resource table. This avoids the resource-ID mismatch that causes
     * Material view inflation crashes in plugin mode.
     */
    fun createPluginClassLoader(
        context: Context,
        apkPath: String,
        parentClassLoader: ClassLoader,
    ): ClassLoader {
        val dexOutputDir = context.getDir("plugin_dex", Context.MODE_PRIVATE)
        dexOutputDir.listFiles()?.forEach { it.delete() }

        val bootClassLoader = ClassLoader.getSystemClassLoader().parent
            ?: ClassLoader.getSystemClassLoader()

        val bridgeLoader = ShadowBridgeClassLoader(
            hostLoader = parentClassLoader,
            bootParent = bootClassLoader,
        )

        // Android 10+ requires DEX files to be non-writable
        File(apkPath).setReadOnly()

        return dalvik.system.DexClassLoader(
            apkPath,
            dexOutputDir.absolutePath,
            null,
            bridgeLoader,
        )
    }
}

/**
 * Bridge ClassLoader between boot and DexClassLoader.
 * Routes shadow runtime classes to the host (shared class identity for delegation).
 * AndroidX / Material classes are NOT routed to the host — they load from the
 * plugin DEX so their R-class IDs match the plugin's own resource table.
 */
private class ShadowBridgeClassLoader(
    private val hostLoader: ClassLoader,
    bootParent: ClassLoader,
) : ClassLoader(bootParent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (shouldLoadFromHost(name)) {
            return hostLoader.loadClass(name)
        }
        return super.loadClass(name, resolve)
    }

    private fun shouldLoadFromHost(name: String): Boolean {
        // Share Shadow runtime + specific androidx API packages that appear in
        // the Activity class hierarchy's method signatures (ActionBar, Toolbar,
        // FragmentManager, Lifecycle, etc.). Without sharing, the ART verifier
        // sees two different class identities for the same type → VerifyError.
        //
        // Widget/view packages (cardview, coordinatorlayout, recyclerview, etc.)
        // must NOT be shared — their constructors use R.styleable with host IDs
        // that don't match the plugin resource table, causing InflateException.
        //
        // com.google.android.material.* is also NOT shared — Material classes
        // inflate layouts and need plugin R-class IDs.
        // Only share Shadow runtime classes (delegation interfaces).
        // ALL of androidx.* and com.google.android.material.* load from the
        // plugin DEX so they use plugin R-class IDs consistently.
        //
        // Sharing ANY androidx class cascades: ActionBar → ActionMode →
        // AppCompatDelegateImpl → VerifyError chain. The generated code
        // avoids referencing host-only types (ActionBar, Toolbar) by using
        // Toolbar as a regular View instead of setSupportActionBar().
        return name.startsWith("com.tencent.shadow.core.runtime.")
    }
}
