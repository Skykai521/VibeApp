package com.vibe.build.gradle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pure-on-device unit-style coverage for [GradleProjectInitializer]:
 * extract the bundled KotlinComposeApp template from assets, render
 * variables, and assert the resulting tree is what a Gradle build would
 * see. Does NOT run Gradle — the e2e build is covered by
 * [TemplateBuildInstrumentedTest].
 *
 * Faster + more deterministic than full assemble; primarily checks
 * placeholder substitution (esp. `{{PACKAGE_PATH}}` directory rename).
 */
@RunWith(AndroidJUnit4::class)
class GradleProjectInitializerInstrumentedTest {

    @Test
    fun renders_KotlinComposeApp_template_with_variable_substitution() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scratch = File(ctx.cacheDir, "init-test-${System.nanoTime()}")
        scratch.mkdirs()

        try {
            val initializer = GradleProjectInitializer(ctx, ProjectStager())
            val dest = File(scratch, "out")
            val result = initializer.initialize(
                GradleProjectInitializer.Input(
                    templateName = "KotlinComposeApp",
                    projectName = "Counter",
                    packageName = "com.vibe.counter",
                    sdkDir = File("/dev/null/sdk"),
                    gradleUserHome = File("/dev/null/.gradle"),
                    destinationDir = dest,
                ),
            )

            assertEquals(dest.absolutePath, result.absolutePath)

            // Top-level files extracted + rendered.
            assertTrue("settings.gradle.kts missing", File(dest, "settings.gradle.kts").isFile)
            assertTrue("build.gradle.kts missing", File(dest, "build.gradle.kts").isFile)
            assertTrue("gradle.properties missing", File(dest, "gradle.properties").isFile)
            assertTrue("local.properties missing", File(dest, "local.properties").isFile)
            assertTrue(
                "gradle/libs.versions.toml missing",
                File(dest, "gradle/libs.versions.toml").isFile,
            )

            // .tmpl content substitution.
            val settings = File(dest, "settings.gradle.kts").readText()
            assertTrue(
                "rootProject.name not substituted: $settings",
                settings.contains("""rootProject.name = "Counter""""),
            )
            val appBuild = File(dest, "app/build.gradle.kts").readText()
            assertTrue(
                "namespace not substituted: $appBuild",
                appBuild.contains("""namespace = "com.vibe.counter""""),
            )
            val locals = File(dest, "local.properties").readText()
            assertTrue("sdk.dir not rendered: $locals", locals.contains("sdk.dir=/dev/null/sdk"))

            // Path substitution: {{PACKAGE_PATH}} → com/vibe/counter.
            val activity = File(
                dest,
                "app/src/main/kotlin/com/vibe/counter/MainActivity.kt",
            )
            assertTrue("MainActivity.kt missing at $activity", activity.isFile)
            assertTrue(
                "{{PACKAGE_PATH}} dir must not survive in dest",
                !File(dest, "app/src/main/kotlin/{{PACKAGE_PATH}}").exists(),
            )

            // MainActivity.kt content also gets {{PACKAGE_NAME}} + {{PROJECT_NAME}}.
            val activityText = activity.readText()
            assertTrue(
                "package line not substituted: ${activityText.lines().first()}",
                activityText.startsWith("package com.vibe.counter"),
            )
            assertTrue(
                "PROJECT_NAME not substituted in MainActivity",
                activityText.contains("\"Counter\""),
            )
        } finally {
            scratch.deleteRecursively()
        }
    }
}
