package com.vibe.app.feature.project.memo

import com.vibe.app.feature.project.VibeProjectDirs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IntentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDirs(): VibeProjectDirs {
        val projectRoot = tmp.newFolder("projects", "p1")
        val workspace = File(projectRoot, "app").apply { mkdirs() }
        return VibeProjectDirs.fromWorkspaceRoot(workspace).also { it.ensureCreated() }
    }

    private val store = DefaultIntentStore()

    @Test
    fun `exists is false when no intent file`() = runTest {
        assertFalse(store.exists(newDirs()))
    }

    @Test
    fun `load returns null when no intent file`() = runTest {
        assertNull(store.load(newDirs()))
    }

    @Test
    fun `save then load round-trips through the codec`() = runTest {
        val dirs = newDirs()
        val intent = Intent(
            purpose = "Test app",
            keyDecisions = listOf("Use X"),
            knownLimits = listOf("No Y"),
        )
        store.save(dirs, intent, appName = "TestApp")
        assertTrue(store.exists(dirs))
        assertEquals(intent, store.load(dirs))
    }

    @Test
    fun `saveRawMarkdown writes exact text`() = runTest {
        val dirs = newDirs()
        val raw = "<!-- edited -->\n# X\n\n**Purpose**: hello\n"
        store.saveRawMarkdown(dirs, raw)
        assertEquals(raw, store.loadRawMarkdown(dirs))
    }

    @Test
    fun `loadRawMarkdown returns null when file missing`() = runTest {
        assertNull(store.loadRawMarkdown(newDirs()))
    }
}
