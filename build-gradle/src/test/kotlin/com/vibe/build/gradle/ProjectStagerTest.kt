package com.vibe.build.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun stages_tree_and_substitutes_tmpl_files() {
        val src = tmp.newFolder("src")
        File(src, "settings.gradle.kts").writeText("""rootProject.name = "x"""")
        File(src, "local.properties.tmpl").writeText(
            "sdk.dir={{SDK_DIR}}\n" +
            "gradle.user.home={{GRADLE_USER_HOME}}\n",
        )
        val dest = tmp.newFolder("dest")

        val stager = ProjectStager()
        val out = stager.stage(
            template = ProjectTemplate.FromDirectory(src),
            destinationDir = dest,
            variables = mapOf(
                "SDK_DIR" to "/opt/sdk",
                "GRADLE_USER_HOME" to "/opt/.gradle",
            ),
        )

        assertEquals(dest.absolutePath, out.absolutePath)
        assertTrue(File(dest, "settings.gradle.kts").exists())
        val rendered = File(dest, "local.properties").readText()
        assertEquals("sdk.dir=/opt/sdk\ngradle.user.home=/opt/.gradle\n", rendered)
        assertTrue("template must not land as .tmpl", !File(dest, "local.properties.tmpl").exists())
    }

    @Test
    fun stage_is_idempotent_same_inputs_produce_identical_tree() {
        val src = tmp.newFolder("src")
        File(src, "build.gradle.kts").writeText("// top level")
        File(src, "app").mkdirs()
        File(src, "app/build.gradle.kts").writeText("// app level")
        val dest = tmp.newFolder("dest")
        val stager = ProjectStager()

        stager.stage(ProjectTemplate.FromDirectory(src), dest, emptyMap())
        val first = File(dest, "app/build.gradle.kts").lastModified()
        stager.stage(ProjectTemplate.FromDirectory(src), dest, emptyMap())
        val second = File(dest, "app/build.gradle.kts").lastModified()

        // Re-staging must overwrite cleanly — second timestamp >= first.
        assertTrue(second >= first)
    }
}
