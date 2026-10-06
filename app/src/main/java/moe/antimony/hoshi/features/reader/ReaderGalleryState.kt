package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal const val ReaderGalleryPreviewScale = 0.8f

internal class ReaderGalleryState(
    val listState: LazyListState = LazyListState(),
) {
    private var requestedImageIndex by mutableStateOf<Int?>(null)
    private val aspectRatios = mutableStateMapOf<String, Float>()

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
