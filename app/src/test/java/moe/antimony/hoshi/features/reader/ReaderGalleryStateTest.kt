package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.lazy.LazyListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderGalleryStateTest {
    @Test
    fun rapidTapsMoveExactlyOneImageInEitherDirectionWithoutWrapping() {
        val state = ReaderGalleryState()

        state.moveByOne(-1, 4)
        assertEquals(0, state.currentImageIndex(4))
        repeat(3) { expected ->
            state.moveByOne(1, 4)
            assertEquals(expected + 1, state.currentImageIndex(4))
        }
        state.moveByOne(1, 4)
        assertEquals(3, state.currentImageIndex(4))
        state.moveByOne(-1, 4)
        assertEquals(2, state.currentImageIndex(4))
    }

    @Test
    fun manualScrollingReanchorsNavigationToTheFirstVisibleImage() {
        val state = ReaderGalleryState(LazyListState(firstVisibleItemIndex = 4, firstVisibleItemScrollOffset = 23))

        state.moveByOne(1, 10)
        state.moveByOne(1, 10)
        assertEquals(6, state.currentImageIndex(10))
        state.onManualScroll()
        assertEquals(state.listState.firstVisibleItemIndex, state.currentImageIndex(10))
        val visible = state.currentImageIndex(10)
        state.moveByOne(-1, 10)
        assertEquals(visible - 1, state.currentImageIndex(10))
    }

    @Test
    fun emptyAndSingleImageListsDoNotAdvance() {
        val state = ReaderGalleryState()
        state.moveByOne(1, 0)
        assertEquals(-1, state.currentImageIndex(0))
        state.moveByOne(1, 1)
        state.moveByOne(-1, 1)
        assertEquals(0, state.currentImageIndex(1))
    }

    @Test
    fun closingContentsAndSwitchingTabsKeepSessionStateButNewSessionStartsFresh() {
        val session = holder()
        val gallery = session.galleryState
        gallery.moveByOne(1, 10)
        gallery.recordAspectRatio("wide.png", 800, 200)
        session.openGoToFromMenu()
        session.dismissGoTo()
        session.openGoToFromMenu()
        session.selectGoToTab(ReaderGoToTab.Chapters)
        session.selectGoToTab(ReaderGoToTab.Gallery)

        assertSame(gallery, session.galleryState)
        assertEquals(1, session.galleryState.currentImageIndex(10))
        assertEquals(4f, session.galleryState.aspectRatio("wide.png"), 0f)
        val newSession = holder()
        assertNotSame(gallery.listState, newSession.galleryState.listState)
        assertEquals(0, newSession.galleryState.currentImageIndex(10))
        assertEquals(0, newSession.galleryState.listState.firstVisibleItemScrollOffset)
        assertEquals(0.7f, newSession.galleryState.aspectRatio("wide.png"), 0f)
    }

    @Test
    fun restoringListStatePreservesImageAndPixelOffset() {
        val list = LazyListState(firstVisibleItemIndex = 8, firstVisibleItemScrollOffset = 91)
        val state = ReaderGalleryState(list)
        assertSame(list, state.listState)
        assertEquals(8, state.currentImageIndex(15))
        assertEquals(91, state.listState.firstVisibleItemScrollOffset)
    }

    private fun holder() = ReaderWebViewStateHolder(ReaderSettings(), ReaderChapterPosition(0, 0.0))
}
