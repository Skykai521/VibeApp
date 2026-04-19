package com.vibe.app.shadow

import android.content.ComponentName
import android.content.Context
import com.tencent.shadow.core.loader.infos.ContainerProviderInfo
import com.tencent.shadow.core.loader.managers.ComponentManager

/**
 * Maps every plugin Activity to the host's single
 * `PluginContainerActivity` declared in `AndroidManifest.xml`. We don't
 * differentiate by launch mode or theme yet — the on-device build pipeline
 * only ships one container, and the Compose-only template never needs
 * specialised launch behaviour. If we ever support legacy launchMode or
 * `theme.NoActionBar`-style overrides we'll need to declare additional
 * container Activities and pick between them here based on
 * `pluginActivity` package or PluginManifest.ActivityInfo metadata.
 *
 * Lives in `loader.apk` (not the host APK): Shadow loads the loader APK
 * into `:shadow_plugin` via DexClassLoader and uses
 * `CoreLoaderFactoryImpl` (this module) to construct the per-process
 * `ShadowPluginLoader`. The host package name we resolve to comes from
 * the `Context` Shadow hands the factory at load time.
 */
internal class VibeAppComponentManager(
    private val hostContext: Context,
) : ComponentManager() {

    private val containerActivityName =
        "com.tencent.shadow.core.runtime.container.PluginContainerActivity"

    override fun onBindContainerActivity(pluginActivity: ComponentName): ComponentName =
        ComponentName(hostContext.packageName, containerActivityName)

    override fun onBindContainerContentProvider(
        pluginContentProvider: ComponentName,
    ): ContainerProviderInfo {
        // Compose-only plugins don't ship ContentProviders. Returning a
        // synthetic placeholder rather than throwing keeps Shadow's
        // PluginContentProviderManager happy when it iterates plugin
        // manifests with an empty providers list — it asks for the
        // mapping per declared provider, but never per non-existent one.
        return ContainerProviderInfo(
            "${hostContext.packageName}.shadow.PluginContentProviderUnused",
            "${hostContext.packageName}.shadow.unused",
        )
    }
}
