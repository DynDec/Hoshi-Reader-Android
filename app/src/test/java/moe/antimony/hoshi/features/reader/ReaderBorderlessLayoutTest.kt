package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBorderlessLayoutTest {
    @Test
    fun removingVerticalBordersUsesCompactEffectiveInsetsAndRestoresConfiguredValues() {
        val configured = ReaderSettings(
            verticalPadding = 12,
            topSafeAreaDp = 40,
            bottomSafeAreaDp = 46,
        )

        val compact = configured.copy(removeVerticalBorders = true)

        assertEquals(12, compact.verticalPadding)
        assertEquals(40, compact.topSafeAreaDp)
        assertEquals(46, compact.bottomSafeAreaDp)
        assertEquals(0, compact.effectiveVerticalPadding)
        assertEquals(ReaderBorderlessTopSafeAreaDp, compact.effectiveTopSafeAreaDp)
        assertEquals(ReaderBorderlessBottomSafeAreaDp, compact.effectiveBottomSafeAreaDp)
        assertEquals(12, compact.copy(removeVerticalBorders = false).effectiveVerticalPadding)
        assertEquals(40, compact.copy(removeVerticalBorders = false).effectiveTopSafeAreaDp)
        assertEquals(46, compact.copy(removeVerticalBorders = false).effectiveBottomSafeAreaDp)
    }

    @Test
    fun removingVerticalBordersUsesCompactContentInsetsAndHidesBorderChrome() {
        val compact = ReaderSettings(
            removeVerticalBorders = true,
            topSafeAreaDp = 40,
            bottomSafeAreaDp = 46,
        )

        assertEquals(
            ReaderContentChromeInsets(topDp = ReaderBorderlessTopSafeAreaDp, bottomDp = ReaderBorderlessBottomSafeAreaDp),
            readerContentChromeInsets(settings = compact),
        )

        val focusVisibility = readerChromeVisibility(
            focusMode = true,
            hasStatisticsToggle = true,
            hasSasayakiToggle = true,
            hasBackJump = true,
            hasForwardJump = true,
            removeVerticalBorders = compact.removeVerticalBorders,
        )
        assertFalse(focusVisibility.showBottomChrome)
        assertFalse(focusVisibility.showStatisticsToggle)
        assertFalse(focusVisibility.showSasayakiToggle)
        assertFalse(focusVisibility.showBackJump)
        assertFalse(focusVisibility.showForwardJump)

        val normalVisibility = readerChromeVisibility(
            focusMode = false,
            hasStatisticsToggle = true,
            hasSasayakiToggle = true,
            hasBackJump = true,
            hasForwardJump = true,
            removeVerticalBorders = compact.removeVerticalBorders,
        )
        // The in-reader back and menu buttons stay available in normal mode;
        // only the auxiliary border shortcuts are suppressed.
        assertTrue(normalVisibility.showBottomChrome)
    }

    @Test
    fun removingVerticalBordersHidesSasayakiBottomPlaybackControls() {
        val compact = ReaderSettings(
            removeVerticalBorders = true,
            bottomSafeAreaDp = 46,
        )
        val controls = readerSasayakiBottomPlaybackControls(
            settings = SasayakiSettings(showReaderBottomPlaybackControls = true),
            hasAudio = true,
            metrics = readerBottomChromeMetrics(compact.effectiveBottomSafeAreaDp),
            removeVerticalBorders = compact.removeVerticalBorders,
        )

        assertFalse(controls.visible)
        assertEquals(ReaderBorderlessBottomSafeAreaDp, controls.rowHeightDp)
    }

    @Test
    fun topStatisticsToggleScalesDownWithMinimumTopSafeAreaHeight() {
        val metrics = readerTopChromeMetrics(topSafeAreaDp = ReaderTopSafeAreaMinDp)

        assertEquals(18, metrics.topSafeAreaDp)
        assertEquals(18, metrics.topSasayakiButtonSizeDp)
        assertEquals(18, metrics.topStatisticsButtonSizeDp)
        assertEquals(16, metrics.topSasayakiIconSizeDp)
        assertEquals(16, metrics.topStatisticsIconSizeDp)
    }

    @Test
    fun borderlessModeHidesBottomProgressWithoutChangingProgressPreferences() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
            chapterCurrentCharacter = 12,
            chapterTotalCharacters = 40,
        )
        val normalSettings = ReaderSettings(
            showProgress = true,
            showChapterProgress = true,
            alwaysShowProgress = true,
        )
        val borderlessSettings = normalSettings.copy(removeVerticalBorders = true)

        assertEquals("355 / 169325 0.21% (12 / 40 30.00%)", readerBottomSafeProgressText(state, normalSettings))
        assertEquals("", readerBottomSafeProgressText(state, borderlessSettings))
        assertFalse(
            readerChromeLayout(
                state,
                borderlessSettings.copy(alwaysShowProgress = false, showProgressTop = false),
            ).showProgressInBottomBar,
        )
        assertTrue(borderlessSettings.showProgress)
        assertTrue(borderlessSettings.showChapterProgress)

        assertEquals("355 / 169325 0.21% (12 / 40 30.00%)", readerBottomSafeProgressText(state, borderlessSettings.copy(removeVerticalBorders = false)))
    }
}
