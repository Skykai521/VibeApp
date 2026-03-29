package com.vibe.app.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun launchPlugin(apkPath: String, packageName: String) {
        val mainClassName = findMainActivity(apkPath, packageName)
        Log.d(TAG, "Launching plugin: apk=$apkPath, main=$mainClassName")
        val intent = PluginContainerActivity.createLaunchIntent(
            context = context,
            apkPath = apkPath,
            mainClassName = mainClassName,
        )
        context.startActivity(intent)
    }

    private fun findMainActivity(apkPath: String, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            info?.activities?.firstOrNull()?.name ?: "$packageName.MainActivity"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse plugin manifest, using default", e)
            "$packageName.MainActivity"
        }
    }

    companion object {
        private const val TAG = "PluginManager"
    }
}
