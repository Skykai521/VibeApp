package com.vibe.app.feature.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogWatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `extractLatestCrash returns last crash block`() {
        val f = tmp.newFile("crash.log")
        f.writeText("--- CRASH 01-01 ---\nold\n--- CRASH 01-02 ---\njava.lang.NullPointerException\n  at Main.java:5\n")
        val result = CrashLogWatcher.extractLatestCrash(f)!!
        assertEquals("--- CRASH 01-02 ---\njava.lang.NullPointerException\n  at Main.java:5", result)
    }

    @Test
    fun `extractLatestCrash returns null for file without crash marker`() {
        val f = tmp.newFile("crash.log")
        f.writeText("just noise\n")
        assertNull(CrashLogWatcher.extractLatestCrash(f))
    }
}
