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
     * Creates a ClassLoader for plugin APKs.
     *
     * Generated apps extend ShadowActivity directly (not AppCompatActivity).
     * The plugin APK's DEX contains ShadowActivity from shadow-runtime.jar,
     * but we need the host's ShadowActivity for shared class identity
     * (so `instanceof` works in PluginContainerActivity).
     *
     * [ShadowBridgeClassLoader] sits between boot and DexClassLoader:
     * - com.tencent.shadow.core.runtime.* → host classloader (shared identity)
     * - everything else → boot classloader (framework only)
     *
     * DexClassLoader's parent-first delegation finds ShadowActivity via the
     * bridge (host's version), while plugin's own classes are loaded from DEX.
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
 * Routes shadow runtime classes to the host for shared class identity.
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
