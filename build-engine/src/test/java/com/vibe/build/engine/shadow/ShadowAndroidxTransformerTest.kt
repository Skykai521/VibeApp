package com.vibe.build.engine.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class ShadowAndroidxTransformerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createTestJar(file: File) {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "androidx/appcompat/app/AppCompatActivity",
            null,
            "android/app/Activity",
            null,
        )
        cw.visitEnd()
        val classBytes = cw.toByteArray()

        JarOutputStream(file.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("androidx/appcompat/app/AppCompatActivity.class"))
            jar.write(classBytes)
            jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
    }

    @Test
    fun `transforms JAR and remaps Activity to ShadowActivity`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val result = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)

        assertTrue(result.exists())
        JarFile(result).use { jar ->
            val entry = jar.getJarEntry("androidx/appcompat/app/AppCompatActivity.class")
            val bytes = jar.getInputStream(entry).readBytes()
            val reader = ClassReader(bytes)
            var superName: String? = null
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, access: Int, name: String?,
                    signature: String?, superNameParam: String?, interfaces: Array<out String>?,
                ) {
                    superName = superNameParam
                }
            }, 0)
            assertEquals(
                "com/tencent/shadow/core/runtime/ShadowActivity",
                superName,
            )
        }
    }

    @Test
    fun `cache hit returns existing JAR without re-transform`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val first = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        val firstModified = first.lastModified()

        val second = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        assertEquals(firstModified, second.lastModified())
    }

    @Test
    fun `cache invalidated when input JAR changes`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val first = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        assertTrue(first.exists())

        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "androidx/appcompat/app/AppCompatActivity",
            null,
            "android/app/Activity",
            arrayOf("java/io/Serializable"),
        )
        cw.visitEnd()
        JarOutputStream(inputJar.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("androidx/appcompat/app/AppCompatActivity.class"))
            jar.write(cw.toByteArray())
            jar.closeEntry()
        }

        val second = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)
        assertTrue(second.exists())
        JarFile(second).use { jar ->
            val entry = jar.getJarEntry("androidx/appcompat/app/AppCompatActivity.class")
            val bytes = jar.getInputStream(entry).readBytes()
            val reader = ClassReader(bytes)
            var interfaces: Array<out String>? = null
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, access: Int, name: String?,
                    signature: String?, superName: String?, interfacesParam: Array<out String>?,
                ) {
                    interfaces = interfacesParam
                }
            }, 0)
            assertTrue(interfaces?.contains("java/io/Serializable") == true)
        }
    }

    @Test
    fun `non-class entries are preserved`() {
        val inputJar = File(tempDir.root, "androidx-classes.jar")
        createTestJar(inputJar)
        val cacheDir = tempDir.newFolder("cache")

        val result = ShadowAndroidxTransformer.getOrTransform(inputJar, cacheDir)

        JarFile(result).use { jar ->
            val manifest = jar.getJarEntry("META-INF/MANIFEST.MF")
            val content = jar.getInputStream(manifest).readBytes().decodeToString()
            assertTrue(content.contains("Manifest-Version: 1.0"))
        }
    }
}
