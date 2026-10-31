package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStatisticsSheetTest {
    @Test
    fun readerPanelHandleDragUsesSingleHeightOrDismisses() {
        val targetHeight = 700f
        val threshold = 96f

        assertEquals(
            targetHeight,
            readerPanelSettleTarget(
                currentHeight = 720f,
                totalDrag = -120f,
                targetHeight = targetHeight,
                threshold = threshold,
            ),
        )
        assertEquals(
            targetHeight,
            readerPanelSettleTarget(
                currentHeight = 640f,
                totalDrag = 64f,
                targetHeight = targetHeight,
                threshold = threshold,
            ),
        )
        assertNull(
            readerPanelSettleTarget(
                currentHeight = 560f,
                totalDrag = 160f,
                targetHeight = targetHeight,
                threshold = threshold,
            ),
        )
    }
}
