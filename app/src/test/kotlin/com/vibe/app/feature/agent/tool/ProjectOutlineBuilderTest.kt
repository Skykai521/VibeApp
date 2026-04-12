package com.vibe.app.feature.agent.tool

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProjectOutlineBuilderTest {

    private lateinit var root: File
    private val builder = ProjectOutlineBuilder()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("outline-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String, content: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `empty workspace produces empty outline`() {
        val outline = builder.build(root)
        assertEquals("", outline.trim())
    }

    @Test
    fun `java file extracts class name and public methods`() {
        write(
            "src/main/java/com/demo/MainActivity.java",
            """
            package com.demo;
            public class MainActivity extends AppCompatActivity {
                public void onCreate(Bundle savedInstanceState) {}
                protected void onResume() {}
                private void helperMethod() {}
                public void onSubmitClick(View v) {}
            }
            """.trimIndent(),
        )
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("src/main/java/com/demo/MainActivity.java"))
        assertTrue(outline, outline.contains("class MainActivity"))
        assertTrue(outline, outline.contains("onCreate"))
        assertTrue(outline, outline.contains("onResume"))
        assertTrue(outline, outline.contains("onSubmitClick"))
        assertFalse("private method should be excluded", outline.contains("helperMethod"))
    }

    @Test
    fun `java method truncation kicks in after 20 methods`() {
        val methods = (1..30).joinToString("\n") { "    public void method$it() {}" }
        write(
            "src/main/java/com/demo/Big.java",
            """
            package com.demo;
            public class Big {
            $methods
            }
            """.trimIndent(),
        )
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("method1"))
        assertTrue(outline, outline.contains("method20"))
        assertFalse("method21 should be truncated", outline.contains("method21"))
        assertTrue("truncation marker present", outline.contains("…"))
    }

    @Test
    fun `layout xml extracts view ids`() {
        write(
            "res/layout/activity_main.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <Button android:id="@+id/btn_submit"/>
                <TextView android:id="@+id/tv_result"/>
                <EditText android:id="@+id/et_input"/>
            </LinearLayout>
            """.trimIndent(),
        )
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("res/layout/activity_main.xml"))
        assertTrue(outline, outline.contains("btn_submit"))
        assertTrue(outline, outline.contains("tv_result"))
        assertTrue(outline, outline.contains("et_input"))
    }

    @Test
    fun `layout id truncation kicks in after 30 ids`() {
        val buttons = (1..40).joinToString("\n") {
            """<Button android:id="@+id/btn_$it"/>"""
        }
        write(
            "res/layout/big.xml",
            """
            <LinearLayout>
            $buttons
            </LinearLayout>
            """.trimIndent(),
        )
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("btn_1"))
        assertTrue(outline, outline.contains("btn_30"))
        assertFalse(outline.contains("btn_31"))
        assertTrue(outline.contains("…"))
    }

    @Test
    fun `values xml extracts name attributes`() {
        write(
            "res/values/strings.xml",
            """
            <resources>
                <string name="app_name">Demo</string>
                <string name="btn_submit_label">Submit</string>
                <string name="hint_input">Type…</string>
            </resources>
            """.trimIndent(),
        )
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("res/values/strings.xml"))
        assertTrue(outline, outline.contains("app_name"))
        assertTrue(outline, outline.contains("btn_submit_label"))
        assertTrue(outline, outline.contains("hint_input"))
    }

    @Test
    fun `manifest extracts package and activities`() {
        write(
            "src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.demo">
                <application>
                    <activity android:name=".MainActivity"/>
                    <activity android:name="com.example.demo.SettingsActivity"/>
                </application>
            </manifest>
            """.trimIndent(),
        )
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("AndroidManifest.xml"))
        assertTrue(outline, outline.contains("com.example.demo"))
        assertTrue(outline, outline.contains("MainActivity"))
        assertTrue(outline, outline.contains("SettingsActivity"))
    }

    @Test
    fun `unknown files are listed as path only without symbols`() {
        write("README.txt", "hello")
        val outline = builder.build(root)
        assertTrue(outline, outline.contains("README.txt"))
        // No indented symbol lines under it
        val lines = outline.lines()
        val idx = lines.indexOfFirst { it.contains("README.txt") }
        assertTrue(idx >= 0)
        val next = lines.getOrNull(idx + 1) ?: ""
        assertFalse("should not have indented children", next.startsWith("  "))
    }

    @Test
    fun `build directory is excluded`() {
        write("build/intermediates/Foo.java", "public class Foo { public void bar() {} }")
        write("src/main/java/Keep.java", "public class Keep { public void ok() {} }")
        val outline = builder.build(root)
        assertFalse(outline, outline.contains("Foo"))
        assertTrue(outline, outline.contains("Keep"))
    }

    @Test
    fun `total outline size is capped around 8KB`() {
        // Create many files to exceed the cap
        repeat(500) { i ->
            write(
                "src/main/java/com/demo/Cls$i.java",
                "public class Cls$i { public void m1() {} public void m2() {} }",
            )
        }
        val outline = builder.build(root)
        assertTrue("outline should be truncated", outline.length <= 8 * 1024 + 200)
        assertTrue("truncation marker", outline.contains("outline truncated"))
    }
}
