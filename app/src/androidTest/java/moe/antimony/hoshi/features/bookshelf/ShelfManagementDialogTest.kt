package moe.antimony.hoshi.features.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.epub.BookShelf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfManagementDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manyShelfRowsAreComposedLazilyAndRemainReachable() {
        val shelves = (1..40).map { index ->
            BookShelf(name = "Shelf $index", bookIds = emptyList())
        }

        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 360.dp, height = 640.dp),
                ) {
                    ShelfManagementDialog(
                        shelves = shelves,
                        showReading = true,
                        coverMode = BookshelfCoverMode.Show,
                        hideCollapsedShelfThumbnails = false,
                        bookColumns = 2,
                        maxBookColumns = 4,
                        onBookColumnsChange = {},
                        onHideCollapsedShelfThumbnailsChange = {},
                        onShowReadingChange = {},
                        onCoverModeChange = {},
                        onCreateShelf = {},
                        onDeleteShelf = {},
                        onRenameShelf = { _, _ -> },
                        onMoveShelf = { _, _ -> },
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithText("Shelf 40").assertCountEquals(0)

        composeRule.onNodeWithTag(ShelfManagementShelfListTag)
            .performScrollToNode(hasText("Shelf 40"))

        composeRule.onNodeWithText("Shelf 40").assertIsDisplayed()
    }

    @Test
    fun shelfRenameActionIsShownInMoreMenu() {
        setShelfManagementDialogContent(
            shelves = listOf(BookShelf(name = "Shelf 1", bookIds = emptyList())),
        )

        composeRule.onNodeWithContentDescription("More").performClick()

        composeRule.onNodeWithText("Rename shelf").assertIsDisplayed()
    }

    @Test
    fun moveShelfActionKeepsMoreMenuOpenForRepeatedMoves() {
        val moveRequests = mutableListOf<Pair<Int, Int>>()
        setShelfManagementDialogContent(
            shelves = listOf(
                BookShelf(name = "Shelf 1", bookIds = emptyList()),
                BookShelf(name = "Shelf 2", bookIds = emptyList()),
            ),
            onMoveShelf = { from, to -> moveRequests += from to to },
        )

        composeRule.onNodeWithTag(ShelfManagementShelfListTag)
            .performScrollToNode(hasText("Shelf 2"))
        composeRule.onAllNodesWithContentDescription("More").onLast().performClick()
        composeRule.onNodeWithText("Move shelf up").performClick()

        composeRule.onNodeWithText("Move shelf up").assertIsDisplayed()
        assertEquals(listOf(1 to 0), moveRequests)
    }

    @Test
    fun sliderTracksLibraryCapacityAndRetainsSavedColumnsAcrossResize() {
        var contentWidth by mutableStateOf(640)
        var savedColumns by mutableStateOf(6)
        val changes = mutableListOf<Int>()
        composeRule.setContent {
            val layoutSpec = MainShellLayoutSpec.forWidthDp(contentWidth)
            MaterialTheme {
                ShelfManagementDialog(
                    shelves = emptyList(),
                    showReading = false,
                    coverMode = BookshelfCoverMode.Show,
                    hideCollapsedShelfThumbnails = true,
                    bookColumns = layoutSpec.bookGridColumns(contentWidth, savedColumns),
                    maxBookColumns = layoutSpec.collapsedShelfPreviewColumns(contentWidth),
                    onBookColumnsChange = { savedColumns = it; changes += it },
                    onHideCollapsedShelfThumbnailsChange = {},
                    onShowReadingChange = {},
                    onCoverModeChange = {},
                    onCreateShelf = {},
                    onDeleteShelf = {},
                    onRenameShelf = { _, _ -> },
                    onMoveShelf = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        val slider = composeRule.onNodeWithTag(ShelfManagementBookColumnsTag)
        composeRule.onNodeWithText("Books per row").assertIsDisplayed()
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(6f, 2f..6f, 3))
        composeRule.runOnIdle { contentWidth = 360 }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(4f, 2f..4f, 1))
        composeRule.runOnIdle {
            assertEquals(6, savedColumns)
            assertEquals(emptyList<Int>(), changes)
            contentWidth = 640
        }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(6f, 2f..6f, 3))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(2f, 2f..6f, 3))
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(2, savedColumns)
            assertEquals(listOf(2), changes)
        }
    }

    private fun setShelfManagementDialogContent(
        shelves: List<BookShelf>,
        onMoveShelf: (Int, Int) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 360.dp, height = 640.dp),
                ) {
                    ShelfManagementDialog(
                        shelves = shelves,
                        showReading = true,
                        coverMode = BookshelfCoverMode.Show,
                        hideCollapsedShelfThumbnails = false,
                        bookColumns = 2,
                        maxBookColumns = 4,
                        onBookColumnsChange = {},
                        onHideCollapsedShelfThumbnailsChange = {},
                        onShowReadingChange = {},
                        onCoverModeChange = {},
                        onCreateShelf = {},
                        onDeleteShelf = {},
                        onRenameShelf = { _, _ -> },
                        onMoveShelf = onMoveShelf,
                        onDismiss = {},
                    )
                }
            }
        }
    }
}
