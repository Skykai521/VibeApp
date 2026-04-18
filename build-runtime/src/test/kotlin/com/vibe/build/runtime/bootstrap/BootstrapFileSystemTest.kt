package com.vibe.build.runtime.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootstrapFileSystemTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `usrRoot is filesDir usr`() {
        val fs = BootstrapFileSystem(filesDir = temp.root)
        assertEquals(File(temp.root, "usr"), fs.usrRoot)
    }

    @Test
    fun `componentInstallDir lives under usr opt`() {
        val fs = BootstrapFileSystem(filesDir = temp.root)
        val expected = File(temp.root, "usr/opt/jdk-17.0.13")
        assertEquals(expected, fs.componentInstallDir("jdk-17.0.13"))
    }

    @Test
    fun `tempDownloadFile returns path under usr tmp`() {
        val fs = BootstrapFileSystem(filesDir = temp.root)
        val tmp = fs.tempDownloadFile("jdk-17.0.13-arm64-v8a.tar.zst")
        assertTrue(tmp.absolutePath.contains("/usr/tmp/"))
        assertEquals("jdk-17.0.13-arm64-v8a.tar.zst.part", tmp.name)
    }

    @Test
    fun `ensureDirectories creates usr opt and usr tmp idempotently`() {
        val fs = BootstrapFileSystem(filesDir = temp.root)
        fs.ensureDirectories()
        fs.ensureDirectories()
        assertTrue(File(temp.root, "usr/opt").isDirectory)
        assertTrue(File(temp.root, "usr/tmp").isDirectory)
    }

    @Test
    fun `atomicInstall moves staged dir into opt with final name`() {
        val fs = BootstrapFileSystem(filesDir = temp.root)
        fs.ensureDirectories()

        val staged = File(temp.root, "usr/tmp/staged-jdk-abc123")
        File(staged, "bin").mkdirs()
        File(staged, "bin/java").writeText("#!/bin/sh\necho jdk\n")

        fs.atomicInstall(staged, componentId = "jdk-17.0.13")

        val finalDir = fs.componentInstallDir("jdk-17.0.13")
        assertTrue(finalDir.isDirectory)
        assertTrue(File(finalDir, "bin/java").isFile)
        assertFalse(staged.exists())
    }
}
