package com.vibe.app.feature.projecticon.iconlibrary

import org.junit.Assert.assertTrue
import org.junit.Test

class IconVectorDrawableBuilderTest {

    private val plusIcon = IconLibrary.IconRecord(
        id = "plus",
        body = """<path d="M5 12h14M12 5v14"/>""",
        width = 24,
        height = 24,
        categories = listOf("Math"),
    )

    @Test
    fun `foreground wraps paths in 108 canvas with safe-zone group`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.SOLID,
                backgroundColor1 = "#2196F3",
            ),
        )
        val fg = result.foregroundXml
        assertTrue(fg.contains("""android:viewportWidth="108""""))
        assertTrue(fg.contains("""android:translateX="21"""))
        assertTrue(fg.contains("""android:scaleX="2.75"""))
        assertTrue(fg.contains("""android:strokeColor="#FFFFFF""""))
        assertTrue(fg.contains("""android:pathData="M5 12h14M12 5v14""""))
    }

    @Test
    fun `solid background uses fillColor path`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.SOLID,
                backgroundColor1 = "#2196F3",
            ),
        )
        val bg = result.backgroundXml
        assertTrue(bg.contains("""android:fillColor="#2196F3""""))
        assertTrue(bg.contains("""M0,0h108v108h-108Z"""))
    }

    @Test
    fun `linear gradient background emits aapt gradient`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.LINEAR_GRADIENT,
                backgroundColor1 = "#ff2196f3",
                backgroundColor2 = "#ff0d47a1",
            ),
        )
        val bg = result.backgroundXml
        assertTrue(bg.contains("""xmlns:aapt="http://schemas.android.com/aapt""""))
        assertTrue(bg.contains("""android:type="linear""""))
        assertTrue(bg.contains("""android:color="#FF2196F3""""))
        assertTrue(bg.contains("""android:color="#FF0D47A1""""))
    }

    @Test
    fun `radial gradient background uses radial type`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "#FFFFFF",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.RADIAL_GRADIENT,
                backgroundColor1 = "#FFFFFFFF",
                backgroundColor2 = "#FF000000",
            ),
        )
        assertTrue(result.backgroundXml.contains("""android:type="radial""""))
        assertTrue(result.backgroundXml.contains("""android:gradientRadius="76""""))
    }

    @Test
    fun `color normalization uppercases and prefixes hash`() {
        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = plusIcon,
                foregroundColor = "ff00aa",
                backgroundStyle = IconVectorDrawableBuilder.BackgroundStyle.SOLID,
                backgroundColor1 = "112233",
            ),
        )
        assertTrue(result.foregroundXml.contains("""android:strokeColor="#FF00AA""""))
        assertTrue(result.backgroundXml.contains("""android:fillColor="#112233""""))
    }
}
