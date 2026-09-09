package moe.antimony.hoshi.features.reader

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageActionsTest {
    @Test
    fun contentTapsRevealHiddenFullscreenImageControlsThenToggle() {
        var visible = readerFullscreenImageControlsInitiallyVisible()
        assertFalse(visible)

        visible = readerFullscreenImageControlsVisibleAfterContentTap(visible)
        assertTrue(visible)

        visible = readerFullscreenImageControlsVisibleAfterContentTap(visible)
        assertFalse(visible)
    }

    @Test
    fun copyToastIsOnlyShownBeforeSystemClipboardFeedbackExists() {
        assertTrue(shouldShowReaderImageCopyToast(Build.VERSION_CODES.S_V2))
        assertFalse(shouldShowReaderImageCopyToast(Build.VERSION_CODES.TIRAMISU))
        assertFalse(shouldShowReaderImageCopyToast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }
}
