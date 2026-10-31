package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryDragReorderTest {
    private fun row(index: Int) = DictionaryDragReorder.RowBounds(index = index, top = index * 100f, bottom = (index + 1) * 100f)

    @Test
    fun targetIndexFollowsTheDraggedCenterAndClampsToVisibleRows() {
        fun target(startIndex: Int, centerY: Float, vararg rows: Int) =
            DictionaryDragReorder.targetIndex(startIndex, centerY, rows.map(::row))

        assertEquals(1, target(startIndex = 0, centerY = 150f, 0, 1))
        assertEquals(0, target(startIndex = 1, centerY = 50f, 0, 1))
        assertEquals(1, target(startIndex = 2, centerY = -200f, 1, 2))
        assertEquals(1, target(startIndex = 0, centerY = 700f, 0, 1))
        assertEquals(1, target(startIndex = 1, centerY = 500f))
    }

    @Test
    fun previewOrderMovesTheDraggedItemAndIgnoresInvalidIndexes() {
        val items = listOf("a", "b", "c", "d")

        assertEquals(listOf("b", "c", "a", "d"), DictionaryDragReorder.previewOrder(items, fromIndex = 0, toIndex = 2))
        assertEquals(listOf("a", "d", "b", "c"), DictionaryDragReorder.previewOrder(items, fromIndex = 3, toIndex = 1))
        assertEquals(items, DictionaryDragReorder.previewOrder(items, fromIndex = 5, toIndex = 1))
    }

    @Test
    fun itemOffsetsKeepTheDraggedItemUnderThePointerAndShiftOnlyCrossedNeighbors() {
        fun offsets(count: Int, startIndex: Int, targetIndex: Int, dragOffset: Float) = List(count) { index ->
            DictionaryDragReorder.itemOffset(index, startIndex, targetIndex, dragOffset, draggedItemSize = 72f)
        }

        assertEquals(listOf(140f, -72f, -72f, 0f), offsets(4, startIndex = 0, targetIndex = 2, dragOffset = 140f))
        assertEquals(listOf(0f, 72f, 72f, -140f), offsets(4, startIndex = 3, targetIndex = 1, dragOffset = -140f))
        assertEquals(listOf(0f, 20f, 0f), offsets(3, startIndex = 1, targetIndex = 1, dragOffset = 20f))
        assertEquals(88f, DictionaryDragReorder.adjustedDragOffsetAfterMove(160f, fromIndex = 0, toIndex = 1, itemSize = 72f), 0.001f)
        assertEquals(-16f, DictionaryDragReorder.adjustedDragOffsetAfterMove(-160f, fromIndex = 3, toIndex = 1, itemSize = 72f), 0.001f)
    }

    @Test
    fun autoScrollSpeedsUpTowardEitherEdgeAndStopsAwayFromThem() {
        fun delta(pointerY: Float) = DictionaryDragReorder.autoScrollDelta(
            pointerY = pointerY,
            viewportStart = 0f,
            viewportEnd = 600f,
            edgeThreshold = 80f,
            maxDelta = 20f,
        )

        assertEquals(-17f, delta(12f), 0.001f)
        assertEquals(15f, delta(580f), 0.001f)
        assertEquals(0f, delta(300f), 0.001f)
    }
}
