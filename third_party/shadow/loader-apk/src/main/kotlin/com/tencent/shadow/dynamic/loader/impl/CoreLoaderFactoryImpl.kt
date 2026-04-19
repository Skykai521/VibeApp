package com.tencent.shadow.dynamic.loader.impl

import android.content.Context
import com.tencent.shadow.core.loader.ShadowPluginLoader
import com.vibe.app.shadow.VibeAppPluginLoader

/**
 * Resolved by `DynamicPluginLoader` via reflection at the hard-coded
 * fully-qualified name `com.tencent.shadow.dynamic.loader.impl.CoreLoaderFactoryImpl`.
 * The package + class name are part of Shadow's public-by-convention
 * loader contract — don't rename.
 *
 * Returns the host-specific `ShadowPluginLoader`. For VibeApp that's
 * `VibeAppPluginLoader`, which only differs from the abstract base in
 * the `ComponentManager` it hands back (mapping plugin Activities to
 * the single container Activity declared in the host manifest).
 */
class CoreLoaderFactoryImpl : CoreLoaderFactory {
    override fun build(hostAppContext: Context): ShadowPluginLoader =
        VibeAppPluginLoader(hostAppContext)
}
