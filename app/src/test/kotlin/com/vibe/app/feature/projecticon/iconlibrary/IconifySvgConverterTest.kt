package com.vibe.app.feature.projecticon.iconlibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconifySvgConverterTest {

    @Test
    fun `path element copies pathData verbatim`() {
        val out = IconifySvgConverter.convert(
            """<path fill="none" stroke="currentColor" stroke-width="2" d="M1 2L3 4"/>""",
        )
        assertEquals(1, out.size)
        assertEquals("M1 2L3 4", out[0].pathData)
        assertEquals(2f, out[0].strokeWidth, 0f)
    }

    @Test
    fun `default stroke width is 2 and linecap round`() {
        val out = IconifySvgConverter.convert("""<path d="M0 0"/>""")
        assertEquals(2f, out[0].strokeWidth, 0f)
        assertEquals("round", out[0].strokeLineCap)
        assertEquals("round", out[0].strokeLineJoin)
    }

    @Test
    fun `circle becomes two half-arc path`() {
        val out = IconifySvgConverter.convert("""<circle cx="12" cy="12" r="10"/>""")
        assertEquals(1, out.size)
        val d = out[0].pathData
        assertTrue(d.startsWith("M2.0,12"))
        assertTrue(d.contains("a10.0,10.0 0 1,0 20.0,0"))
        assertTrue(d.endsWith("Z"))
    }

    @Test
    fun `rect without radii becomes M h v h Z`() {
        val out = IconifySvgConverter.convert("""<rect x="1" y="2" width="10" height="20"/>""")
        assertEquals("M1.0,2.0h10.0v20.0h-10.0Z", out[0].pathData)
    }

    @Test
    fun `rect with rx uses four arcs`() {
        val out = IconifySvgConverter.convert(
            """<rect x="0" y="0" width="10" height="10" rx="2"/>""",
        )
        val d = out[0].pathData
        assertTrue(d.startsWith("M2.0,0"))
        assertTrue(d.contains("a2.0,2.0 0 0 1 2.0,2.0"))
        assertTrue(d.endsWith("Z"))
    }

    @Test
    fun `line becomes move-then-line`() {
        val out = IconifySvgConverter.convert("""<line x1="1" y1="2" x2="3" y2="4"/>""")
        assertEquals("M1.0,2.0L3.0,4.0", out[0].pathData)
    }

    @Test
    fun `polyline is open sequence`() {
        val out = IconifySvgConverter.convert("""<polyline points="1,2 3,4 5,6"/>""")
        assertEquals("M1,2L3,4L5,6", out[0].pathData)
    }

    @Test
    fun `polygon closes with Z`() {
        val out = IconifySvgConverter.convert("""<polygon points="0,0 10,0 5,10"/>""")
        assertEquals("M0,0L10,0L5,10Z", out[0].pathData)
    }

    @Test
    fun `multiple elements preserved in order`() {
        val out = IconifySvgConverter.convert(
            """<path d="M1 1"/><circle cx="5" cy="5" r="2"/>""",
        )
        assertEquals(2, out.size)
        assertEquals("M1 1", out[0].pathData)
        assertTrue(out[1].pathData.startsWith("M3.0,5"))
    }
}
