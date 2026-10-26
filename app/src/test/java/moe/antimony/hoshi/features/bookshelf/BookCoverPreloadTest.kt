package moe.antimony.hoshi.features.bookshelf

import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class BookCoverPreloadTest {
    private fun source(name: String) = BookCoverSource(path = "/tmp/$name.jpg", cacheKey = name)

    @Test
    fun requestSizeFollowsCoverAspectAndCapsAtLargestThumbnail() {
        assertEquals(Size(301, 424), bookCoverRequestSize(cellWidthPx = 300))
        assertEquals(Size(545, 768), bookCoverRequestSize(cellWidthPx = 900))
    }

    @Test
    fun preloadSkipsPreviewedAndMissingCoversInDisplayOrder() {
        val sources = bookCoverPreloadSources(
            collapsedShelves = listOf(
                listOf(source("a"), source("b"), null, source("c")),
                listOf(source("d"), source("e")),
            ),
            previewCount = 1,
        )

        assertEquals(listOf(source("b"), source("c"), source("e")), sources)
    }

    @Test
    fun preloadBudgetUsesShareOfMemoryCacheWithUpperBound() {
        val coverBytes = 545L * 768L * 4L

        assertEquals(4, bookCoverPreloadBudget(cacheMaxBytes = coverBytes * 10, Size(545, 768)))
        assertEquals(48, bookCoverPreloadBudget(cacheMaxBytes = coverBytes * 1_000, Size(545, 768)))
    }
}
