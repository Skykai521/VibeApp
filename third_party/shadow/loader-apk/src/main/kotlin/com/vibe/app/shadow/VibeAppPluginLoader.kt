package com.vibe.app.shadow

import android.content.Context
import com.tencent.shadow.core.loader.ShadowPluginLoader
import com.tencent.shadow.core.loader.managers.ComponentManager

/**
 * Concrete `ShadowPluginLoader` for VibeApp. The base class does the
 * heavy lifting — apk parsing, classloader hierarchy, ResourceManager,
 * delegate provider plumbing. We only need to provide the
 * `ComponentManager` subclass that knows how to map plugin Activities
 * to the host's container Activity.
 */
internal class VibeAppPluginLoader(
    private val hostContext: Context,
) : ShadowPluginLoader(hostContext) {

    private val componentManager = VibeAppComponentManager(hostContext)

    override fun getComponentManager(): ComponentManager = componentManager
}
