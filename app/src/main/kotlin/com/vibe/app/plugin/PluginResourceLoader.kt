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
    /**
     * Creates a child-first ClassLoader for plugin APKs.
     *
     * The plugin APK contains shadow-transformed AndroidX classes where the
     * superclass chain ends at ShadowActivity instead of android.app.Activity.
     *
     * The inner DexClassLoader uses the **boot classloader** as parent (not
     * the host app's classloader). This prevents it from finding the host's
     * original untransformed AndroidX classes via parent-first delegation.
     *
     * The outer PluginChildFirstClassLoader controls routing:
     * - Plugin's own classes + transformed AndroidX → from inner DexClassLoader
     * - Framework classes (android/java/dalvik) → from boot classloader
     * - Shadow runtime classes → from host classloader (shared class identity)
     */
    fun createPluginClassLoader(
        context: Context,
        apkPath: String,
        parentClassLoader: ClassLoader,
    ): ClassLoader {
        val dexOutputDir = context.getDir("plugin_dex", Context.MODE_PRIVATE)
        dexOutputDir.listFiles()?.forEach { it.delete() }

        // Use boot classloader as the DexClassLoader's parent so it cannot
        // find the host's untransformed AndroidX classes.
        val bootClassLoader = ClassLoader.getSystemClassLoader().parent
            ?: ClassLoader.getSystemClassLoader()

        val innerLoader = dalvik.system.DexClassLoader(
            apkPath,
            dexOutputDir.absolutePath,
            null,
            bootClassLoader,
        )

        return PluginChildFirstClassLoader(innerLoader, parentClassLoader)
    }
}

/**
 * Child-first ClassLoader:
 * 1. Shadow runtime classes → parent (host), for shared class identity
 * 2. Everything else → try inner DexClassLoader first (plugin APK)
 * 3. Fallback → parent (host)
 *
 * The inner DexClassLoader's parent is the boot classloader, so it only
 * finds framework classes + plugin DEX classes (including shadow-transformed
 * AndroidX). It will NOT find the host's original AndroidX.
 */
private class PluginChildFirstClassLoader(
    private val pluginLoader: ClassLoader,
    parent: ClassLoader,
) : ClassLoader(parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Check if already loaded by this classloader
        findLoadedClass(name)?.let { return it }

        // Shadow runtime must come from the host for shared class identity
        if (name.startsWith("com.tencent.shadow.core.runtime.")) {
            return parent.loadClass(name)
        }

        // Try plugin first — the inner DexClassLoader will find:
        // - Plugin's own classes (com.vibe.generated.*)
        // - Shadow-transformed AndroidX (androidx.*)
        // - Framework classes via boot classloader (android.*, java.*)
        return try {
            pluginLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            // Fallback to host for anything not in plugin
            parent.loadClass(name)
        }
    }
}
