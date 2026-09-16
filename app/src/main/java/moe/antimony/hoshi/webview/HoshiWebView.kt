package moe.antimony.hoshi.webview

import android.os.Build
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

internal interface HoshiWebViewSettings {
    var javaScriptEnabled: Boolean
    var domStorageEnabled: Boolean
    var allowFileAccess: Boolean
    var allowContentAccess: Boolean
    var minimumFontSize: Int
    var forceDarkAllowed: Boolean
    var algorithmicDarkeningAllowed: Boolean
}

internal fun HoshiWebViewSettings.applyHoshiWebViewSecurityDefaults() {
    javaScriptEnabled = true
    domStorageEnabled = false
    allowFileAccess = false
    allowContentAccess = false
    forceDarkAllowed = false
    algorithmicDarkeningAllowed = false
}

internal fun HoshiWebViewSettings.applyHoshiReaderTextDefaults() {
    applyHoshiReaderTextDefaults(adaptiveFurigana = false)
}

internal fun HoshiWebViewSettings.applyHoshiReaderTextDefaults(adaptiveFurigana: Boolean) {
    // Adaptive furigana needs CSS-sized ruby annotations below WebView's former 8px floor.
    minimumFontSize = if (adaptiveFurigana) 1 else 8
}

fun WebView.applyHoshiWebViewSecurityDefaults() {
    AndroidHoshiWebViewSettings(this).applyHoshiWebViewSecurityDefaults()
    disableNativeOverscrollStretch()
}

fun WebView.applyHoshiReaderTextDefaults(adaptiveFurigana: Boolean = false) {
    AndroidHoshiWebViewSettings(this).applyHoshiReaderTextDefaults(adaptiveFurigana)
}

fun WebView.disableNativeOverscrollStretch() {
    overScrollMode = View.OVER_SCROLL_NEVER
}

private class AndroidHoshiWebViewSettings(
    private val webView: WebView,
) : HoshiWebViewSettings {
    private val settings: WebSettings
        get() = webView.settings

    override var javaScriptEnabled: Boolean
        get() = settings.javaScriptEnabled
        set(value) {
            settings.javaScriptEnabled = value
        }

    override var domStorageEnabled: Boolean
        get() = settings.domStorageEnabled
        set(value) {
            settings.domStorageEnabled = value
        }

    override var allowFileAccess: Boolean
        get() = settings.allowFileAccess
        set(value) {
            settings.allowFileAccess = value
        }

    override var allowContentAccess: Boolean
        get() = settings.allowContentAccess
        set(value) {
            settings.allowContentAccess = value
        }

    override var minimumFontSize: Int
        get() = settings.minimumFontSize
        set(value) {
            settings.minimumFontSize = value
        }

    override var forceDarkAllowed: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && webView.isForceDarkAllowed
        set(value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.isForceDarkAllowed = value
            }
        }

    override var algorithmicDarkeningAllowed: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && settings.isAlgorithmicDarkeningAllowed
        set(value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                settings.isAlgorithmicDarkeningAllowed = value
            }
        }
}
