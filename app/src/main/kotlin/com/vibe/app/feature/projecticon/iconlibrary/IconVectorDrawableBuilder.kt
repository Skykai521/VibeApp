package com.vibe.app.feature.projecticon.iconlibrary

/**
 * Builds background + foreground adaptive-icon vector-drawable XML
 * from an [IconLibrary.IconRecord] and user-supplied colors.
 *
 * All output targets viewportWidth/Height=108 to match Android adaptive icons.
 * The icon is centered inside a 66×66 safe zone via a `<group>` with
 * translate=(108-66)/2 and scale=66/iconWidth.
 */
object IconVectorDrawableBuilder {

    enum class BackgroundStyle { SOLID, LINEAR_GRADIENT, RADIAL_GRADIENT }

    data class Request(
        val icon: IconLibrary.IconRecord,
        val foregroundColor: String,
        val backgroundStyle: BackgroundStyle,
        val backgroundColor1: String,
        val backgroundColor2: String? = null,
    )

    data class Result(
        val backgroundXml: String,
        val foregroundXml: String,
    )

    private const val CANVAS = 108f
    private const val SAFE_ZONE = 66f

    fun build(request: Request): Result {
        val paths = IconifySvgConverter.convert(request.icon.body)
        val foreground = buildForeground(paths, request.icon, request.foregroundColor)
        val background = buildBackground(request)
        return Result(backgroundXml = background, foregroundXml = foreground)
    }

    private fun buildForeground(
        paths: List<IconifySvgConverter.PathEntry>,
        icon: IconLibrary.IconRecord,
        colorHex: String,
    ): String {
        val color = normalizeColor(colorHex)
        val scale = SAFE_ZONE / icon.width.toFloat()
        val translate = (CANVAS - SAFE_ZONE) / 2f
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        sb.append("""<vector xmlns:android="http://schemas.android.com/apk/res/android" """)
        sb.append("""android:width="108dp" android:height="108dp" """)
        sb.append("""android:viewportWidth="108" android:viewportHeight="108">""").append('\n')
        sb.append("""    <group android:translateX="$translate" android:translateY="$translate" """)
        sb.append("""android:scaleX="$scale" android:scaleY="$scale">""").append('\n')
        for (entry in paths) {
            sb.append("""        <path android:pathData="${escapeXml(entry.pathData)}" """)
            sb.append("""android:strokeColor="$color" """)
            sb.append("""android:strokeWidth="${entry.strokeWidth}" """)
            sb.append("""android:strokeLineCap="${entry.strokeLineCap}" """)
            sb.append("""android:strokeLineJoin="${entry.strokeLineJoin}"/>""").append('\n')
        }
        sb.append("    </group>\n")
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun buildBackground(request: Request): String {
        val rectPath = "M0,0h108v108h-108Z"
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        sb.append("""<vector xmlns:android="http://schemas.android.com/apk/res/android" """)
        sb.append("""xmlns:aapt="http://schemas.android.com/aapt" """)
        sb.append("""android:width="108dp" android:height="108dp" """)
        sb.append("""android:viewportWidth="108" android:viewportHeight="108">""").append('\n')

        when (request.backgroundStyle) {
            BackgroundStyle.SOLID -> {
                sb.append("""    <path android:pathData="$rectPath" """)
                sb.append("""android:fillColor="${normalizeColor(request.backgroundColor1)}"/>""")
                sb.append('\n')
            }
            BackgroundStyle.LINEAR_GRADIENT -> {
                val c1 = normalizeColor(request.backgroundColor1)
                val c2 = normalizeColor(request.backgroundColor2 ?: request.backgroundColor1)
                sb.append("""    <path android:pathData="$rectPath">""").append('\n')
                sb.append("""        <aapt:attr name="android:fillColor">""").append('\n')
                sb.append("""            <gradient android:type="linear" """)
                sb.append("""android:startX="0" android:startY="0" """)
                sb.append("""android:endX="108" android:endY="108">""").append('\n')
                sb.append("""                <item android:offset="0.0" android:color="$c1"/>""").append('\n')
                sb.append("""                <item android:offset="1.0" android:color="$c2"/>""").append('\n')
                sb.append("""            </gradient>""").append('\n')
                sb.append("""        </aapt:attr>""").append('\n')
                sb.append("""    </path>""").append('\n')
            }
            BackgroundStyle.RADIAL_GRADIENT -> {
                val c1 = normalizeColor(request.backgroundColor1)
                val c2 = normalizeColor(request.backgroundColor2 ?: request.backgroundColor1)
                sb.append("""    <path android:pathData="$rectPath">""").append('\n')
                sb.append("""        <aapt:attr name="android:fillColor">""").append('\n')
                sb.append("""            <gradient android:type="radial" """)
                sb.append("""android:centerX="54" android:centerY="54" """)
                sb.append("""android:gradientRadius="76">""").append('\n')
                sb.append("""                <item android:offset="0.0" android:color="$c1"/>""").append('\n')
                sb.append("""                <item android:offset="1.0" android:color="$c2"/>""").append('\n')
                sb.append("""            </gradient>""").append('\n')
                sb.append("""        </aapt:attr>""").append('\n')
                sb.append("""    </path>""").append('\n')
            }
        }
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun normalizeColor(input: String): String {
        val trimmed = input.trim()
        val hex = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return hex.uppercase()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
