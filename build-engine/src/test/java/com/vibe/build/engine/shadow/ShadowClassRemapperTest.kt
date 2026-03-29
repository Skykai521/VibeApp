package com.vibe.build.engine.shadow

import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ShadowClassRemapperTest {

    @Test
    fun `remaps Activity superclass to ShadowActivity`() {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MainActivity",
            null,
            "android/app/Activity",
            null,
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        val verifyReader = ClassReader(transformed)
        var superName: String? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
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

    @Test
    fun `does not remap Application`() {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MyApp",
            null,
            "android/app/Application",
            null,
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        val verifyReader = ClassReader(transformed)
        var superName: String? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superNameParam: String?, interfaces: Array<out String>?,
            ) {
                superName = superNameParam
            }
        }, 0)

        // Application must NOT be remapped — it would break method signatures
        assertEquals("android/app/Application", superName)
    }

    @Test
    fun `does not remap unrelated classes`() {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/example/MyView",
            null,
            "android/view/View",
            null,
        )
        cw.visitEnd()
        val original = cw.toByteArray()

        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        val transformed = writer.toByteArray()

        val verifyReader = ClassReader(transformed)
        var superName: String? = null
        verifyReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superNameParam: String?, interfaces: Array<out String>?,
            ) {
                superName = superNameParam
            }
        }, 0)

        assertEquals("android/view/View", superName)
    }
}
