package com.vibe.build.engine.internal

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal object PackagedBinaryResolver {

    private const val tag = "BuildEngine-Binary"

    fun resolveAapt2(context: Context): File {
        return resolveExecutable(
            context = context,
            libraryFileName = "libaapt2.so",
            extractedFileName = "aapt2",
        )
    }

    private fun resolveExecutable(
        context: Context,
        libraryFileName: String,
        extractedFileName: String,
    ): File {
        findInstalledLibrary(context, libraryFileName)?.let { installedLibrary ->
            Log.d(tag, "Using installed native binary ${installedLibrary.absolutePath}")
            return installedLibrary
        }

        val apkSources = listOfNotNull(
            context.applicationInfo.sourceDir,
            *context.applicationInfo.splitSourceDirs.orEmpty(),
        )
        val abiCandidates = supportedAbiCandidates()

        for (apkPath in apkSources) {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                continue
            }
            ZipFile(apkFile).use { zip ->
                for (abi in abiCandidates) {
                    val entryName = "lib/$abi/$libraryFileName"
                    val entry = zip.getEntry(entryName) ?: continue
                    val outputFile = File(
                        context.filesDir,
                        "build-engine/native-tools/$abi/$extractedFileName",
                    )
                    outputFile.parentFile?.mkdirs()
                    val shouldRewrite = !outputFile.exists() || outputFile.length() != entry.size
                    if (shouldRewrite) {
                        zip.getInputStream(entry).use { input ->
                            outputFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (!outputFile.setExecutable(true, true)) {
                        throw IOException("Failed to mark executable: ${outputFile.absolutePath}")
                    }
                    Log.d(
                        tag,
                        "Extracted $libraryFileName from ${apkFile.name}!/$entryName to ${outputFile.absolutePath}",
                    )
                    return outputFile
                }
            }
        }

        throw IOException(
            buildString {
                append("Unable to locate ")
                append(libraryFileName)
                append(" in installed native libs under nativeLibraryDir=")
                append(context.applicationInfo.nativeLibraryDir)
                append(" or APK lib entries for ABIs=")
                append(abiCandidates.joinToString())
            },
        )
    }

    private fun findInstalledLibrary(context: Context, libraryFileName: String): File? {
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        val directLibrary = File(nativeLibraryDir, libraryFileName)
        if (directLibrary.exists()) {
            return directLibrary
        }

        val libRoot = nativeLibraryDir.parentFile ?: return null
        val abiCandidates = supportedAbiCandidates()
        abiCandidates.forEach { abi ->
            val candidate = File(libRoot, "$abi/$libraryFileName")
            if (candidate.exists()) {
                return candidate
            }
        }

        libRoot.listFiles()
            ?.sortedBy { it.name }
            ?.forEach { abiDir ->
                val candidate = File(abiDir, libraryFileName)
                if (candidate.exists()) {
                    return candidate
                }
            }

        return null
    }

    private fun supportedAbiCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        Build.SUPPORTED_ABIS.forEach { abi ->
            candidates += abi
            when (abi) {
                "arm64" -> candidates += "arm64-v8a"
                "arm64-v8a" -> candidates += "arm64"
                "armeabi" -> candidates += "armeabi-v7a"
                "armeabi-v7a" -> candidates += "armeabi"
            }
        }
        candidates += listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")
        return candidates.toList()
    }
}
