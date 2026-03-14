package com.vibe.app.feature.projecticon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
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
        context: Context,
        workspacePath: String,
        sizePx: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val background = loadDrawable(context, File(workspacePath, ICON_BACKGROUND_PATH))
        val foreground = loadDrawable(context, File(workspacePath, ICON_FOREGROUND_PATH))
        if (background == null && foreground == null) return@withContext null

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        background?.apply {
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
        }
        foreground?.apply {
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
        }
        bitmap.asImageBitmap()
    }

    private fun loadDrawable(
        context: Context,
        file: File,
    ): Drawable? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            file.inputStream().buffered().reader(Charsets.UTF_8).use { reader ->
                val parser = Xml.newPullParser()
                parser.setInput(reader)
                moveToStartTag(parser)
                Drawable.createFromXml(context.resources, parser, context.theme)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to render project icon file ${file.absolutePath}", error)
        }.getOrNull()
    }

    private fun moveToStartTag(parser: XmlPullParser) {
        while (parser.eventType != XmlPullParser.START_TAG &&
            parser.eventType != XmlPullParser.END_DOCUMENT
        ) {
            parser.next()
        }
    }

    private const val TAG = "ProjectIconRenderer"
    private const val ICON_BACKGROUND_PATH = "src/main/res/drawable/ic_launcher_background.xml"
    private const val ICON_FOREGROUND_PATH = "src/main/res/drawable/ic_launcher_foreground.xml"
}
