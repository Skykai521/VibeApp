package com.vibe.build.runtime.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiTest {

    @Test
    fun `pickPreferredAbi returns ARM64 when arm64-v8a listed first`() {
        val supported = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals(Abi.ARM64, Abi.pickPreferred(supported))
    }

    @Test
    fun `pickPreferredAbi returns ARM32 when only 32-bit ARM available`() {
        val supported = arrayOf("armeabi-v7a", "armeabi")
        assertEquals(Abi.ARM32, Abi.pickPreferred(supported))
    }

    @Test
    fun `pickPreferredAbi returns X86_64 on emulator`() {
        val supported = arrayOf("x86_64", "x86")
        assertEquals(Abi.X86_64, Abi.pickPreferred(supported))
    }

    @Test
    fun `pickPreferredAbi returns null for unsupported ABI set`() {
        val supported = arrayOf("mips", "x86")
        assertNull(Abi.pickPreferred(supported))
    }

    @Test
    fun `abiId stable across enum`() {
        assertEquals("arm64-v8a", Abi.ARM64.abiId)
        assertEquals("armeabi-v7a", Abi.ARM32.abiId)
        assertEquals("x86_64", Abi.X86_64.abiId)
    }
}
