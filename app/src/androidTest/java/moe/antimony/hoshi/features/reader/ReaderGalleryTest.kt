package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubResource
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory images only; does not open user books or modify preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderGalleryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val previous get() = context.getString(R.string.reader_gallery_previous)
    private val next get() = context.getString(R.string.reader_gallery_next)
    private val imageLabel get() = context.getString(R.string.reader_gallery_image)

    @Test
    fun buttonsAdvanceOneImageAndManualScrollingChangesTheirReference() {
        val book = book(20, 100, 200)
        val state = ReaderGalleryState()
        show(book, state)
        composeRule.onNodeWithText(previous).assertIsNotEnabled()
        composeRule.onNodeWithText(next).performClick()
        composeRule.runOnIdle { assertEquals(1, state.currentImageIndex(20)) }
        composeRule.onNodeWithText(next).performClick()
        composeRule.runOnIdle { assertEquals(2, state.currentImageIndex(20)) }
        composeRule.onNodeWithText(previous).performClick()
        composeRule.runOnIdle { assertEquals(1, state.currentImageIndex(20)) }

        composeRule.onNodeWithTag("gallery").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        var visible = 0
        composeRule.runOnIdle {
            visible = state.listState.firstVisibleItemIndex
            assertEquals(visible, state.currentImageIndex(20))
        }
        composeRule.onNodeWithText(next).performClick()
        composeRule.runOnIdle { assertEquals(visible + 1, state.currentImageIndex(20)) }
        composeRule.runOnIdle { repeat(20) { state.moveByOne(1, 20) } }
        composeRule.onNodeWithText(next).assertIsNotEnabled()
    }

    @Test
    fun removingAndReopeningGalleryKeepsPixelOffsetButNewBookSessionResetsIt() {
        val book = book(20, 100, 200)
        val session = mutableStateOf(holder())
        val visible = mutableStateOf(true)
        composeRule.setContent {
            HoshiReaderTheme(darkTheme = false) {
                if (visible.value) ReaderGalleryTab(
                    book, session.value.galleryState, {}, Modifier.width(320.dp).height(400.dp),
                )
            }
        }
        waitForImages()
        composeRule.runOnIdle { session.value.galleryState.listState.requestScrollToItem(3, 37) }
        waitForImages()
        composeRule.runOnIdle {
            assertEquals(3, session.value.galleryState.listState.firstVisibleItemIndex)
            assertEquals(37, session.value.galleryState.listState.firstVisibleItemScrollOffset)
            visible.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        waitForImages()
        composeRule.runOnIdle {
            assertEquals(3, session.value.galleryState.listState.firstVisibleItemIndex)
            assertEquals(37, session.value.galleryState.listState.firstVisibleItemScrollOffset)
            session.value = holder()
        }
        waitForImages()
        composeRule.runOnIdle {
            assertEquals(0, session.value.galleryState.listState.firstVisibleItemIndex)
            assertEquals(0, session.value.galleryState.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test fun portraitPreviewsKeepProportionsAndStayBelowControls() = checkLayout(100, 200, "portrait")
    @Test fun widePreviewsKeepProportionsAndStayBelowControls() = checkLayout(400, 100, "wide")

    @Test
    fun emptyGalleryShowsItsMessageWithoutNavigation() {
        show(book(0, 100, 200), ReaderGalleryState())
        composeRule.onNodeWithText(context.getString(R.string.reader_no_images)).assertExists()
        composeRule.onNodeWithText(previous).assertDoesNotExist()
        composeRule.onNodeWithText(next).assertDoesNotExist()
    }

    private fun checkLayout(width: Int, height: Int, name: String) {
        val book = book(1, width, height)
        var selected: String? = null
        show(book, ReaderGalleryState()) { selected = it }
        waitForImages()
        val bounds = composeRule.onAllNodesWithContentDescription(imageLabel)[0].getUnclippedBoundsInRoot()
        val galleryBounds = composeRule.onNodeWithTag("gallery").getUnclippedBoundsInRoot()
        val oldPreviewWidth = (galleryBounds.right - galleryBounds.left).value - 40f
        assertEquals(oldPreviewWidth * 0.8f, (bounds.right - bounds.left).value, 1f)
        assertEquals(oldPreviewWidth * height / width * 0.8f, (bounds.bottom - bounds.top).value, 1f)
        assertEquals(
            (galleryBounds.left.value + galleryBounds.right.value) / 2,
            (bounds.left.value + bounds.right.value) / 2, 1f,
        )
        for (label in listOf(previous, next)) {
            val button = composeRule.onNodeWithText(label)
            button.assertIsNotEnabled()
            val buttonBounds = button.getUnclippedBoundsInRoot()
            assertTrue("Navigation row must stay compact", buttonBounds.bottom - buttonBounds.top <= 24.dp)
            assertTrue("Buttons must not cover the image", bounds.top >= buttonBounds.bottom)
        }
        assertTrue("Controls must leave only the existing image padding", bounds.top - galleryBounds.top <= 32.dp)
        composeRule.onRoot().captureToImage().asAndroidBitmap().let { screenshot ->
            File(context.cacheDir, "gallery-$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        composeRule.onAllNodesWithContentDescription(imageLabel)[0].performClick()
        composeRule.runOnIdle { assertEquals(book.bookInfo.images!!.first(), selected) }
    }

    private fun show(book: EpubBook, state: ReaderGalleryState, onSelect: (String) -> Unit = {}) {
        composeRule.setContent {
            HoshiReaderTheme(darkTheme = false) {
                ReaderGalleryTab(book, state, onSelect, Modifier.width(320.dp).height(400.dp).testTag("gallery"))
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForImages() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(imageLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun holder() = ReaderWebViewStateHolder(ReaderSettings(), ReaderChapterPosition(0, 0.0))

    private fun book(count: Int, width: Int, height: Int): EpubBook {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        val paths = List(count) { "image-$it.png" }
        return EpubBook(
            title = "Gallery test", chapters = emptyList(),
            resources = paths.associateWith { EpubResource("image/png", bytes) },
            bookInfo = BookInfo(0, emptyMap(), images = paths),
        )
    }
}
