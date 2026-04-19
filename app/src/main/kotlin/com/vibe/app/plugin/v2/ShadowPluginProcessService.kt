package com.vibe.app.plugin.v2

import com.tencent.shadow.dynamic.host.PluginProcessService

/**
 * Hosts Shadow's PPS (PluginProcessService) inside the host app's
 * dedicated plugin process. Declared in `AndroidManifest.xml` with
 * `android:process=":shadow_plugin"` so Shadow's loader + runtime APKs
 * load into a process isolated from the host UI.
 *
 * No additional behavior over the vendored [PluginProcessService] — the
 * subclass just gives us a manifest-referenceable component class name.
 */
class ShadowPluginProcessService : PluginProcessService()
