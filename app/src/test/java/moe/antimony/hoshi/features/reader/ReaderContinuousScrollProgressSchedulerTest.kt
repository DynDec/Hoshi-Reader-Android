package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderContinuousScrollProgressSchedulerTest {
    @Test
    fun groupedScrollReportsOriginalIdentityAndRejectsCancelledEvaluation() {
        val scheduler = ReaderContinuousScrollProgressScheduler(nowMillis = { 1_000L })
        val evaluations = mutableListOf<(String?) -> Unit>()
        val displayed = mutableListOf<ReaderChapterPosition>()
        val saved = mutableListOf<ReaderChapterPosition>()
        var idle: Runnable? = null
        fun scroll(epoch: Int) = scheduler.onScrollChanged(
            isRestoring = false, restoreEpoch = epoch,
            evaluateProgress = { evaluations += it },
            onProgressChanged = { _, _ -> error("Source payload must retain chapter identity") },
            onProgressIdle = { _, _ -> error("Source payload must retain chapter identity") },
            onSourcePositionChanged = { position, callbackEpoch, save ->
                assertEquals(epoch, callbackEpoch)
                if (save) saved += position else displayed += position
            },
            onClearLookupPopup = {}, postDelayed = { _, _ -> }, removeCallback = {},
            cancelIdleSave = { idle = null }, scheduleIdleSave = { callback, _ -> idle = callback },
        )
        scroll(1)
        evaluations.removeAt(0)("\"{\\\"index\\\":2,\\\"progress\\\":0.75}\"")
        assertEquals(listOf(ReaderChapterPosition(2, 0.75)), displayed)
        idle!!.run()
        assertEquals(displayed, saved)
        scheduler.reset {}
        scroll(2)
        val stale = evaluations.removeAt(0)
        scheduler.reset {}
        stale("{\"index\":1,\"progress\":0.9}")
        assertEquals(1, displayed.size)
        assertEquals(null, continuousSourcePositionResult("{\"index\":-1,\"progress\":0.2}"))
        assertEquals(null, continuousSourcePositionResult("null"))
        assertEquals(ReaderChapterPosition(1, 1.0), continuousSourcePositionResult("{\"index\":1,\"progress\":4}"))
    }
    @Test
    fun scrollEventsWhileProgressIsRunningRequestOnlyOneFollowUpEvaluation() {
        var now = 1_000L
        val scheduler = ReaderContinuousScrollProgressScheduler(nowMillis = { now })
        val evaluations = mutableListOf<(String?) -> Unit>()
        val displayed = mutableListOf<Double>()
        val saved = mutableListOf<Double>()
        val throttleCallbacks = mutableListOf<Runnable>()
        var idleSaveCallback: Runnable? = null

        fun scroll() {
            scheduler.onScrollChanged(
                isRestoring = false,
                restoreEpoch = 3,
                evaluateProgress = { callback -> evaluations += callback },
                onProgressChanged = { progress, _ -> displayed += progress },
                onProgressIdle = { progress, _ -> saved += progress },
                onClearLookupPopup = {},
                postDelayed = { callback, _ -> throttleCallbacks += callback },
                removeCallback = { callback -> throttleCallbacks.remove(callback) },
                cancelIdleSave = { idleSaveCallback = null },
                scheduleIdleSave = { callback, _ -> idleSaveCallback = callback },
            )
        }

        scroll()
        assertEquals(1, evaluations.size)

        repeat(6) {
            now += 50L
            scroll()
        }

        assertEquals(1, evaluations.size)

        evaluations.removeAt(0)("0.25")
        assertEquals(listOf(0.25), displayed)
        assertEquals(1, evaluations.size)

        evaluations.removeAt(0)("0.5")
        assertEquals(listOf(0.25, 0.5), displayed)
        assertEquals(0, evaluations.size)
        assertEquals(emptyList<Double>(), saved)

        idleSaveCallback?.run()
        assertEquals(listOf(0.5), saved)
    }

    @Test
    fun scrollWithinThrottleSchedulesOneDelayedProgressEvaluation() {
        var now = 1_000L
        val scheduler = ReaderContinuousScrollProgressScheduler(nowMillis = { now })
        val evaluations = mutableListOf<(String?) -> Unit>()
        val displayed = mutableListOf<Double>()
        val throttleCallbacks = mutableListOf<Runnable>()
        var idleSaveCallback: Runnable? = null

        fun scroll() {
            scheduler.onScrollChanged(
                isRestoring = false,
                restoreEpoch = 4,
                evaluateProgress = { callback -> evaluations += callback },
                onProgressChanged = { progress, _ -> displayed += progress },
                onProgressIdle = { _, _ -> },
                onClearLookupPopup = {},
                postDelayed = { callback, _ -> throttleCallbacks += callback },
                removeCallback = { callback -> throttleCallbacks.remove(callback) },
                cancelIdleSave = { idleSaveCallback = null },
                scheduleIdleSave = { callback, _ -> idleSaveCallback = callback },
            )
        }

        scroll()
        evaluations.removeAt(0)("0.1")

        now += 20L
        scroll()
        scroll()

        assertEquals(0, evaluations.size)
        assertEquals(1, throttleCallbacks.size)
        assertEquals(null, idleSaveCallback)

        now += 30L
        throttleCallbacks.removeAt(0).run()
        assertEquals(1, evaluations.size)

        evaluations.removeAt(0)("0.2")
        assertEquals(listOf(0.1, 0.2), displayed)
    }
}
