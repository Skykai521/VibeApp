package com.vibe.app.plugin

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Invisible trampoline launched from the "tap to preview" notification.
 *  Runs in the main process with foreground privileges, so PluginManager
 *  can legally start the plugin container Activity.
 *
 *  Extends ComponentActivity (not bare Activity) because Hilt's
 *  @AndroidEntryPoint requires a ComponentActivity subclass. */
@AndroidEntryPoint
class PluginLaunchProxyActivity : ComponentActivity() {
    @Inject lateinit var pluginManager: PluginManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (apkPath != null && packageName != null && projectId != null) {
            pluginManager.launchPlugin(apkPath, packageName, projectId)
        }
        finish()
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
