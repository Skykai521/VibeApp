package com.vibe.app.plugin

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources

object PluginResourceLoader {

    /**
     * Creates Resources with PLUGIN APK first, HOST APK second.
     *
     * Order matters: resource ID lookup checks tables in insertion order.
     * Plugin-first ensures plugin R.layout/R.id values resolve to plugin
     * resources (not colliding host resources). Host-second provides theme
     * attribute definitions (colorPrimary, actionBarSize, etc.) that
     * Material Components views need during inflation.
     */
    fun loadPluginResources(hostContext: Context, apkPath: String): Resources {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true
        addAssetPath.invoke(assetManager, apkPath) // Plugin APK first (R.layout/R.id priority)
        addAssetPath.invoke(assetManager, hostContext.applicationInfo.sourceDir) // Host APK second (theme attrs)
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
