package com.vibe.app.feature.projecticon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.PathParser
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

object ProjectIconRenderer {

    fun iconSignature(workspacePath: String): Long {
        val backgroundFile = File(workspacePath, ICON_BACKGROUND_PATH)
        val foregroundFile = File(workspacePath, ICON_FOREGROUND_PATH)
        return backgroundFile.lastModified() + foregroundFile.lastModified()
    }

    suspend fun loadProjectIcon(
        workspacePath: String,
        sizePx: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val backgroundFile = File(workspacePath, ICON_BACKGROUND_PATH)
        val foregroundFile = File(workspacePath, ICON_FOREGROUND_PATH)
        if (!backgroundFile.exists() && !foregroundFile.exists()) return@withContext null

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        renderVectorXml(canvas, backgroundFile, sizePx)
        renderVectorXml(canvas, foregroundFile, sizePx)

        bitmap.asImageBitmap()
    }

    private fun renderVectorXml(canvas: Canvas, file: File, sizePx: Int) {
        if (!file.exists()) return
        runCatching {
            val vector = parseVectorXml(file)
            drawVector(canvas, vector, sizePx)
        }.onFailure { error ->
            Log.w(TAG, "Failed to render icon ${file.absolutePath}", error)
        }
    }

    private fun parseVectorXml(file: File): VectorData {
        val parser = Xml.newPullParser()
        var viewportWidth = 108f
        var viewportHeight = 108f
        val rootGroup = GroupData()
        val groupStack = ArrayDeque<GroupData>()
        groupStack.addLast(rootGroup)

        file.inputStream().buffered().reader(Charsets.UTF_8).use { reader ->
            parser.setInput(reader)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "vector" -> {
                                viewportWidth = parser.getAttr("viewportWidth")?.toFloatOrNull() ?: 108f
                                viewportHeight = parser.getAttr("viewportHeight")?.toFloatOrNull() ?: 108f
                            }
                            "group" -> {
                                val group = GroupData(
                                    translateX = parser.getAttr("translateX")?.toFloatOrNull() ?: 0f,
                                    translateY = parser.getAttr("translateY")?.toFloatOrNull() ?: 0f,
                                    scaleX = parser.getAttr("scaleX")?.toFloatOrNull() ?: 1f,
                                    scaleY = parser.getAttr("scaleY")?.toFloatOrNull() ?: 1f,
                                    rotation = parser.getAttr("rotation")?.toFloatOrNull() ?: 0f,
                                    pivotX = parser.getAttr("pivotX")?.toFloatOrNull() ?: 0f,
                                    pivotY = parser.getAttr("pivotY")?.toFloatOrNull() ?: 0f,
                                )
                                groupStack.last().children.add(group)
                                groupStack.addLast(group)
                            }
                            "path" -> {
                                val pathData = parser.getAttr("pathData") ?: continue
                                val pathItem = PathData(
                                    pathData = pathData,
                                    fillColor = parseColor(parser.getAttr("fillColor")),
                                    strokeColor = parseColor(parser.getAttr("strokeColor")),
                                    strokeWidth = parser.getAttr("strokeWidth")?.toFloatOrNull() ?: 0f,
                                    strokeLineCap = parser.getAttr("strokeLineCap") ?: "butt",
                                    strokeLineJoin = parser.getAttr("strokeLineJoin") ?: "miter",
                                )
                                groupStack.last().paths.add(pathItem)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "group" && groupStack.size > 1) {
                            groupStack.removeLast()
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        return VectorData(viewportWidth, viewportHeight, rootGroup)
    }

    private fun drawVector(canvas: Canvas, vector: VectorData, sizePx: Int) {
        val scaleX = sizePx / vector.viewportWidth
        val scaleY = sizePx / vector.viewportHeight
        canvas.save()
        canvas.scale(scaleX, scaleY)
        drawGroup(canvas, vector.rootGroup)
        canvas.restore()
    }

    private fun drawGroup(canvas: Canvas, group: GroupData) {
        canvas.save()
        canvas.translate(group.translateX, group.translateY)
        if (group.rotation != 0f) {
            canvas.rotate(group.rotation, group.pivotX, group.pivotY)
        }
        canvas.scale(group.scaleX, group.scaleY)

        for (pathData in group.paths) {
            drawPath(canvas, pathData)
        }
        for (child in group.children) {
            drawGroup(canvas, child)
        }
        canvas.restore()
    }

    private fun drawPath(canvas: Canvas, pathData: PathData) {
        val path = Path()
        runCatching {
            val nodes = PathParser.createNodesFromPathData(pathData.pathData)
            PathParser.PathDataNode.nodesToPath(nodes, path)
        }.onFailure { return }

        // Fill
        if (pathData.fillColor != null && pathData.fillColor != 0) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = pathData.fillColor
            }
            canvas.drawPath(path, paint)
        }

        // Stroke
        if (pathData.strokeColor != null && pathData.strokeColor != 0 && pathData.strokeWidth > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = pathData.strokeColor
                strokeWidth = pathData.strokeWidth
                strokeCap = when (pathData.strokeLineCap) {
                    "round" -> Paint.Cap.ROUND
                    "square" -> Paint.Cap.SQUARE
                    else -> Paint.Cap.BUTT
                }
                strokeJoin = when (pathData.strokeLineJoin) {
                    "round" -> Paint.Join.ROUND
                    "bevel" -> Paint.Join.BEVEL
                    else -> Paint.Join.MITER
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun parseColor(colorStr: String?): Int? {
        if (colorStr.isNullOrBlank()) return null
        return runCatching { android.graphics.Color.parseColor(colorStr) }.getOrNull()
    }

    private fun XmlPullParser.getAttr(name: String): String? {
        // Try with android namespace first, then without namespace
        return getAttributeValue("http://schemas.android.com/apk/res/android", name)
            ?: getAttributeValue(null, name)
    }

    private data class VectorData(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val rootGroup: GroupData,
    )

    private data class GroupData(
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val rotation: Float = 0f,
        val pivotX: Float = 0f,
        val pivotY: Float = 0f,
        val paths: MutableList<PathData> = mutableListOf(),
        val children: MutableList<GroupData> = mutableListOf(),
    )

    private data class PathData(
        val pathData: String,
        val fillColor: Int?,
        val strokeColor: Int?,
        val strokeWidth: Float,
        val strokeLineCap: String,
        val strokeLineJoin: String,
    )

    /**
     * Render PNG launcher icons at all standard densities from the vector drawables.
     * Generates both regular and round icons in mipmap-{density} directories.
     *
     * This ensures launchers that don't properly resolve adaptive icons from
     * mipmap-anydpi-v26 still get correctly sized PNG fallbacks.
     */
    /**
     * Blocking version — callers are responsible for dispatching to a background thread.
     * All existing callers run inside withContext(Dispatchers.IO).
     */
    fun renderPngIcons(workspacePath: String) {
        val backgroundFile = File(workspacePath, ICON_BACKGROUND_PATH)
        val foregroundFile = File(workspacePath, ICON_FOREGROUND_PATH)
        if (!backgroundFile.exists() && !foregroundFile.exists()) return

        DENSITY_ICON_SIZES.forEach { (density, sizePx) ->
            val mipmapDir = File(workspacePath, "src/main/res/mipmap-$density")
            mipmapDir.mkdirs()

            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            renderVectorXml(canvas, backgroundFile, sizePx)
            renderVectorXml(canvas, foregroundFile, sizePx)

            writePng(bitmap, File(mipmapDir, "ic_launcher.png"))
            writePng(bitmap, File(mipmapDir, "ic_launcher_round.png"))
            bitmap.recycle()
        }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * Standard Android launcher icon sizes per density bucket.
     * Launcher icons use 48dp base → mdpi=48px, hdpi=72px, etc.
     */
    private val DENSITY_ICON_SIZES = mapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192,
    )

    private const val TAG = "ProjectIconRenderer"
    private const val ICON_BACKGROUND_PATH = "src/main/res/drawable/ic_launcher_background.xml"
    private const val ICON_FOREGROUND_PATH = "src/main/res/drawable/ic_launcher_foreground.xml"
}
