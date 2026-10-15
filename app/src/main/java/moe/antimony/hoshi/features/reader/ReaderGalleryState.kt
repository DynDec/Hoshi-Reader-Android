package moe.antimony.hoshi.features.reader

import androidx.collection.LruCache
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

internal const val ReaderGalleryPreviewScale = 0.8f

/** A decoded preview and the layout width, in pixels, it was decoded for. */
internal data class ReaderGalleryPreview(val image: ImageBitmap, val targetWidthPx: Int)

internal class ReaderGalleryState(
    val listState: LazyListState = LazyListState(),
    previewCacheBytes: Int = defaultReaderGalleryPreviewCacheBytes(),
) {
    private var requestedImageIndex by mutableStateOf<Int?>(null)
    private val aspectRatios = mutableStateMapOf<String, Float>()

    // Previews scrolled out of the list are kept so returning to them does not
    // re-read and re-decode the image from the EPUB.
    private val previews = object : LruCache<String, ReaderGalleryPreview>(previewCacheBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: ReaderGalleryPreview): Int =
            (value.image.width.toLong() * value.image.height * 4).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    fun cachedPreview(path: String): ReaderGalleryPreview? = previews[path]

    fun cachePreview(path: String, preview: ReaderGalleryPreview) {
        previews.put(path, preview)
        recordAspectRatio(path, preview.image.width, preview.image.height)
    }

    fun currentImageIndex(imageCount: Int): Int =
        if (imageCount == 0) -1
        else (requestedImageIndex ?: listState.firstVisibleItemIndex).coerceIn(0, imageCount - 1)

    fun moveByOne(direction: Int, imageCount: Int) {
        if (imageCount == 0) return
        val target = (currentImageIndex(imageCount) + direction.coerceIn(-1, 1))
            .coerceIn(0, imageCount - 1)
        // Retain the requested index when short final images cannot reach the top,
        // and let rapid taps advance before the list's next layout pass.
        requestedImageIndex = target
        listState.requestScrollToItem(target)
    }

    fun onManualScroll() {
        requestedImageIndex = null
    }

    fun aspectRatio(path: String): Float = aspectRatios[path] ?: 0.7f

    fun recordAspectRatio(path: String, width: Int, height: Int) {
        if (width > 0 && height > 0) aspectRatios[path] = width.toFloat() / height
    }
}

private fun defaultReaderGalleryPreviewCacheBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
