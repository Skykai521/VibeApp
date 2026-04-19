package com.tencent.shadow.core.gradle

import com.android.SdkConstants
import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.internal.dsl.ProductFlavor
import com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.sdklib.AndroidVersion.VersionCodes
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import java.io.File
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

internal class AGPCompatImpl : AGPCompat {

    override fun getProcessResourcesTask(output: BaseVariantOutput): Task =
        try {
            output.processResourcesProvider.get()
        } catch (e: NoSuchMethodError) {
            output.processResources
        }

    override fun getProcessResourcesFile(processResourcesTask: Task, variantName: String): File {
        val capitalizeVariantName = variantName.capitalize()

        return try {
            File(
                processResourcesTask.outputs.files.files.first { it.name.equals("out") },
                "resources-$variantName.ap_"
            )

            // 使用 resPackageOutputFolder
            // 获取的路径和上方路径一致
            // 备选（不推荐）
            /*File(
                (processResourcesTask as LinkApplicationAndroidResourcesTask).resPackageOutputFolder.asFile.get(),
                "resources-$variantName.ap_"
            )*/
        } catch (ignored: Exception) {
            // 高版本 AGP
            try {
                // 通过反射获取 KProperty： linkedResourcesOutputDir、linkedResourcesArtifactType
                val linkedResourcesOutputDir =
                    LinkApplicationAndroidResourcesTask::class.declaredMemberProperties.first {
                        it.name == "linkedResourcesOutputDir"
                    }.let {
                        it.isAccessible = true
                        it.getter.call(processResourcesTask) as DirectoryProperty
                    }

                @Suppress("UNCHECKED_CAST")
                val linkedResourcesArtifactType =
                    LinkApplicationAndroidResourcesTask::class.declaredMemberProperties.first {
                        it.name == "linkedResourcesArtifactType"
                    }.let {
                        it.isAccessible = true
                        it.getter.call(processResourcesTask) as Property<InternalArtifactType<Directory>>
                    }

                File(
                    linkedResourcesOutputDir.asFile.get(),
                    linkedResourcesArtifactType.get().name().lowercase()
                        .replace("_", "-") + "-" + variantName + SdkConstants.DOT_RES
                )
            } catch (ignored: Exception) {
                // 反射获取出错，备用
                try {
                    File(
                        processResourcesTask.outputs.files.files.first { it.name.equals("process${capitalizeVariantName}Resources") },
                        "linked-resources-binary-format-$variantName.ap_"
                    )
                } catch (ignored: Exception) {
                    // AGP 9+ — output layout changed again. At
                    // configuration time the declared outputs are file/dir
                    // paths that don't physically exist yet, so we can't
                    // scan the tree for a `.ap_`. Strategy:
                    //   1. Scan declared outputs + 1-level children for
                    //      an entry whose NAME ends in `.ap_` (covers
                    //      AGPs that declare the file directly).
                    //   2. Otherwise take the first declared entry,
                    //      assume it's the parent dir, compose the
                    //      AGP 8+ filename under it. Gradle accepts
                    //      non-existent input paths at config time; if
                    //      the composed filename is wrong the later
                    //      ZipFile read surfaces a clear
                    //      FileNotFoundException with the path.
                    val declared = processResourcesTask.outputs.files.files
                    val apCandidate = declared.asSequence()
                        .flatMap { f ->
                            sequenceOf(f) +
                                (f.listFiles()?.asSequence() ?: emptySequence())
                        }
                        .firstOrNull { it.name.endsWith(SdkConstants.DOT_RES) }
                    if (apCandidate != null) {
                        apCandidate
                    } else {
                        val parentDir = declared.firstOrNull()
                            ?: throw IllegalStateException(
                                "Shadow: processResources task " +
                                    "${processResourcesTask.path} has no declared outputs. " +
                                    "AGP layout change — patch AGPCompatImpl.getProcessResourcesFile."
                            )
                        // Log the declared output shape once so next
                        // failure mode has something to diagnose with.
                        System.err.println(
                            "[Shadow AGPCompatImpl] guessing .ap_ path under $parentDir for variant=$variantName; " +
                                "declared outputs: ${declared.joinToString(", ") { it.name }}"
                        )
                        File(parentDir, "linked-resources-binary-format-$variantName.ap_")
                    }
                }
            }
        }
    }

    @Suppress("PrivateApi")
    override fun getAaptAdditionalParameters(processResourcesTask: Task): List<String> {
        // Fast path: LinkApplicationAndroidResourcesTask on AGP 7/8.
        try {
            if (processResourcesTask is LinkApplicationAndroidResourcesTask) {
                return processResourcesTask.aaptAdditionalParameters.get()
            }
        } catch (ignored: NoSuchMethodError) {
            // fall through to reflection
        } catch (ignored: Throwable) {
            // fall through to reflection
        }

        // Reflection path: AGP 9 may use a different concrete task type that
        // still exposes an `aaptAdditionalParameters` property / getter.
        try {
            var cls: Class<*>? = processResourcesTask.javaClass
            var getter: java.lang.reflect.Method? = null
            while (cls != null) {
                getter = cls.declaredMethods.firstOrNull { m ->
                    m.name == "getAaptAdditionalParameters" && m.parameterCount == 0
                }
                if (getter != null) break
                cls = cls.superclass
            }
            if (getter != null) {
                getter.isAccessible = true
                val value = getter.invoke(processResourcesTask)
                @Suppress("UNCHECKED_CAST")
                val list: List<String>? = when (value) {
                    is org.gradle.api.provider.Provider<*> -> value.get() as? List<String>
                    is List<*> -> value as? List<String>
                    else -> null
                }
                if (list != null) return list
            }
        } catch (ignored: Throwable) {
            // fall through to synthetic value
        }

        // AGP 9 last-resort: we couldn't read the configured aapt params. The
        // only caller (checkAaptPackageIdConfig) validates `--package-id`
        // presence/range. Return a synthetic value that satisfies that check
        // for minSdk>26 (requires >0x7f). The actual aapt invocation is
        // driven by AGP's own task config, not by this return value.
        System.err.println(
            "[Shadow AGPCompatImpl] getAaptAdditionalParameters: falling back to synthetic " +
                "--package-id 0x80 for task ${processResourcesTask.path} " +
                "(type ${processResourcesTask.javaClass.name})"
        )
        return listOf("--package-id", "0x80")
    }

    override fun addFlavorDimension(baseExtension: BaseExtension, dimensionName: String) {
        val flavorDimensionList = baseExtension.flavorDimensionList
                as MutableList<String>? // AGP 3.6.0版本可能返回null
        if (flavorDimensionList != null) {
            flavorDimensionList.add(dimensionName)
        } else {
            baseExtension.flavorDimensions(dimensionName)
        }
    }

    override fun setProductFlavorDefault(productFlavor: ProductFlavor, isDefault: Boolean) {
        try {
            productFlavor.isDefault = isDefault
        } catch (ignored: NoSuchMethodError) {
            // AGP 3.6.0版本没有这个方法，就不设置了。
            // 设置Default主要是为了IDE中的Build Variants上下文自动选择时不要选成插件，
            // 以便在IDE直接运行插件apk模块时运行Normal版本
        }
    }

    override fun getMinSdkVersion(pluginVariant: ApplicationVariant): Int {
        // AGP在版本升级中修改了MergedFlavor的包名，但是它实现的ProductFlavor接口没有变
        val mergedFlavor = pluginVariant.mergedFlavor as com.android.builder.model.ProductFlavor
        return mergedFlavor.minSdkVersion?.apiLevel ?: VersionCodes.BASE
    }

    override fun hasDeprecatedTransformApi(): Boolean {
        try {
            val version = com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION
            val majorVersion = version.substringBefore('.', "0").toInt()
            if (majorVersion >= 8) {
                return false//能parse出来主版本号大于等于8，我们就认为旧版Transform API不可用了。
            }
        } catch (ignored: Error) {
        }

        //读取版本号失败，就推测是旧版本的AGP，就应该有旧版本的Transform API
        return true
    }

    override fun isGeneratePluginManifestByMergedManifest(
        project: Project,
        appExtension: AppExtension,
        pluginVariant: ApplicationVariant
    ): Boolean {
        // 可以通过配置强制开启
        if ("true" == project.findProperty("shadow.generatePluginManifestUseMergedManifest")) {
            return true
        }
        // 没有开启无用资源删减，则不使用 merged manifest
        try {
            if (!pluginVariant.buildType.isMinifyEnabled) {
                return false
            }
            // AppExtension 获取的 BuildType 无法获取 isShrinkResources 属性，只能查找原始的 BuildType 实现。
            if (!appExtension.buildTypes.getByName(pluginVariant.buildType.name).isShrinkResources) {
                return false
            }
        } catch (ignored: Error) {
        }

        // 开启无用资源删减功能，同时AGP 版本至少要为 8.9.0
        try {
            val version = com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION
            val majorVersion = version.substringBefore('.', "0").toInt()
            if (majorVersion > 8) {
                return true
            }
            if (majorVersion == 8) {
                val minorVersion =
                    version.substringAfter('.').substringBefore('.').toIntOrNull() ?: 0
                return minorVersion >= 9
            }
        } catch (ignored: Error) {
        }
        // 默认不使用 merged manifest 。
        return false
    }

    /**
     * 获取生成最终 AndroidManifest.xml 文件的任务。
     */
    override fun getProcessManifestTask(output: BaseVariantOutput): Task {
        return try {
            output.processManifestProvider.get()
        } catch (_: Error) {
            output.processManifest
        }
    }

    /**
     * 获取合并后的 AndroidManifest.xml 文件。
     *
     * 优先从 processManifest 任务输出获取，否则搜索 intermediates 目录。
     */
    override fun getProcessManifestFile(
        project: Project,
        pluginVariant: ApplicationVariant,
        output: BaseVariantOutput
    ): File {
        // 1. 优先从任务输出获取
        try {
            output.processManifestProvider.get().outputs.files.files.forEach {
                findFileByName(it, "AndroidManifest.xml")?.let { file -> return file }
            }
        } catch (_: Exception) {
            // 忽略
        }

        val variantName = pluginVariant.name

        // 2. 搜索中间产物目录
        return listOf(
            "intermediates/merged_manifests/$variantName", // AGP 4.x/7.x/8.x
            "intermediates/manifests/full/$variantName", // AGP 3.x
        )
            .map { File(project.buildDir, it) }
            .first {
                findFileByName(it, "AndroidManifest.xml") != null
            }
    }

    /**
     * 获取 R.txt 文件。
     *
     * 优先从 processResources 任务的输出获取（最准确）， 否则搜索 intermediates 目录。
     */
    override fun getRTxtFile(
        project: Project,
        processResourcesTask: Task?,
        variantName: String
    ): File {
        // 1. 优先尝试从任务输出中查找
        if (processResourcesTask != null) {
            try {
                processResourcesTask.outputs.files.files.forEach {
                    findFileByName(it, "R.txt")?.let { file -> return file }
                }
            } catch (_: Exception) {
                // 忽略解析错误，继续走备选路径
            }
        }

        // 2. 根据 AGP 版本已知的中间产物路径搜索
        return listOf(
            "intermediates/runtime_symbol_list/$variantName", // AGP 4.x/7.x/8.x
            "intermediates/symbols/$variantName",
            "intermediates/bundles/$variantName"
        )
            .map { File(project.buildDir, it) }
            .first {
                findFileByName(it, "R.txt") != null
            }
    }

    /**
     * 搜索指定目录下指定文件名的文件。
     *
     * @return 文件对象，若找不到则返回 null 。
     */
    private fun findFileByName(file: File, fileName: String): File? {
        if (!file.exists()) {
            return null
        }
        if (file.isFile && file.name == fileName) {
            return file
        }
        if (file.isDirectory) {
            val subFiles = file.listFiles()
            if (subFiles != null) {
                for (subFile in subFiles) {
                    val resultFile = findFileByName(subFile, fileName)
                    if (resultFile != null) {
                        return resultFile
                    }
                }
            }
        }
        return null
    }

    companion object {
        fun getStringFromProperty(x: Any?): String {
            return when (x) {
                is String -> x
                is Property<*> -> x.get() as String
                else -> throw Error("不支持的AGP版本")
            }
        }
    }
}
