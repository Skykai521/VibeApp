package com.vibe.app.plugin

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources

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
     * - com.tencent.shadow.core.runtime.* → host (shared class identity)
     * - androidx.* / com.google.android.material.* → host (shared AndroidX, no conflict)
     * - everything else → boot classloader (framework only)
     *
     * Since we don't ASM-transform AndroidX, host and plugin use identical
     * AndroidX classes, so sharing them avoids ClassCastException and reduces
     * plugin DEX size.
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
 * Routes shadow runtime + AndroidX + Material classes to the host.
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
        return name.startsWith("com.tencent.shadow.core.runtime.") ||
            name.startsWith("androidx.") ||
            name.startsWith("com.google.android.material.")
    }
}
