package moe.antimony.hoshi.features.bookshelf

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.size.Size
import coil3.size.pxOrElse

/**
 * Decodes covers of collapsed shelves into the memory cache so expanding a shelf shows them at
 * once. Requests run one at a time to leave decode slots for on-screen covers, and stop at a
 * budget so the preload never evicts what is already displayed.
 */
@Composable
internal fun PreloadBookCovers(
    sources: List<BookCoverSource>,
    coverMode: BookshelfCoverMode,
    requestSize: Size,
) {
    val context = LocalPlatformContext.current
    LaunchedEffect(context, sources, coverMode, requestSize) {
        val imageLoader = context.imageLoader
        val cacheMaxBytes = imageLoader.memoryCache?.maxSize ?: return@LaunchedEffect
        val budget = bookCoverPreloadBudget(cacheMaxBytes, requestSize)
        sources
            .mapNotNull { resolveBookCoverArtwork(coverMode, Build.VERSION.SDK_INT, it).coverSource }
            .take(budget)
            .forEach { source -> imageLoader.execute(bookCoverImageRequest(context, source, requestSize)) }
    }
}

/**
 * Covers of collapsed shelves in display order, skipping the ones their preview row already
 * loads at the shared request size.
 */
internal fun bookCoverPreloadSources(
    collapsedShelves: List<List<BookCoverSource?>>,
    previewCount: Int,
): List<BookCoverSource> =
    collapsedShelves.flatMap { shelf -> shelf.drop(previewCount).filterNotNull() }.distinct()

internal fun bookCoverPreloadBudget(cacheMaxBytes: Long, requestSize: Size): Int {
    val coverBytes = requestSize.width.pxOrElse { 1 }.toLong() *
        requestSize.height.pxOrElse { 1 }.toLong() * BytesPerPixel
    return (cacheMaxBytes * PreloadCacheShare / coverBytes.coerceAtLeast(1)).toInt()
        .coerceIn(0, MaxPreloadedCovers)
}

private const val BytesPerPixel = 4L
private const val PreloadCacheShare = 0.4
private const val MaxPreloadedCovers = 48
