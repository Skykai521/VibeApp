package com.vibe.app.presentation.ui.chat

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.vibe.app.R
import com.vibe.app.presentation.theme.VibeAppTheme
import java.io.File

@Composable
fun UserChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    files: List<String> = emptyList(),
    onLongPress: () -> Unit
) {
    val cardColor = CardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    )
    Log.d("UserChatBubble", "files: $files (size: ${files.size})")
    files.forEachIndexed { index, file ->
        Log.d("UserChatBubble", "files[$index] = '$file' (length: ${file.length})")
    }

    Column(horizontalAlignment = Alignment.End) {
        Card(
            modifier = modifier
                .padding(end = 10.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress.invoke() })
                },
            shape = RoundedCornerShape(12.dp),
            colors = cardColor
        ) {
            Markdown(
                content = text.trimIndent(),
                modifier = Modifier.padding(16.dp),
                colors = chatMarkdownColors(),
                typography = chatMarkdownTypography(),
                components = chatMarkdownComponents(),
            )
        }
        UserFileThumbnailRow(
            modifier = Modifier
                .padding(top = 8.dp, end = 10.dp),
            files = files
        )
    }
}

@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canRetry: Boolean,
    isLoading: Boolean,
    isError: Boolean = false,
    text: String,
    onCopyClick: () -> Unit = {},
    onSelectClick: () -> Unit = {},
    onRetryClick: () -> Unit = {}
) {
    val cardColor = CardColors(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        disabledContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
    )

    Column(modifier = modifier) {
        Column {
            Card(
                shape = RoundedCornerShape(0.dp),
                colors = cardColor
            ) {
                val displayText = if (isLoading) text.trimIndent() + "●" else text.trimIndent()

                Markdown(
                    content = displayText,
                    modifier = Modifier.padding(16.dp),
                    colors = chatMarkdownColors(),
                    typography = chatMarkdownTypography(),
                    components = chatMarkdownComponents(),
                )
            }

            if (!isLoading) {
                Row(
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    if (!isError) {
                        CopyTextIcon(onCopyClick)
                        Spacer(modifier = Modifier.width(4.dp))
                        SelectTextIcon(onSelectClick)
                    }
                    if (canRetry) {
                        Spacer(modifier = Modifier.width(4.dp))
                        RetryIcon(onRetryClick)
                    }
                }
            }
        }
    }
}

@Composable
fun VibeAppIcon(loading: Boolean) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(40.dp),
                ambientColor = Color.Black.copy(alpha = 0.1f),
                spotColor = Color.Black.copy(alpha = 0.15f),
            )
            .size(40.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(color = Color(0xFFFFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp)
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_vibe),
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun PlatformButton(
    isLoading: Boolean,
    name: String,
    selected: Boolean,
    onPlatformClick: () -> Unit
) {
    val buttonContent: @Composable RowScope.() -> Unit = {
        Spacer(modifier = Modifier.width(12.dp))

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        if (isLoading) Spacer(modifier = Modifier.width(4.dp))
    }

    TextButton(
        modifier = Modifier.widthIn(max = 160.dp),
        onClick = onPlatformClick,
        colors = if (selected) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors(),
        content = buttonContent
    )
}

@Composable
private fun CopyTextIcon(onCopyClick: () -> Unit) {
    IconButton(onClick = onCopyClick, modifier = Modifier.size(38.dp)) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_copy),
            contentDescription = stringResource(R.string.copy_text),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun SelectTextIcon(onSelectClick: () -> Unit) {
    IconButton(onClick = onSelectClick, modifier = Modifier.size(38.dp)) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_select),
            contentDescription = stringResource(R.string.select_text),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun RetryIcon(onRetryClick: () -> Unit) {
    IconButton(onClick = onRetryClick, modifier = Modifier.size(38.dp)) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.retry),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Preview
@Composable
fun UserChatBubblePreview() {
    val sampleText = """
        How can I print hello world
        in Python?
    """.trimIndent()
    VibeAppTheme {
        UserChatBubble(text = sampleText, files = emptyList(), onLongPress = {})
    }
}

@Preview
@Composable
fun OpponentChatBubblePreview() {
    val sampleText = """
        # Demo
    
        Emphasis, aka italics, with *asterisks* or _underscores_. Strong emphasis, aka bold, with **asterisks** or __underscores__. Combined emphasis with **asterisks and _underscores_**. [Links with two blocks, text in square-brackets, destination is in parentheses.](https://www.example.com). Inline `code` has `back-ticks around` it.
    
        1. First ordered list item
        2. Another item
            * Unordered sub-list.
        3. And another item.
            You can have properly indented paragraphs within list items. Notice the blank line above, and the leading spaces (at least one, but we'll use three here to also align the raw Markdown).
    
        * Unordered list can use asterisks
        - Or minuses
        + Or pluses
    """.trimIndent()
    VibeAppTheme {
        OpponentChatBubble(
            text = sampleText,
            canRetry = true,
            isLoading = false,
            onCopyClick = {},
            onRetryClick = {}
        )
    }
}

@Composable
private fun UserFileThumbnailRow(
    modifier: Modifier = Modifier,
    files: List<String>
) {
    // Filter out empty strings and check if we have valid files
    val validFiles = files.filter { it.isNotEmpty() && it.isNotBlank() }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    Log.d("UserFileThumbnailRow", "Original files: $files (size: ${files.size})")
    Log.d("UserFileThumbnailRow", "Valid files: $validFiles (size: ${validFiles.size})")

    if (validFiles.isEmpty()) {
        return
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        )
    ) {
        Row(
            modifier = Modifier
                .wrapContentHeight()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            validFiles.forEach { filePath ->
                UserFileThumbnail(
                    filePath = filePath,
                    onImageClick = { previewImagePath = filePath }
                )
            }
        }
    }

    previewImagePath?.let { imagePath ->
        FullscreenImagePreview(
            filePath = imagePath,
            onDismissRequest = { previewImagePath = null }
        )
    }
}

@Composable
private fun UserFileThumbnail(
    filePath: String,
    onImageClick: () -> Unit
) {
    val file = File(filePath)
    val isImage = isImageFile(file.extension)
    val imageModel = remember(filePath) { Uri.fromFile(file) }

    Column(
        modifier = Modifier.width(if (isImage) 144.dp else 92.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(if (isImage) 144.dp else 92.dp)
                .size(if (isImage) 144.dp else 92.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                .then(if (isImage) Modifier.clickable(onClick = onImageClick) else Modifier)
        ) {
            if (isImage) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .width(if (isImage) 144.dp else 92.dp)
        )
    }
}

@Composable
private fun chatMarkdownColors() = markdownColor(
    codeBackground = MaterialTheme.colorScheme.surfaceVariant,
)

@Composable
fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.headlineSmall,
    h2 = MaterialTheme.typography.titleLarge,
    h3 = MaterialTheme.typography.titleMedium,
    h4 = MaterialTheme.typography.titleSmall,
    h5 = MaterialTheme.typography.bodyLarge,
    h6 = MaterialTheme.typography.bodyMedium,
)

@Composable
fun chatMarkdownComponents() = markdownComponents(
    codeFence = {
        MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style ->
            MarkdownHighlightedCode(
                code = code,
                language = language ?: "java",
                style = style,
                showHeader = true,
            )
        }
    },
    codeBlock = {
        MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, style ->
            MarkdownHighlightedCode(
                code = code,
                language = language ?: "java",
                style = style,
                showHeader = true,
            )
        }
    },
)

@Composable
fun FullscreenImagePreview(
    filePath: String,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = Uri.fromFile(File(filePath)),
                contentDescription = File(filePath).name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

private fun isImageFile(extension: String?): Boolean {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    return extension?.lowercase() in imageExtensions
}
