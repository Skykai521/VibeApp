package com.vibe.app.feature.projecticon.iconlibrary

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Pure SVG-body → Android vector-drawable path converter.
 *
 * Input: the inner SVG body of an Iconify icon (e.g. `<path .../><circle .../>`).
 * Output: a list of [PathEntry] each ready to be rendered as
 * `<path android:pathData="...">`.
 *
 * Handles: path, circle, rect (with rx/ry), line, polyline, polygon.
 * `<g transform>` is not supported — Lucide doesn't use it.
 */
object IconifySvgConverter {

    data class PathEntry(
        val pathData: String,
        val strokeWidth: Float,
        val strokeLineCap: String,
        val strokeLineJoin: String,
    )

    private val factory: XmlPullParserFactory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = false
    }

    fun convert(body: String): List<PathEntry> {
        val wrapped = "<svgroot>$body</svgroot>"
        val parser = factory.newPullParser()
        parser.setInput(StringReader(wrapped))

        val out = ArrayList<PathEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val data = when (parser.name) {
                    "path" -> parser.getAttributeValue(null, "d")
                    "circle" -> circleToPath(parser)
                    "rect" -> rectToPath(parser)
                    "line" -> lineToPath(parser)
                    "polyline" -> polylineToPath(parser, close = false)
                    "polygon" -> polylineToPath(parser, close = true)
                    else -> null
                }
                if (!data.isNullOrBlank()) {
                    out += PathEntry(
                        pathData = data.trim(),
                        strokeWidth = parser.getAttributeValue(null, "stroke-width")
                            ?.toFloatOrNull() ?: 2f,
                        strokeLineCap = parser.getAttributeValue(null, "stroke-linecap")
                            ?: "round",
                        strokeLineJoin = parser.getAttributeValue(null, "stroke-linejoin")
                            ?: "round",
                    )
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun XmlPullParser.numAttr(name: String, default: Float = 0f): Float =
        getAttributeValue(null, name)?.toFloatOrNull() ?: default

    private fun circleToPath(p: XmlPullParser): String {
        val cx = p.numAttr("cx")
        val cy = p.numAttr("cy")
        val r = p.numAttr("r")
        if (r <= 0f) return ""
        return "M${cx - r},${cy}a${r},${r} 0 1,0 ${r * 2},0a${r},${r} 0 1,0 -${r * 2},0Z"
    }

    private fun rectToPath(p: XmlPullParser): String {
        val x = p.numAttr("x")
        val y = p.numAttr("y")
        val w = p.numAttr("width")
        val h = p.numAttr("height")
        if (w <= 0f || h <= 0f) return ""
        val rxAttr = p.getAttributeValue(null, "rx")?.toFloatOrNull()
        val ryAttr = p.getAttributeValue(null, "ry")?.toFloatOrNull()
        val rx = (rxAttr ?: ryAttr ?: 0f).coerceAtMost(w / 2f).coerceAtLeast(0f)
        val ry = (ryAttr ?: rxAttr ?: 0f).coerceAtMost(h / 2f).coerceAtLeast(0f)
        if (rx == 0f && ry == 0f) {
            return "M$x,${y}h${w}v${h}h-${w}Z"
        }
        return buildString {
            append("M${x + rx},$y")
            append("h${w - 2 * rx}")
            append("a$rx,$ry 0 0 1 $rx,$ry")
            append("v${h - 2 * ry}")
            append("a$rx,$ry 0 0 1 -$rx,$ry")
            append("h-${w - 2 * rx}")
            append("a$rx,$ry 0 0 1 -$rx,-$ry")
            append("v-${h - 2 * ry}")
            append("a$rx,$ry 0 0 1 $rx,-$ry")
            append("Z")
        }
    }

    private fun lineToPath(p: XmlPullParser): String {
        val x1 = p.numAttr("x1"); val y1 = p.numAttr("y1")
        val x2 = p.numAttr("x2"); val y2 = p.numAttr("y2")
        return "M$x1,${y1}L$x2,$y2"
    }

    private fun polylineToPath(p: XmlPullParser, close: Boolean): String {
        val points = p.getAttributeValue(null, "points")?.trim().orEmpty()
        if (points.isEmpty()) return ""
        val nums = points.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (nums.size < 4 || nums.size % 2 != 0) return ""
        return buildString {
            append("M${nums[0]},${nums[1]}")
            var i = 2
            while (i < nums.size) {
                append("L${nums[i]},${nums[i + 1]}")
                i += 2
            }
            if (close) append("Z")
        }
    }
}
