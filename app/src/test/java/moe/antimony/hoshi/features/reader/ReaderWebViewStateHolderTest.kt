package moe.antimony.hoshi.features.reader

import androidx.compose.ui.unit.IntSize
import kotlin.io.path.createTempDirectory
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import moe.antimony.hoshi.features.sasayaki.SasayakiSheetTab
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebViewStateHolderTest {
    @Test
    fun groupingModeChangesAndSyncedPositionsKeepOriginalChaptersAndJumpHistory() {
        val holder = stateHolder(initialIndex = 0)
        holder.syncSettings(ReaderSettings(viewMode = ReaderViewMode.Continuous))
        holder.markWebViewRestored()
        holder.recordSourcePosition(ReaderChapterPosition(2, 0.75))
        holder.syncSettings(ReaderSettings(viewMode = ReaderViewMode.Continuous, mergeImageOnlyChapters = true))
        assertEquals(ReaderChapterPosition(2, 0.75), holder.readerPosition.loadPosition)
        holder.markWebViewRestored()
        holder.recordSourcePosition(ReaderChapterPosition(3, 0.4))
        holder.syncSettings(ReaderSettings(viewMode = ReaderViewMode.VisualNovel, mergeImageOnlyChapters = true))
        assertEquals(ReaderChapterPosition(3, 0.4), holder.readerPosition.loadPosition)
        holder.markWebViewRestored()
        holder.jumpToWithHistory(ReaderChapterPosition(4, 0.2))
        holder.markWebViewRestored()
        holder.applySyncedBookmark(ReaderChapterPosition(1, 0.5))
        assertEquals(ReaderChapterPosition(1, 0.5), holder.readerPosition.loadPosition)
        assertEquals(ReaderChapterPosition(3, 0.4), holder.backTargetPosition)
    }
    @Test
    fun syncedBookmarkRestoresSamePositionWithoutAddingJumpHistory() {
        val holder = stateHolder(initialIndex = 2)
        holder.jumpToWithHistory(ReaderChapterPosition(3, 0.1))
        holder.markWebViewRestored()
        val epoch = holder.webViewRestoreEpoch
        holder.applySyncedBookmark(ReaderChapterPosition(3, 0.1))
        assertEquals(epoch + 1, holder.webViewRestoreEpoch)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(ReaderChapterPosition(2, 0.0), holder.backTargetPosition)
        assertNull(holder.recordContinuousScrollProgress(0.9, epoch))
        holder.markWebViewRestored()
        assertEquals(ReaderChapterPosition(3, 0.1), holder.readerPosition.displayedPosition)
    }

    @Test
    fun rendererRecoveryPreservesUnfinishedCueLandingButDoesNotRevealCompletedCue() {
        val cue = PendingSasayakiCue(sasayakiProgressCue(80), true, SasayakiCueRevealSource.DirectJump)
        val queued = cue.copy(cue = sasayakiProgressCue(120))
        assertEquals(cue, readerSasayakiCueAfterRendererTermination(null, cue, null, cue))
        assertEquals(cue, readerSasayakiCueAfterRendererTermination(null, null, cue.cue, cue))
        assertEquals(queued, readerSasayakiCueAfterRendererTermination(queued, cue, null, cue))
        assertEquals(cue.copy(reveal = false), readerSasayakiCueAfterRendererTermination(null, null, null, cue))
        assertNull(readerSasayakiCueAfterRendererTermination(null, null, null, null))
    }

    @Test
    fun rendererTerminationRestoresLatestAcceptedPositionAndKeepsHistory() {
        val holder = stateHolder(initialIndex = 2)
        holder.jumpToWithHistory(ReaderChapterPosition(3, 0.1))
        holder.markWebViewRestored()
        val oldEpoch = holder.webViewRestoreEpoch
        holder.recordContinuousScrollDisplayProgress(0.67, oldEpoch)

        assertTrue(holder.onRendererTerminated(ReaderRendererTermination(0)))

        assertEquals(ReaderChapterPosition(3, 0.67), holder.readerPosition.loadPosition)
        assertEquals(ReaderChapterPosition(2, 0.0), holder.backTargetPosition)
        assertEquals(1, holder.webViewGeneration)
        assertTrue(holder.isWebViewRestoring)
        assertFalse(holder.canAcceptReaderNavigationInput())
        assertNull(holder.recordContinuousScrollProgress(0.9, oldEpoch))
        assertFalse(holder.onRendererTerminated(ReaderRendererTermination(0)))
        assertEquals(1, holder.webViewGeneration)
        holder.markWebViewRestored()
        assertNull(holder.recordContinuousScrollProgress(0.9, oldEpoch))
        assertEquals(ReaderChapterPosition(3, 0.67), holder.readerPosition.displayedPosition)
    }

    @Test
    fun repeatedTerminationDuringRestoreKeepsPendingNavigationTarget() {
        val holder = stateHolder(initialIndex = 2)
        holder.jumpTo(ReaderChapterPosition(4, 0.3), "section")
        assertTrue(holder.onRendererTerminated(ReaderRendererTermination(0)))
        assertEquals("section", holder.readerPosition.loadFragment)
        assertTrue(holder.onRendererTerminated(ReaderRendererTermination(1)))
        assertEquals(ReaderChapterPosition(4, 0.3), holder.readerPosition.loadPosition)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.55)
        assertTrue(holder.onRendererTerminated(ReaderRendererTermination(2)))
        assertNull(holder.readerPosition.loadFragment)
        assertEquals(ReaderChapterPosition(4, 0.55), holder.readerPosition.loadPosition)
    }

    @Test
    fun recordsDisplayedProgressWithoutChangingLoadTarget() {
        val holder = stateHolder(initialIndex = 2)

        val saved = holder.recordDisplayedProgress(0.42)

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.0), holder.readerPosition.loadPosition)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.readerPosition.displayedPosition)
        assertEquals(holder.readerPosition.displayedPosition, saved)
    }

    @Test
    fun chapterLimitNavigationUpdatesLoadAndDisplayedPositions() {
        val holder = stateHolder(initialIndex = 2)

        val next = holder.goToNextChapter(lastIndex = 3)
        val previous = holder.goToPreviousChapter()

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), next)
        assertEquals(ReaderChapterPosition(index = 2, progress = 1.0), previous)
        assertEquals(previous, holder.readerPosition.loadPosition)
        assertEquals(previous, holder.readerPosition.displayedPosition)
    }

    @Test
    fun activeJumpRecordsDisplayedOriginAndClearsForwardHistory() {
        val holder = stateHolder(initialIndex = 2)
        holder.recordDisplayedProgress(0.42)

        holder.jumpToWithHistory(ReaderChapterPosition(index = 5, progress = 0.0))
        holder.navigateBackInJumpHistory()
        holder.navigateForwardInJumpHistory()
        holder.jumpToWithHistory(ReaderChapterPosition(index = 7, progress = 0.0))

        assertEquals(ReaderChapterPosition(index = 5, progress = 0.0), holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
        assertEquals(ReaderChapterPosition(index = 7, progress = 0.0), holder.readerPosition.displayedPosition)
    }

    @Test
    fun jumpingToCurrentDisplayedPositionDoesNotStartRestoreOrRecordHistory() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.42)

        val saved = holder.jumpToWithHistory(ReaderChapterPosition(index = 2, progress = 0.42))

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), saved)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.readerPosition.displayedPosition)
        assertTrue(holder.canAcceptReaderNavigationInput())
        assertNull(holder.backTargetPosition)
    }

    @Test
    fun jumpingToCurrentLoadPositionAfterDisplayedProgressMovedStartsNewRestoreEpoch() {
        val holder = stateHolder(initialIndex = 2, initialProgress = 0.42)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.50)
        val previousEpoch = holder.webViewRestoreEpoch

        val saved = holder.jumpToWithHistory(ReaderChapterPosition(index = 2, progress = 0.42))

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), saved)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(previousEpoch + 1, holder.webViewRestoreEpoch)
    }

    @Test
    fun jumpHistoryBackAndForwardMirrorIosReaderBehavior() {
        val holder = stateHolder(initialIndex = 2)
        holder.recordDisplayedProgress(0.42)
        holder.jumpToWithHistory(ReaderChapterPosition(index = 5, progress = 0.0))
        holder.recordDisplayedProgress(0.25)

        val back = holder.navigateBackInJumpHistory()
        val forward = holder.navigateForwardInJumpHistory()

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), back)
        assertEquals(ReaderChapterPosition(index = 5, progress = 0.25), forward)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
    }

    @Test
    fun ordinaryChapterNavigationDoesNotRecordJumpHistory() {
        val holder = stateHolder(initialIndex = 2)

        holder.goToNextChapter(lastIndex = 3)
        holder.goToPreviousChapter()

        assertNull(holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
    }

    @Test
    fun manualProgressClearsForwardHistoryOnlyWhenNoBackTargetRemains() {
        val holder = stateHolder(initialIndex = 2)
        holder.jumpToWithHistory(ReaderChapterPosition(index = 5, progress = 0.0))
        holder.navigateBackInJumpHistory()

        holder.recordDisplayedProgress(0.6)
        holder.clearForwardHistoryAfterManualMovement()

        assertNull(holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
    }

    @Test
    fun manualProgressKeepsForwardHistoryWhenBackTargetStillExists() {
        val holder = stateHolder(initialIndex = 1)
        holder.jumpToWithHistory(ReaderChapterPosition(index = 2, progress = 0.0))
        holder.jumpToWithHistory(ReaderChapterPosition(index = 3, progress = 0.0))
        holder.navigateBackInJumpHistory()

        holder.recordDisplayedProgress(0.6)
        holder.clearForwardHistoryAfterManualMovement()

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.0), holder.backTargetPosition)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.forwardTargetPosition)
    }

    @Test
    fun continuousScrollProgressIsIgnoredWhileWebViewIsRestoringLikeIos() {
        val holder = stateHolder(initialIndex = 2)

        val next = holder.goToNextChapter(lastIndex = 3)
        val ignored = holder.recordContinuousScrollProgress(0.72, holder.webViewRestoreEpoch)

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), next)
        assertNull(ignored)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)

        holder.markWebViewRestored()
        val saved = holder.recordContinuousScrollProgress(0.12, holder.webViewRestoreEpoch)

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.12), saved)
        assertEquals(saved, holder.readerPosition.displayedPosition)
    }

    @Test
    fun restoreCompletionActionKeepsReaderRestoringUntilWebViewIsVisible() {
        var restoreCompletedCount = 0

        val afterVisible = readerRestoreCompletionAfterVisibleAction(
            chapterFragment = null,
            evaluateProgress = { error("Progress should not be evaluated without a fragment") },
            onSaveProgress = { error("Progress should not be saved without a fragment") },
            onRestoreCompleted = { restoreCompletedCount += 1 },
        )

        assertEquals(0, restoreCompletedCount)

        afterVisible { true }

        assertEquals(1, restoreCompletedCount)
    }

    @Test
    fun fragmentRestoreCompletionEvaluatesProgressOnlyAfterWebViewIsVisible() {
        val events = mutableListOf<String>()

        val afterVisible = readerRestoreCompletionAfterVisibleAction(
            chapterFragment = "chapter-anchor",
            evaluateProgress = { callback ->
                events += "evaluate"
                callback("\"0.42\"")
            },
            onSaveProgress = { progress -> events += "save $progress" },
            onRestoreCompleted = { events += "restored" },
        )

        assertTrue(events.isEmpty())

        afterVisible {
            events += "visible"
            true
        }

        assertEquals(listOf("visible", "evaluate", "save 0.42", "restored"), events)
    }

    @Test
    fun restoreCompletionCanRunSasayakiLandingBeforeWebViewIsVisible() {
        val events = mutableListOf<String>()

        val completion = readerRestoreCompletionAfterVisibleAction(
            chapterFragment = null,
            evaluateProgress = { error("Progress should not be evaluated without a fragment") },
            onSaveProgress = { error("Progress should not be saved without a fragment") },
            onRestoreCompleted = { events += "restored" },
            beforeVisible = { show, completeAfterVisible ->
                events += "sasayaki"
                if (show()) {
                    events += "save"
                    completeAfterVisible()
                }
            },
        )

        completion {
            events += "visible"
            true
        }

        assertEquals(listOf("sasayaki", "visible", "save", "restored"), events)
    }

    @Test
    fun staleRestoreCompletionDoesNotMarkReaderRestoredWhenShowIsRejected() {
        val events = mutableListOf<String>()

        val completion = readerRestoreCompletionAfterVisibleAction(
            chapterFragment = null,
            evaluateProgress = { error("Progress should not be evaluated without a fragment") },
            onSaveProgress = { error("Progress should not be saved without a fragment") },
            onRestoreCompleted = { events += "restored" },
            beforeVisible = { show, completeAfterVisible ->
                events += "sasayaki"
                if (show()) {
                    events += "save"
                    completeAfterVisible()
                }
            },
        )

        completion {
            events += "stale"
            false
        }

        assertEquals(listOf("sasayaki", "stale"), events)
    }

    @Test
    fun sasayakiChapterLoadResetsStatisticsBaselineAfterSavingHiddenJumpTarget() {
        val events = mutableListOf<String>()
        val statistics = mapOf("session" to moe.antimony.hoshi.features.sync.Timestamped<moe.antimony.hoshi.epub.ReadingSession?>(1, moe.antimony.hoshi.epub.ReadingSession(0, 1, 12)))
        val target = ReaderChapterPosition(index = 3, progress = 0.75)

        val saved = readerSasayakiChapterLoadPosition(
            saveStatistics = {
                events += "flush"
                statistics
            },
            jumpToTarget = {
                events += "jump"
                target
            },
            resetStatisticsBaseline = {
                events += "reset"
            },
            saveReaderPosition = { position, savedStatistics ->
                events += "save ${position.index}:${position.progress} ${savedStatistics?.values?.single()?.value?.charactersRead}"
            },
        )

        assertEquals(target, saved)
        assertEquals(listOf("flush", "jump", "save 3:0.75 12", "reset"), events)
    }

    @Test
    fun sasayakiCueProgressUsesCueStartWithinTargetChapter() {
        val book = sasayakiProgressBook(chapterCount = 1_000)

        assertEquals(0.75, readerSasayakiCueProgress(book, sasayakiProgressCue(start = 750)), 0.0)
    }

    @Test
    fun sasayakiCueProgressClampsCueStartToChapterBounds() {
        val book = sasayakiProgressBook(chapterCount = 1_000)

        assertEquals(0.0, readerSasayakiCueProgress(book, sasayakiProgressCue(start = -1)), 0.0)
        assertEquals(1.0, readerSasayakiCueProgress(book, sasayakiProgressCue(start = 1_001)), 0.0)
    }

    @Test
    fun sasayakiCueProgressFallsBackWithoutUsableChapterMetadata() {
        val zeroCountBook = sasayakiProgressBook(chapterCount = 0)
        val missingInfoBook = zeroCountBook.copy(
            bookInfo = BookInfo(characterCount = 0, chapterInfo = emptyMap()),
        )
        val missingChapterCue = sasayakiProgressCue(start = 10).copy(chapterIndex = 2)

        assertEquals(0.0, readerSasayakiCueProgress(zeroCountBook, sasayakiProgressCue(start = 10)), 0.0)
        assertEquals(0.0, readerSasayakiCueProgress(missingInfoBook, sasayakiProgressCue(start = 10)), 0.0)
        assertEquals(0.0, readerSasayakiCueProgress(zeroCountBook, missingChapterCue), 0.0)
    }

    @Test
    fun sasayakiCrossChapterInitialProgressTargetsCueOnlyWhenJumpingBackward() {
        val book = sasayakiProgressBook(chapterCount = 1_000)
        val cue = sasayakiProgressCue(start = 750)

        assertEquals(
            0.75,
            readerSasayakiCrossChapterInitialProgress(book, cue, currentChapterIndex = 1),
            0.0,
        )
        assertEquals(
            0.0,
            readerSasayakiCrossChapterInitialProgress(book, cue, currentChapterIndex = -1),
            0.0,
        )
    }

    @Test
    fun readerNavigationInputIsIgnoredWhileWebViewIsRestoring() {
        val holder = stateHolder(initialIndex = 2)

        assertFalse(holder.canAcceptReaderNavigationInput())

        holder.markWebViewRestored()
        assertTrue(holder.canAcceptReaderNavigationInput())

        holder.goToNextChapter(lastIndex = 3)
        assertFalse(holder.canAcceptReaderNavigationInput())
    }

    @Test
    fun restoreLoadingUsesBehindChromeStaticSpinnerImmediatelyWhileRestoring() {
        val holder = stateHolder(initialIndex = 2)

        assertEquals(
            ReaderRestoreLoadingPresentation.BehindChromeStaticSpinner,
            readerRestoreLoadingPresentation(
                isWebViewRestoring = holder.isWebViewRestoring,
            ),
        )

        holder.markWebViewRestored()
        assertNull(
            readerRestoreLoadingPresentation(
                isWebViewRestoring = holder.isWebViewRestoring,
            ),
        )

        holder.goToNextChapter(lastIndex = 3)
        assertEquals(
            ReaderRestoreLoadingPresentation.BehindChromeStaticSpinner,
            readerRestoreLoadingPresentation(
                isWebViewRestoring = holder.isWebViewRestoring,
            ),
        )
    }

    @Test
    fun staleContinuousScrollProgressFromPreviousRestoreEpochIsIgnored() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        val oldEpoch = holder.webViewRestoreEpoch

        holder.goToNextChapter(lastIndex = 3)
        holder.markWebViewRestored()
        val staleProgress = holder.recordContinuousScrollProgress(0.72, oldEpoch)

        assertNull(staleProgress)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)
    }

    @Test
    fun staleContinuousScrollDisplayProgressFromPreviousRestoreEpochIsIgnored() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        val oldEpoch = holder.webViewRestoreEpoch

        holder.goToNextChapter(lastIndex = 3)
        holder.markWebViewRestored()
        val staleProgress = holder.recordContinuousScrollDisplayProgress(1.0, oldEpoch)

        assertNull(staleProgress)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)
    }

    @Test
    fun viewportResizeAfterInitialMeasureReloadsAtDisplayedPosition() {
        val holder = stateHolder(initialIndex = 1)

        holder.updateViewportSize(IntSize(800, 1200))
        holder.recordDisplayedProgress(0.35)
        holder.updateViewportSize(IntSize(900, 1200))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(IntSize(900, 1200), holder.webViewViewportSize)
    }

    @Test
    fun viewportResizeAfterInitialMeasureDismissesLookupPopups() {
        val holder = stateHolder(initialIndex = 1)
        holder.updateViewportSize(IntSize(800, 1200))
        holder.markWebViewRestored()
        holder.sasayakiLookupPause.pauseIfPlaying(true, true) { {} }
        holder.updateLookupPopups(listOf(lookupPopup()))

        holder.updateViewportSize(IntSize(900, 1200))

        assertTrue(holder.lookupPopups.isEmpty())
        assertFalse(holder.sasayakiLookupPause.pending)
    }

    @Test
    fun readerWebViewWaitsForMeasuredViewportBeforeInitialLoad() {
        assertFalse(readerWebViewReadyToLoad(IntSize.Zero))
        assertTrue(readerWebViewReadyToLoad(IntSize(800, 1200)))
    }

    @Test
    fun readerWebViewLoadKeyTracksContentReloadKey() {
        val baseSettings = ReaderSettings()
        val changedSettings = baseSettings.copy(fontSize = 28)
        val setupReloadKey = ReaderWebViewSetupReloadKey(
            initialProgress = 0.2,
            initialFragment = null,
            scanNonJapaneseText = false,
            contentLanguageProfile = ContentLanguageProfile.Default,
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
        )
        val viewportSize = IntSize(800, 1200)

        val baseLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = baseSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )
        val changedLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = changedSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )

        assertFalse(baseLoadKey == changedLoadKey)
    }

    @Test
    fun readerWebViewLoadKeyTracksChapterRestoreTarget() {
        val settings = ReaderSettings()
        val viewportSize = IntSize(800, 1200)

        val baseLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = settings.readerContentReloadKey(),
            readerSetupReloadKey = ReaderWebViewSetupReloadKey(
                initialProgress = 0.2,
                initialFragment = null,
                scanNonJapaneseText = false,
                contentLanguageProfile = ContentLanguageProfile.Default,
                fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
            ),
            webViewViewportSize = viewportSize,
        )
        val changedLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = settings.readerContentReloadKey(),
            readerSetupReloadKey = ReaderWebViewSetupReloadKey(
                initialProgress = 0.6,
                initialFragment = null,
                scanNonJapaneseText = false,
                contentLanguageProfile = ContentLanguageProfile.Default,
                fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
            ),
            webViewViewportSize = viewportSize,
        )

        assertFalse(baseLoadKey == changedLoadKey)
    }

    @Test
    fun readerWebViewRestoreTokenTracksRestoreEpochEvenWhenLoadKeyIsUnchanged() {
        val loadKey = "https://appassets.androidplatform.net/epub/chapter.xhtml#reader-load"

        val baseToken = readerWebViewRestoreToken(loadKey, restoreEpoch = 1)
        val changedToken = readerWebViewRestoreToken(loadKey, restoreEpoch = 2)

        assertFalse(baseToken == changedToken)
    }

    @Test
    fun readerWebViewLoadKeyIgnoresThemeOnlySettings() {
        val baseSettings = ReaderSettings(theme = ReaderTheme.Light)
        val changedSettings = baseSettings.copy(theme = ReaderTheme.Dark)
        val setupReloadKey = ReaderWebViewSetupReloadKey(
            initialProgress = 0.2,
            initialFragment = null,
            scanNonJapaneseText = false,
            contentLanguageProfile = ContentLanguageProfile.Default,
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
        )
        val viewportSize = IntSize(800, 1200)

        val baseLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = baseSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )
        val changedLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = changedSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )

        assertEquals(baseLoadKey, changedLoadKey)
    }

    @Test
    fun sasayakiTopToggleSpaceIsReservedBeforeSidecarsAreParsed() {
        val root = createTempDirectory("hoshi-sasayaki-sidecar").toFile()
        try {
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings()))

            root.resolve("sasayaki_match.json").writeText("{}")
            root.resolve("sasayaki_playback.json").writeText("{}")

            assertTrue(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings()))
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings(enabled = false)))
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings(showReaderToggle = false)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncedLayoutSettingsReloadAtDisplayedPosition() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.35)
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(verticalPadding = 22))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(22, holder.effectiveSettings.verticalPadding)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(previousEpoch + 1, holder.webViewRestoreEpoch)
    }

    @Test
    fun removingVerticalBordersReloadsAtDisplayedPositionAndRestoresNormalSettings() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.35)
        val previousEpoch = holder.webViewRestoreEpoch
        val configured = ReaderSettings(
            verticalPadding = 12,
            topSafeAreaDp = 40,
            bottomSafeAreaDp = 44,
        )

        holder.syncSettings(configured.copy(removeVerticalBorders = true))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(previousEpoch + 1, holder.webViewRestoreEpoch)
        assertEquals(0, holder.effectiveSettings.effectiveVerticalPadding)
        assertEquals(10, holder.effectiveSettings.effectiveTopSafeAreaDp)
        assertEquals(6, holder.effectiveSettings.effectiveBottomSafeAreaDp)

        holder.markWebViewRestored()
        holder.syncSettings(configured.copy(removeVerticalBorders = false))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(12, holder.effectiveSettings.effectiveVerticalPadding)
        assertEquals(40, holder.effectiveSettings.effectiveTopSafeAreaDp)
        assertEquals(44, holder.effectiveSettings.effectiveBottomSafeAreaDp)
    }

    @Test
    fun nonContentSettingChangesDoNotReloadWebView() {
        val changes: List<Pair<String, (ReaderWebViewStateHolder) -> Unit>> = listOf(
            "chrome" to { it.syncSettings(ReaderSettings(showTitle = false, alwaysShowProgress = false, showProgressTop = false)) },
            "e-ink" to { it.syncSettings(ReaderSettings(eInkMode = true)) },
            "reveal speed" to { it.syncSettings(ReaderSettings(visualNovelRevealSpeed = 80)) },
            "popup" to {
                it.applySettings(
                    ReaderSettings(
                        popupWidth = 420,
                        popupHeight = 360,
                        popupActionBar = true,
                        popupFullWidth = true,
                        popupSwipeToDismiss = false,
                        popupSwipeThreshold = 45,
                    ),
                )
            },
        )

        changes.forEach { (name, change) ->
            val holder = stateHolder(initialIndex = 1)
            holder.markWebViewRestored()
            val previousEpoch = holder.webViewRestoreEpoch

            change(holder)

            assertFalse(name, holder.isWebViewRestoring)
            assertEquals(name, previousEpoch, holder.webViewRestoreEpoch)
        }
    }

    @Test
    fun readerContentReloadKeyChangesOnlyForContentSettings() {
        val base = ReaderSettings()
        val visualNovel = base.copy(viewMode = ReaderViewMode.VisualNovel)
        val ignored = listOf(
            base.copy(popupWidth = 420, popupHeight = 360, popupActionBar = true, popupFullWidth = true),
            base.copy(popupSwipeToDismiss = false, popupSwipeThreshold = 45),
            base.copy(topSafeAreaDp = 72),
            base.copy(bottomSafeAreaDp = 72),
            base.copy(theme = ReaderTheme.Dark),
            base.copy(systemLightSepia = true),
            base.copy(sepiaInvertInDark = true),
            base.copy(eInkMode = true),
            base.copy(theme = ReaderTheme.Custom, customBackgroundColor = 0xFF778899, customTextColor = 0xFFABCDEF),
        )
        ignored.forEach { assertEquals(it.toString(), base.readerContentReloadKey(), it.readerContentReloadKey()) }
        listOf(visualNovel.copy(visualNovelRevealSpeed = 0), visualNovel.copy(visualNovelClickAdvance = false)).forEach {
            assertEquals(it.toString(), visualNovel.readerContentReloadKey(), it.readerContentReloadKey())
        }

        val furiganaKeys = FuriganaMode.entries.map { base.copy(furiganaMode = it).readerContentReloadKey() }
        assertEquals(FuriganaMode.entries.size, furiganaKeys.toSet().size)
        listOf(
            base.copy(fontSize = 28),
            base.copy(verticalWriting = false),
            base.copy(paragraphSpacing = 1.2),
            visualNovel,
        ).forEach { assertNotEquals(it.toString(), base.readerContentReloadKey(), it.readerContentReloadKey()) }
        listOf(
            visualNovel.copy(visualNovelScreenMode = VisualNovelScreenMode.Sentences),
            visualNovel.copy(visualNovelSentencesPerScreen = 4),
            visualNovel.copy(visualNovelPreserveDialogueBubbles = true),
            visualNovel.copy(visualNovelMergeCrossScreenSasayakiCues = true),
        ).forEach { assertNotEquals(it.toString(), visualNovel.readerContentReloadKey(), it.readerContentReloadKey()) }
    }

    @Test
    fun readerAppearanceUpdateKeyTracksColorsDarkModeAndRevealSpeed() {
        fun key(settings: ReaderSettings, systemDark: Boolean = false) =
            readerAppearanceUpdateKey(settings, systemDark, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF)
        val custom = ReaderSettings(theme = ReaderTheme.Custom, customBackgroundColor = 0xFF112233, customTextColor = 0xFF5F8FFF)
        val visualNovel = ReaderSettings(viewMode = ReaderViewMode.VisualNovel, visualNovelRevealSpeed = 45)

        assertNotEquals(key(custom), key(custom.copy(customBackgroundColor = 0xFF778899)))
        assertEquals("#5f8fff", key(custom).textColorCss)
        assertEquals("#ff0000", key(custom.copy(customTextColor = 0xFFFF0000)).textColorCss)
        assertNotEquals(key(visualNovel), key(visualNovel.copy(visualNovelRevealSpeed = 90)))
        assertFalse(key(ReaderSettings(theme = ReaderTheme.Light), systemDark = true).darkMode)
        assertTrue(key(ReaderSettings(theme = ReaderTheme.Dark)).darkMode)
    }

    @Test
    fun focusModeChangesWithoutReloadingTheReaderContent() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.toggleFocusMode()
        assertTrue(holder.focusMode)
        holder.toggleFocusMode()
        assertFalse(holder.focusMode)

        holder.enterFocusModeForReaderInteraction()
        holder.enterFocusModeForReaderInteraction()
        assertTrue(holder.focusMode)

        holder.toggleFocusMode()
        holder.showReaderMenu()
        assertTrue(holder.beginReaderNavigationInput())
        assertTrue(holder.focusMode)
        assertFalse(holder.showReaderMenu)

        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun readerNavigationInputIsRejectedWhileWebViewIsRestoringWithoutChangingFocus() {
        val holder = stateHolder(initialIndex = 1)

        assertFalse(holder.beginReaderNavigationInput())

        assertFalse(holder.focusMode)
        assertTrue(holder.isWebViewRestoring)
    }

    @Test
    fun readerTapTogglesFocusModeOnlyWhenNoPopupIsVisible() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        assertTrue(holder.toggleFocusModeFromReaderTap(hasVisiblePopups = false))
        assertTrue(holder.focusMode)

        assertFalse(holder.toggleFocusModeFromReaderTap(hasVisiblePopups = true))
        assertTrue(holder.focusMode)

        assertTrue(holder.toggleFocusModeFromReaderTap(hasVisiblePopups = false))
        assertFalse(holder.focusMode)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun continuousScrollFocusTrackerOnlyStartsFocusForRealScrollGestures() {
        val tracker = ReaderContinuousScrollFocusTracker()

        tracker.onDown()
        assertFalse(tracker.onMove(2f, 2f))
        assertTrue(tracker.onMove(12f, 1f))
        assertFalse(tracker.onMove(20f, 1f))

        tracker.onCancel()
        assertFalse(tracker.onMove(2f, 2f))

        tracker.onDown()
        assertTrue(tracker.onMove(0f, -12f))
    }

    @Test
    fun enteringFocusModeClosesTheReaderMenu() {
        val holder = stateHolder()
        holder.showReaderMenu()
        assertTrue(holder.showReaderMenu)

        holder.enterFocusModeForReaderInteraction()

        assertTrue(holder.focusMode)
        assertFalse(holder.showReaderMenu)
    }

    @Test
    fun backNavigationExitsFocusModeBeforeClosingReader() {
        val holder = stateHolder()
        holder.enterFocusModeForReaderInteraction()

        assertFalse(holder.handleBackNavigation())
        assertFalse(holder.focusMode)

        assertTrue(holder.handleBackNavigation())
    }

    @Test
    fun replacingLookupStackKeepsBothNarrationSourcesPausedUntilFinalDismissal() {
        for (tts in listOf(false, true)) {
            val holder = stateHolder()
            val pauseState = if (tts) holder.ttsLookupPause else holder.sasayakiLookupPause
            var pauses = 0
            var resumes = 0
            pauseState.pauseIfPlaying(true, true) { pauses++; { resumes++ } }
            holder.updateLookupPopups(listOf(lookupPopup()))
            holder.updateLookupPopups(listOf(lookupPopup().copy(id = "replacement")))
            // A second selection must not replace the original resume even with a stale playing snapshot.
            pauseState.pauseIfPlaying(true, true) { pauses++; { resumes++ } }
            assertEquals(1, pauses)
            assertEquals(0, resumes)
            assertTrue(pauseState.pending)
            holder.updateLookupPopups(emptyList())
            holder.updateLookupPopups(emptyList())
            assertEquals(1, resumes)
            assertFalse(pauseState.pending)
        }
    }

    @Test
    fun lookupPauseClearingOrDisabledAutoPauseNeverResumesPlayback() {
        val holder = stateHolder()
        for (pauseState in listOf(holder.sasayakiLookupPause, holder.ttsLookupPause)) {
            var resumes = 0
            pauseState.pauseIfPlaying(true, false) { error("Already paused") }
            assertFalse(pauseState.pending)
            pauseState.pauseIfPlaying(false, true) { error("Auto-pause disabled") }
            assertFalse(pauseState.pending)
            pauseState.pauseIfPlaying(true, true) { { resumes++ } }
            pauseState.clear()
            holder.updateLookupPopups(emptyList())
            assertEquals(0, resumes)
            pauseState.pauseIfPlaying(true, true) { { resumes++ } }
            pauseState.pauseIfPlaying(false, true) { error("Auto-pause disabled") }
            holder.updateLookupPopups(emptyList())
            assertEquals(0, resumes)
        }
    }

    @Test
    fun menuActionsOpenOnlyTheRequestedSheet() {
        val holder = stateHolder()

        holder.showReaderMenu()
        holder.openAppearanceFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showAppearance)

        holder.dismissAppearance()
        holder.showReaderMenu()
        holder.openDisplaySettingsFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showDisplaySettings)

        holder.dismissDisplaySettings()
        holder.showReaderMenu()
        holder.openGoToFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showGoTo)

        holder.dismissGoTo()
        holder.showReaderMenu()
        holder.openSasayakiFromMenu(SasayakiSheetTab.Resources)
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showSasayaki)
    }

    @Test
    fun contentsTabSelectionSurvivesSheetDismissalWithinReaderSession() {
        val holder = stateHolder()

        assertEquals(ReaderGoToTab.Gallery, holder.selectedGoToTab)
        holder.openGoToFromMenu()
        assertEquals(ReaderGoToTab.Gallery, holder.selectedGoToTab)
        holder.selectGoToTab(ReaderGoToTab.Chapters)
        holder.dismissGoTo()
        holder.openGoToFromMenu()

        assertEquals(ReaderGoToTab.Chapters, holder.selectedGoToTab)

        holder.selectGoToTab(ReaderGoToTab.Search)
        holder.dismissGoTo()
        holder.openGoToFromMenu()

        assertEquals(ReaderGoToTab.Search, holder.selectedGoToTab)
    }

    @Test
    fun sasayakiFirstOpenUsesCurrentDefaultAndLaterOpensKeepExplicitSelection() {
        val holder = stateHolder()

        holder.openSasayakiFromMenu(SasayakiSheetTab.Chapters)
        assertEquals(SasayakiSheetTab.Chapters, holder.selectedSasayakiTab)

        holder.selectSasayakiTab(SasayakiSheetTab.Settings)
        holder.dismissSasayaki()
        holder.openSasayakiFromMenu(SasayakiSheetTab.Resources)

        assertEquals(SasayakiSheetTab.Settings, holder.selectedSasayakiTab)
    }

    @Test
    fun sasayakiDataChangesDoNotReplaceFirstOpenTabSelection() {
        val holder = stateHolder()

        holder.openSasayakiFromMenu(SasayakiSheetTab.Resources)
        holder.openSasayakiFromMenu(SasayakiSheetTab.Chapters)

        assertEquals(SasayakiSheetTab.Resources, holder.selectedSasayakiTab)
    }

    @Test
    fun newReaderSessionResetsRememberedSheetTabs() {
        val previousSession = stateHolder()
        previousSession.selectGoToTab(ReaderGoToTab.Highlights)
        previousSession.openSasayakiFromMenu(SasayakiSheetTab.Chapters)
        previousSession.selectSasayakiTab(SasayakiSheetTab.Settings)

        val newSession = stateHolder()

        assertEquals(ReaderGoToTab.Gallery, newSession.selectedGoToTab)
        newSession.openSasayakiFromMenu(SasayakiSheetTab.Resources)
        assertEquals(SasayakiSheetTab.Resources, newSession.selectedSasayakiTab)
    }

    @Test
    fun statisticsBlockingSheetStaysVisibleUntilEveryOpenSheetIsDismissed() {
        val holder = stateHolder()

        assertFalse(holder.hasStatisticsBlockingSheet)
        holder.openAppearanceFromMenu()
        holder.openGoToFromMenu()
        assertTrue(holder.hasStatisticsBlockingSheet)

        holder.dismissAppearance()
        assertTrue(holder.hasStatisticsBlockingSheet)

        holder.dismissGoTo()
        assertFalse(holder.hasStatisticsBlockingSheet)
    }

    @Test
    fun readerMenuButtonTogglesMenuVisibility() {
        val holder = stateHolder()

        holder.toggleReaderMenu()
        assertTrue(holder.showReaderMenu)

        holder.toggleReaderMenu()
        assertFalse(holder.showReaderMenu)
    }

    @Test
    fun searchLandingWaitsForMatchingRestoreAndIsConsumedOnce() {
        val holder = stateHolder()
        holder.markWebViewRestored()
        val target = ReaderChapterPosition(1, 0.5)
        holder.jumpToSearchResult(target, offset = 20, length = 3)
        val epoch = holder.webViewRestoreEpoch
        assertNull(holder.takeSearchHighlight(1, epoch))
        holder.markWebViewRestored()
        assertNull(holder.takeSearchHighlight(0, epoch))
        assertNull(holder.takeSearchHighlight(1, epoch - 1))
        assertEquals(ReaderSearchHighlight(1, 20, 3), holder.takeSearchHighlight(1, epoch))
        assertNull(holder.takeSearchHighlight(1, epoch))
    }

    @Test
    fun newerSearchAndOrdinaryNavigationInvalidatePendingLanding() {
        val holder = stateHolder()
        holder.markWebViewRestored()
        holder.jumpToSearchResult(ReaderChapterPosition(0, 0.3), 3, 1)
        val oldEpoch = holder.webViewRestoreEpoch
        holder.jumpToSearchResult(ReaderChapterPosition(0, 0.6), 6, 2)
        holder.markWebViewRestored()
        assertNull(holder.takeSearchHighlight(0, oldEpoch))
        assertEquals(ReaderSearchHighlight(0, 6, 2), holder.takeSearchHighlight(0, holder.webViewRestoreEpoch))
        holder.jumpToSearchResult(ReaderChapterPosition(0, 0.8), 8, 1)
        holder.navigateBackInJumpHistory()
        holder.markWebViewRestored()
        assertNull(holder.takeSearchHighlight(0, holder.webViewRestoreEpoch))
        holder.navigateForwardInJumpHistory()
        holder.markWebViewRestored()
        assertNull(holder.takeSearchHighlight(0, holder.webViewRestoreEpoch))
    }

    @Test
    fun samePositionSearchStillRestoresWithoutDuplicatingHistory() {
        val holder = stateHolder(initialProgress = 0.4)
        holder.markWebViewRestored()
        val epoch = holder.webViewRestoreEpoch
        holder.jumpToSearchResult(ReaderChapterPosition(0, 0.4), 4, 0)
        assertTrue(holder.isWebViewRestoring)
        assertTrue(holder.webViewRestoreEpoch > epoch)
        assertNull(holder.backTargetPosition)
        holder.markWebViewRestored()
        assertEquals(ReaderSearchHighlight(0, 4, 0), holder.takeSearchHighlight(0, holder.webViewRestoreEpoch))
    }

    private fun stateHolder(
        initialIndex: Int = 0,
        initialProgress: Double = 0.0,
    ): ReaderWebViewStateHolder =
        ReaderWebViewStateHolder(
            initialSettings = ReaderSettings(),
            initialPosition = ReaderChapterPosition(
                index = initialIndex,
                progress = initialProgress,
            ),
        )

    private fun sasayakiProgressBook(chapterCount: Int): EpubBook {
        val chapter = EpubChapter(
            id = "chapter-1",
            href = "chapter-1.xhtml",
            mediaType = "application/xhtml+xml",
            html = "<p>chapter</p>",
        )
        return EpubBook(
            title = "Book",
            chapters = listOf(chapter),
            bookInfo = BookInfo(
                characterCount = chapterCount,
                chapterInfo = mapOf(
                    chapter.href to BookInfo.ChapterInfo(
                        spineIndex = 0,
                        currentTotal = 0,
                        chapterCount = chapterCount,
                    ),
                ),
            ),
        )
    }

    private fun sasayakiProgressCue(start: Int): SasayakiMatch =
        SasayakiMatch(
            id = "cue",
            startTime = 10.0,
            endTime = 12.0,
            text = "cue",
            chapterIndex = 0,
            start = start,
            length = 3,
        )

    private fun lookupPopup(): LookupPopupItem =
        LookupPopupItem(
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "corner",
                    sentence = "corner",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 50.0, height = 24.0),
                    normalizedOffset = 0,
                ),
                results = emptyList(),
                isVertical = false,
            ),
        )
}
