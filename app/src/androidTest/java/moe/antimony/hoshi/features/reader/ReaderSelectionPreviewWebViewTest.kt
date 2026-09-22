package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSelectionPreviewWebViewTest {
    @Test
    fun packagedPreviewPreservesSelectionOptionsAndTracksAppearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = ReaderWebAssets.load(context)
        val loaded = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        var result = JSONObject()
        var webView: WebView? = null
        try {
            instrumentation.runOnMainSync {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            loaded.countDown()
                        }
                    }
                    loadDataWithBaseURL(
                        "https://appassets.androidplatform.net/",
                        """
                        <!doctype html><html><head><script>${assets.selectionJs}</script></head>
                        <body><ruby>漢<rt>かん</rt></ruby>字</body></html>
                        """.trimIndent(),
                        "text/html", "utf-8", null,
                    )
                }
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                checkNotNull(webView).evaluateJavascript(
                    """
                    (() => {
                        const selection = window.hoshiSelection;
                        selection.configure({ bridge: 'android-reader', scaleRects: false });
                        selection.selectionRects = count => [{ x: count, y: 2, width: 3, height: 4 }];
                        ${readerSelectionPreviewConfigurationScript(false, true, true)}
                        selection.options.previewSelection(selection);
                        const pending = window.__hoshiPendingReaderSelectionPreview;
                        selection.options.clearSelectionPreview();
                        const pendingCleared = window.__hoshiPendingReaderSelectionPreview === null;
                        let shown = null, cleared = false;
                        window.hoshiReaderPopupHost = {
                            previewRootSelection: payload => { shown = payload; },
                            clearRootSelectionPreview: () => { cleared = true; }
                        };
                        ${readerSelectionPreviewConfigurationScript(true, false, false)}
                        selection.options.previewSelection(selection);
                        selection.options.clearSelectionPreview();
                        return {
                            pending, shown, pendingCleared, cleared,
                            bridge: selection.options.bridge,
                            scaleRects: selection.options.scaleRects
                        };
                    })();
                    """.trimIndent(),
                ) { value ->
                    result = JSONObject(value)
                    evaluated.countDown()
                }
            }
            assertTrue(evaluated.await(10, TimeUnit.SECONDS))
            assertEquals("android-reader", result.getString("bridge"))
            assertFalse(result.getBoolean("scaleRects"))
            assertTrue(result.getBoolean("pendingCleared"))
            assertTrue(result.getBoolean("cleared"))
            val pending = result.getJSONObject("pending")
            assertFalse(pending.getBoolean("darkMode"))
            assertTrue(pending.getBoolean("eInkMode"))
            assertTrue(pending.getBoolean("verticalWriting"))
            val shown = result.getJSONObject("shown")
            assertTrue(shown.getBoolean("darkMode"))
            assertFalse(shown.getBoolean("eInkMode"))
            assertFalse(shown.getBoolean("verticalWriting"))
            assertEquals(1, shown.getJSONArray("rects").getJSONObject(0).getInt("x"))
        } finally {
            instrumentation.runOnMainSync { webView?.destroy() }
        }
    }
}
