package com.vibe.app.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Receives crash/log reports from installed generated apps (DebugBridge).
 * Caller identity is verified by (a) package prefix com.vibe.generated.p*,
 * (b) signing cert digest == the bundled debug keystore cert that
 * DebugApkSigner uses for every generated APK. This works regardless of how
 * VibeApp itself is signed, which is why a signature <permission> is NOT used.
 */
class DebugReportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        val projectId = DebugReportValidator.projectIdFromPackage(callingPackage) ?: return null
        if (!isCallerSignedByBundledDebugKey(callingPackage!!)) return null
        val kind = values?.getAsString("kind") ?: return null
        val content = values.getAsString("content") ?: return null
        if (!File(ctx.filesDir, "projects/$projectId").isDirectory) return null

        val fileName = when (kind) {
            "crash" -> "crash.log"
            "log" -> "app.log"
            else -> return null
        }
        val logDir = File(ctx.filesDir, "projects/$projectId/logs").apply { mkdirs() }
        val target = File(logDir, fileName)
        try {
            if (target.exists() && target.length() > 256 * 1024) {
                File(target.path + ".1").delete()
                target.renameTo(File(target.path + ".1"))
            }
            target.appendText(DebugReportValidator.truncateForAppend(content))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist debug report", e)
            return null
        }
        return uri
    }

    private fun isCallerSignedByBundledDebugKey(pkg: String): Boolean = try {
        val pm = context!!.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = info.signingInfo?.apkContentsSigners ?: return false
        certs.any { sig ->
            val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) } == GENERATED_APP_CERT_SHA256
        }
    } catch (e: Exception) {
        false
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.vibe.debugreport"
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0

    companion object {
        private const val TAG = "DebugReportProvider"
        const val AUTHORITY = "com.vibe.app.debugreport"

        /**
         * SHA-256 of the DER-encoded X.509 cert in build-engine's bundled testkey
         * (testkey.x509.pem.zip, the AOSP testkey used by DebugApkSigner for every
         * generated APK). Matches PackageInfo.signingInfo.apkContentsSigners digests.
         */
        const val GENERATED_APP_CERT_SHA256 =
            "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
    }
}
