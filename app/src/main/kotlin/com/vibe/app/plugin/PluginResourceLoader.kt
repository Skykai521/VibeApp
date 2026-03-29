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
     * Creates a ClassLoader for plugin APKs with correct class resolution.
     *
     * The plugin APK contains shadow-transformed AndroidX where the superclass
     * chain is: AppCompatActivity → ... → ShadowActivity → Activity.
     *
     * Key challenge: when DexClassLoader resolves superclasses internally, it
     * uses its own parent chain. If the parent doesn't have ShadowActivity,
     * DexClassLoader loads it from the plugin DEX — creating a different Class
     * object than the host's ShadowActivity, breaking `instanceof`.
     *
     * Solution: insert a [ShadowBridgeClassLoader] between boot and DexClassLoader:
     *
     * ```
     * DexClassLoader (plugin APK)
     *   └── ShadowBridgeClassLoader
     *         ├── com.tencent.shadow.core.runtime.* → host classloader
     *         └── everything else → boot classloader
     * ```
     *
     * This way, when DexClassLoader resolves ShadowActivity via parent-first
     * delegation, the bridge intercepts it and returns the host's version.
     * AndroidX classes are NOT in boot or bridge, so DexClassLoader loads
     * them from its own DEX (the shadow-transformed versions). The returned
     * DexClassLoader can be used directly — no outer wrapper needed.
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
 * Bridge ClassLoader that sits between boot and DexClassLoader.
 *
 * Intercepts `com.tencent.shadow.core.runtime.*` and loads them from the
 * host classloader (for shared class identity). Everything else delegates
 * to the boot classloader (framework classes only).
 *
 * This ensures that when DexClassLoader resolves the superclass chain
 * `AppCompatActivity → ... → ShadowActivity`, it finds the HOST's
 * ShadowActivity (via this bridge), not a duplicate from the plugin DEX.
 */
private class ShadowBridgeClassLoader(
    private val hostLoader: ClassLoader,
    bootParent: ClassLoader,
) : ClassLoader(bootParent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("com.tencent.shadow.core.runtime.")) {
            return hostLoader.loadClass(name)
        }
        return super.loadClass(name, resolve)
    }
}
