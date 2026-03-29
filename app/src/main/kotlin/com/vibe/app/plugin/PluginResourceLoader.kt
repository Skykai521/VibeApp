package com.vibe.app.plugin

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources

object PluginResourceLoader {

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
     * Creates a child-first ClassLoader for plugin APKs.
     *
     * The plugin APK contains shadow-transformed AndroidX classes where the
     * superclass chain ends at ShadowActivity instead of android.app.Activity.
     * A standard parent-first DexClassLoader would skip these and load the
     * host's original (untransformed) AndroidX from the parent, breaking the
     * ShadowActivity inheritance chain.
     *
     * This ClassLoader loads classes from the plugin first, except:
     * - java.* / android.* / dalvik.* — always from parent (framework)
     * - com.tencent.shadow.core.runtime.* — always from parent (shared class identity)
     */
    fun createPluginClassLoader(
        context: Context,
        apkPath: String,
        parentClassLoader: ClassLoader,
    ): ClassLoader {
        val dexOutputDir = context.getDir("plugin_dex", Context.MODE_PRIVATE)
        dexOutputDir.listFiles()?.forEach { it.delete() }

        val innerLoader = dalvik.system.DexClassLoader(
            apkPath,
            dexOutputDir.absolutePath,
            null,
            parentClassLoader,
        )

        return PluginChildFirstClassLoader(innerLoader, parentClassLoader)
    }
}

/**
 * Child-first ClassLoader that tries the plugin's DexClassLoader before the parent,
 * except for framework and shadow-runtime classes which must come from the parent.
 */
private class PluginChildFirstClassLoader(
    private val pluginLoader: ClassLoader,
    parent: ClassLoader,
) : ClassLoader(parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Check if already loaded
        findLoadedClass(name)?.let { return it }

        // These must come from the parent to share class identity
        if (shouldLoadFromParent(name)) {
            return parent.loadClass(name)
        }

        // Try plugin first (child-first for AndroidX, generated code, etc.)
        return try {
            pluginLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            parent.loadClass(name)
        }
    }

    private fun shouldLoadFromParent(name: String): Boolean {
        return name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("android.") ||
            name.startsWith("dalvik.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("com.tencent.shadow.core.runtime.")
    }
}
