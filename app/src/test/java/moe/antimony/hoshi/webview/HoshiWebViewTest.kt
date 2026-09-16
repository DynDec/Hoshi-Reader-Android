package moe.antimony.hoshi.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoshiWebViewTest {
    @Test
    fun webViewDefaultsDisablePlatformDarkeningForAppControlledThemes() {
        val settings = FakeHoshiWebViewSettings()

        settings.applyHoshiWebViewSecurityDefaults()

        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.forceDarkAllowed)
        assertFalse(settings.algorithmicDarkeningAllowed)
    }

    @Test
    fun readerTextDefaultsUseEightPixelFloorWhenAdaptiveFuriganaIsDisabled() {
        val settings = FakeHoshiWebViewSettings()

        settings.applyHoshiReaderTextDefaults(adaptiveFurigana = false)

        assertEquals(8, settings.minimumFontSize)
    }

    @Test
    fun readerTextDefaultsUseOnePixelFloorWhenAdaptiveFuriganaIsEnabled() {
        val settings = FakeHoshiWebViewSettings()

        settings.applyHoshiReaderTextDefaults(adaptiveFurigana = true)

        assertEquals(1, settings.minimumFontSize)
    }

    private class FakeHoshiWebViewSettings : HoshiWebViewSettings {
        override var javaScriptEnabled: Boolean = false
        override var domStorageEnabled: Boolean = true
        override var allowFileAccess: Boolean = true
        override var allowContentAccess: Boolean = true
        override var minimumFontSize: Int = 8
        override var forceDarkAllowed: Boolean = true
        override var algorithmicDarkeningAllowed: Boolean = true
    }
}
