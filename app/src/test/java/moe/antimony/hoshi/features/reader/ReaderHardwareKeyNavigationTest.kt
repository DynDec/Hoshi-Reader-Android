package moe.antimony.hoshi.features.reader

import android.view.KeyEvent
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction.PopupTermNavigation
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction.ReaderNavigation
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction.SasayakiSeekBackward
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction.SasayakiSeekForward
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction.TtsSentenceNavigation
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection.Backward
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection.Forward
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderHardwareKeyNavigationTest {
    private val pages = ReaderSettings(volumeKeysTurnPages = true)
    private val seek = ReaderSettings(volumeKeysSeekSasayaki = true)
    private val popupTerms = ReaderSettings(volumeKeysNavigatePopupTerms = true)
    private val everything = ReaderSettings(
        volumeKeysTurnPages = true,
        volumeKeysSeekSasayaki = true,
        volumeKeysNavigatePopupTerms = true,
    )

    private data class Case(
        val name: String,
        val settings: ReaderSettings,
        val expected: ReaderHardwareKeyAction?,
        val consumed: Boolean = expected != null,
        val code: Int = KeyEvent.KEYCODE_VOLUME_DOWN,
        val action: Int = KeyEvent.ACTION_DOWN,
        val repeat: Int = 0,
        val sasayakiEnabled: Boolean = false,
        val audio: Boolean = false,
        val popup: Boolean = false,
        val tts: Boolean = false,
    )

    private fun up(case: Case) = case.copy(code = KeyEvent.KEYCODE_VOLUME_UP)
    private fun withAudio(case: Case) = case.copy(sasayakiEnabled = true, audio = true)

    @Test
    fun keyEventsMapToTheHighestPriorityEnabledAction() {
        val reversed = { settings: ReaderSettings -> settings.copy(reverseVolumeKeyDirection = true) }
        val cases = listOf(
            // Page keys always navigate the reader, once per press, regardless of volume settings.
            Case("page down", reversed(ReaderSettings()), ReaderNavigation(Forward), code = KeyEvent.KEYCODE_PAGE_DOWN),
            Case("page up", reversed(ReaderSettings()), ReaderNavigation(Backward), code = KeyEvent.KEYCODE_PAGE_UP),
            Case("page key up", pages, null, consumed = true, code = KeyEvent.KEYCODE_PAGE_DOWN, action = KeyEvent.ACTION_UP),
            Case("page key repeat", pages, null, consumed = true, code = KeyEvent.KEYCODE_PAGE_DOWN, repeat = 1),

            // Volume page turns.
            Case("volume disabled", ReaderSettings(), null),
            up(Case("volume up disabled", ReaderSettings(), null)),
            Case("volume down", pages, ReaderNavigation(Forward)),
            up(Case("volume up", pages, ReaderNavigation(Backward))),
            Case("volume down reversed", reversed(pages), ReaderNavigation(Backward)),
            up(Case("volume up reversed", reversed(pages), ReaderNavigation(Forward))),
            Case("volume repeat", pages, ReaderNavigation(Forward), repeat = 3),
            Case("volume key up", pages, null, consumed = true, action = KeyEvent.ACTION_UP),

            // Audiobook seeking requires an enabled audiobook with loaded audio.
            withAudio(Case("seek disabled", ReaderSettings(), null, repeat = 1)),
            withAudio(Case("seek down", seek, SasayakiSeekForward)),
            withAudio(up(Case("seek up", seek, SasayakiSeekBackward))),
            withAudio(up(Case("seek up reversed", reversed(seek), SasayakiSeekForward))),
            withAudio(Case("seek down reversed", reversed(seek), SasayakiSeekBackward)),
            withAudio(up(Case("seek repeat", seek, SasayakiSeekBackward, repeat = 4))),
            withAudio(up(Case("seek key up", seek, null, consumed = true, action = KeyEvent.ACTION_UP))),
            up(Case("seek without audiobook", seek, null, audio = true)),
            up(Case("seek without audio", seek, null, sasayakiEnabled = true)),
            withAudio(up(Case("seek beats page turns", seek.copy(volumeKeysTurnPages = true), SasayakiSeekBackward))),
            up(Case("page turns without audio", seek.copy(volumeKeysTurnPages = true), ReaderNavigation(Backward), sasayakiEnabled = true)),

            // Popup term navigation wins whenever a popup is open.
            withAudio(Case("popup beats seek", everything, PopupTermNavigation(PopupTermNavigationDirection.Next), popup = true)),
            withAudio(up(Case("popup up", everything, PopupTermNavigation(PopupTermNavigationDirection.Previous), popup = true))),
            up(Case("popup reversed", reversed(popupTerms), PopupTermNavigation(PopupTermNavigationDirection.Next), popup = true)),
            Case("popup repeat", popupTerms, PopupTermNavigation(PopupTermNavigationDirection.Next), repeat = 3, popup = true),
            Case("popup key up", popupTerms, null, consumed = true, action = KeyEvent.ACTION_UP, popup = true),
            Case("no popup", pages.copy(volumeKeysNavigatePopupTerms = true), ReaderNavigation(Forward)),
            Case("popup terms disabled", pages, ReaderNavigation(Forward), popup = true),

            // TTS sentence navigation shares the audiobook preference and needs a started session.
            Case("tts", everything, TtsSentenceNavigation(Forward), tts = true),
            up(Case("tts up", everything, TtsSentenceNavigation(Backward), tts = true)),
            up(Case("tts reversed", reversed(everything), TtsSentenceNavigation(Forward), tts = true)),
            Case("tts repeat", everything, TtsSentenceNavigation(Forward), repeat = 4, tts = true),
            Case("tts key up", everything, null, consumed = true, action = KeyEvent.ACTION_UP, tts = true),
            Case("tts not started", everything, ReaderNavigation(Forward)),
            Case("tts not started without page turns", seek, null),
            Case("tts without shared preference", pages, ReaderNavigation(Forward), tts = true),
            Case("tts disabled", ReaderSettings(), null, tts = true),
            withAudio(Case("audiobook beats tts", everything, SasayakiSeekForward, tts = true)),
            Case("popup beats tts", everything, PopupTermNavigation(PopupTermNavigationDirection.Next), popup = true, tts = true),
        )

        cases.forEach { case ->
            val result = readerHardwareKeyEventForKeyEvent(
                keyCode = case.code,
                action = case.action,
                repeatCount = case.repeat,
                settings = case.settings,
                sasayakiEnabled = case.sasayakiEnabled,
                hasSasayakiAudio = case.audio,
                hasLookupPopup = case.popup,
                hasStartedTts = case.tts,
            )
            assertEquals("${case.name}: action", case.expected, result.action)
            if (case.code != KeyEvent.KEYCODE_PAGE_DOWN && case.code != KeyEvent.KEYCODE_PAGE_UP) {
                assertEquals("${case.name}: consumed", case.consumed, result.consumed)
            }
        }
    }
}
