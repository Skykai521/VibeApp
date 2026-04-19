package com.vibe.build.gradle

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands an on-device APK to the system installer via the same FileProvider
 * VibeApp already declares for shared internal files.
 *
 * Caller responsibilities:
 *   - APK must be a real file under VibeApp's `filesDir` so the existing
 *     `<files-path>` entry in `res/xml/file_paths.xml` covers it.
 *   - Caller's process needs `android.permission.REQUEST_INSTALL_PACKAGES`
 *     (already declared in the main app manifest).
 *
 * The system installer will pop up its own confirmation UI; nothing else
 * to do post-install.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun install(apk: File) {
        require(apk.isFile) { "APK not found at $apk" }
        require(apk.canRead()) { "APK not readable: $apk" }

        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apk)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
