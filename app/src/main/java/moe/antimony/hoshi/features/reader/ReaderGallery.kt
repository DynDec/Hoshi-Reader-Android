package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook

private const val ReaderGalleryMaxPixelSize = 1600

internal fun EpubBook.galleryResourceUrl(path: String): String? {
    val rawPath = path.trim().replace('\\', '/')
    if (rawPath.isBlank() || rawPath.startsWith('/') || rawPath.startsWith("//")) return null
    val normalized = File(rawPath).normalize().invariantSeparatorsPath
    if (normalized == ".." || normalized.startsWith("../")) return null
    return URI(
        "https",
        "appassets.androidplatform.net",
        "/epub/$normalized",
        null,
    ).toASCIIString()
}

@Composable
internal fun ReaderGalleryTab(
    book: EpubBook,
    state: ReaderGalleryState,
    onImageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = book.bookInfo.images.orEmpty()
    if (images.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.reader_no_images),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        return
    }
    val manualScrollConnection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) state.onManualScroll()
                return Offset.Zero
            }
        }
    }
    val currentIndex = state.currentImageIndex(images.size)
    Column(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { state.moveByOne(-1, images.size) },
                    enabled = currentIndex > 0,
                    modifier = Modifier.heightIn(min = 24.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.reader_gallery_previous)) }
                TextButton(
                    onClick = { state.moveByOne(1, images.size) },
                    enabled = currentIndex < images.lastIndex,
                    modifier = Modifier.heightIn(min = 24.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.reader_gallery_next)) }
            }
        }
        LazyColumn(
            state = state.listState,
            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(manualScrollConnection),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(images, key = { it }) { path ->
                ReaderGalleryImage(
                    book = book,
                    state = state,
                    path = path,
                    onClick = { onImageSelected(path) },
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ReaderGalleryImage(
    book: EpubBook,
    state: ReaderGalleryState,
    path: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember(book, path) { mutableStateOf(state.cachedPreview(path)) }
    var widthPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(book, path, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        // Decode for the laid-out preview width, never beyond the previous size cap.
        val targetWidthPx = widthPx.coerceAtMost(ReaderGalleryMaxPixelSize)
        if (preview?.targetWidthPx == targetWidthPx) return@LaunchedEffect
        val image = withContext(Dispatchers.IO) {
            book.readResource(path)?.decodeReaderGalleryBitmap(targetWidthPx)?.asImageBitmap()
        } ?: return@LaunchedEffect
        preview = ReaderGalleryPreview(image, targetWidthPx).also { state.cachePreview(path, it) }
    }
    val image = preview?.image
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .fillMaxWidth(ReaderGalleryPreviewScale)
            .aspectRatio(state.aspectRatio(path))
            .onSizeChanged { widthPx = it.width }
            .clip(shape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.reader_gallery_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun ByteArray.decodeReaderGalleryBitmap(targetWidthPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val scale = minOf(
        1f,
        targetWidthPx.toFloat() / bounds.outWidth,
        ReaderGalleryMaxPixelSize.toFloat() / maxOf(bounds.outWidth, bounds.outHeight),
    )
    val targetWidth = (bounds.outWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (bounds.outHeight * scale).roundToInt().coerceAtLeast(1)
    // Subsample as far as possible while staying at or above the target size.
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth && bounds.outHeight / (sampleSize * 2) >= targetHeight) {
        sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        this,
        0,
        size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    if (decoded.width <= targetWidth && decoded.height <= targetHeight) return decoded
    val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
