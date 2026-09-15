package moe.antimony.hoshi.features.reader

import android.app.Instrumentation
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPaginationWebViewTest {
    @Test
    fun progressIncludesTextAtCurrentPageStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val readerWebAssets = ReaderWebAssets.load(context)
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        var progress = Double.NaN
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                null,
                """
                <!doctype html>
                <html lang="ja">
                    <head>${ReaderPaginationScripts.shellScript(initialProgress = 0.0, assets = readerWebAssets)}</head>
                    <body>一二三四五六七八九十</body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }

        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.evaluateJavascript(progressAtPageStartScript()) { result ->
                progress = result.toDouble()
                scriptFinished.countDown()
            }
        }

        assertTrue(scriptFinished.await(5, TimeUnit.SECONDS))
        assertEquals(0.5, progress, 0.000001)
    }

    @Test
    fun nativeSelectionLocksPagedScrollPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val readerWebAssets = ReaderWebAssets.load(context)
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        var result = JSONObject()
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                null,
                """
                <!doctype html>
                <html lang="ja">
                    <head>${ReaderPaginationScripts.shellScript(initialProgress = 0.0, assets = readerWebAssets)}</head>
                    <body>一二三四五六七八九十</body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }

        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.evaluateJavascript(nativeSelectionScrollLockScript()) { value ->
                result = JSONObject(value)
                scriptFinished.countDown()
            }
        }

        assertTrue(scriptFinished.await(5, TimeUnit.SECONDS))
        assertFalse(result.optBoolean("missingSetNativeSelectionActive"))
        assertFalse(result.optBoolean("missingHandlePagedBodyScroll"))
        assertEquals(100, result.getInt("scrollTop"))
        assertEquals(100, result.getInt("lastPageScroll"))
    }

    @Test
    fun verticalRubyCalibrationFitsLongReadingsAndKeepsPaddingSymmetric() {
        listOf(1.65, 1.75).forEach { lineHeight ->
            val result = loadCalibrationMetrics(
                settings = ReaderSettings(
                    verticalWriting = true,
                    viewMode = ReaderViewMode.Paginated,
                    fontSize = 14,
                    horizontalPadding = 12,
                    lineHeight = lineHeight,
                    layoutAdvanced = true,
                ),
                waitForScale = true,
            )

            assertFuriganaScale(result.getDouble("scale"))
            assertRubyAdvanceWithinToleranceOrAtFloor(result)
            assertEquals(result.getDouble("paddingLeft"), result.getDouble("paddingRight"), 0.5)
            assertEquals(14.0, result.getDouble("rubyBaseFontSize"), 0.001)
            assertEquals(0, result.getInt("calibrationProbeCount"))
        }
    }

    @Test
    fun verticalRubyCalibrationNeverGoesBelowFloorAtTightLineHeight() {
        val result = loadCalibrationMetrics(
            settings = ReaderSettings(
                verticalWriting = true,
                viewMode = ReaderViewMode.Paginated,
                fontSize = 14,
                lineHeight = 1.0,
                layoutAdvanced = true,
            ),
            waitForScale = true,
            body = tightVerticalRubyBodyHtml(),
        )

        assertFuriganaScale(result.getDouble("scale"))
        assertTrue(result.getDouble("scale") >= 0.30)
        assertRubyAdvanceWithinToleranceOrAtFloor(result)
        assertEquals(14.0, result.getDouble("bodyLineHeight"), 0.001)
    }

    @Test
    fun horizontalInitializationLeavesDefaultRubyScale() {
        val result = loadCalibrationMetrics(
            settings = ReaderSettings(
                verticalWriting = false,
                viewMode = ReaderViewMode.Paginated,
                fontSize = 14,
                lineHeight = 1.75,
                layoutAdvanced = true,
            ),
            waitForScale = false,
        )

        assertEquals("", result.getString("inlineScale"))
        assertEquals(0.45, result.getDouble("computedScale"), 0.001)
    }
}

private fun loadCalibrationMetrics(
    settings: ReaderSettings,
    waitForScale: Boolean,
    body: String = verticalRubyBodyHtml(),
): JSONObject {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val assets = ReaderWebAssets.load(context)
    val pageLoaded = CountDownLatch(1)
    var webView: WebView? = null
    try {
        instrumentation.runOnMainSync {
            webView = WebView(context).apply {
                this.settings.javaScriptEnabled = true
                this.settings.minimumFontSize = 1
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pageLoaded.countDown()
                    }
                }
            }
            val css = ReaderContentStyles.styleTag(
                settings = settings,
                readerCssTemplate = assets.readerCss,
            )
            val script = ReaderPaginationScripts.shellScript(settings = settings, assets = assets)
            webView.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                """
                <!doctype html>
                <html lang="ja">
                    <head>$css$script</head>
                    <body>$body</body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }
        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))
        val activeWebView = checkNotNull(webView)
        if (waitForScale) {
            awaitJavascriptCondition(
                instrumentation,
                activeWebView,
                "document.documentElement.style.getPropertyValue('--hoshi-furigana-scale') !== ''",
            )
        } else {
            awaitJavascriptCondition(instrumentation, activeWebView, "window.hoshiReader?.didInitialize === true")
        }
        return JSONObject(evaluateJavascript(instrumentation, activeWebView, calibrationMetricsScript()))
    } finally {
        webView?.let { createdWebView -> instrumentation.runOnMainSync { createdWebView.destroy() } }
    }
}

private fun evaluateJavascript(
    instrumentation: Instrumentation,
    webView: WebView,
    script: String,
): String {
    val finished = CountDownLatch(1)
    var result = "null"
    instrumentation.runOnMainSync {
        webView.evaluateJavascript(script) {
            result = it ?: "null"
            finished.countDown()
        }
    }
    assertTrue(finished.await(5, TimeUnit.SECONDS))
    return result
}

private fun awaitJavascriptCondition(
    instrumentation: Instrumentation,
    webView: WebView,
    expression: String,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (evaluateJavascript(instrumentation, webView, "Boolean($expression)") == "true") return
        Thread.sleep(25)
    }
    throw AssertionError("Timed out waiting for WebView condition: $expression")
}

private fun assertFuriganaScale(scale: Double) {
    assertTrue(scale in 0.30..0.45)
    val gridIndex = ((scale - 0.30) / 0.005).roundToInt()
    assertEquals(scale, 0.30 + gridIndex * 0.005, 0.001)
}

private fun assertRubyAdvanceWithinToleranceOrAtFloor(result: JSONObject) {
    val minimumDelta = result.getDouble("minimumDelta")
    val selectedDelta = result.getDouble("selectedDelta")
    if (minimumDelta <= 0.5) {
        assertTrue(selectedDelta <= 0.5)
    } else {
        assertEquals(0.30, result.getDouble("scale"), 0.001)
    }
}

private fun verticalRubyBodyHtml(): String =
    """
    <p style="font-family: serif !important;">
        <ruby>漢<rt>かん</rt></ruby>短い読み。
    </p>
    <p style="font-family: sans-serif !important;">
        <ruby>語<rt>とてもながいふりがなを含む読みです</rt></ruby>長い読み。
    </p>
    """.trimIndent()

private fun tightVerticalRubyBodyHtml(): String =
    """
    <p style="font-family: serif !important;">
        <ruby>漢<rt>とてもとてもとてもとてもとてもながいふりがな</rt></ruby>
    </p>
    """.trimIndent()

private fun calibrationMetricsScript(): String =
    """
    (() => {
        const root = document.documentElement;
        const bodyStyle = getComputedStyle(document.body);
        const longReading = 'とてもながいふりがなを含む読みです';
        const baseStyleProperties = ['fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight', 'letterSpacing', 'wordSpacing'];
        function line(markup, scale, family) {
            const line = document.createElement('div');
            line.style.position = 'absolute';
            line.style.left = '-100000px';
            line.style.visibility = 'hidden';
            line.style.display = 'block';
            line.style.writingMode = 'vertical-rl';
            line.style.whiteSpace = 'nowrap';
            line.style.width = 'max-content';
            line.style.height = 'max-content';
            baseStyleProperties.forEach((property) => line.style[property] = bodyStyle[property]);
            if (family) line.style.fontFamily = family;
            line.innerHTML = markup;
            if (scale !== undefined) {
                line.querySelectorAll('rt').forEach((rt) => rt.style.setProperty('font-size', scale + 'em', 'important'));
            }
            document.body.appendChild(line);
            const width = line.getBoundingClientRect().width;
            const baseFontSize = parseFloat(getComputedStyle(line.querySelector('.ruby-base') || line).fontSize);
            line.remove();
            return { width, baseFontSize };
        }
        const plainMarkup = '<span>漢</span>';
        const rubyMarkup = '<ruby><span class="ruby-base">漢</span><rt>' + longReading + '</rt></ruby>';
        const serifPlain = line(plainMarkup, undefined, 'serif');
        const serifSelected = line(rubyMarkup, undefined, 'serif');
        const serifMinimum = line(rubyMarkup, 0.30, 'serif');
        const sansPlain = line(plainMarkup, undefined, 'sans-serif');
        const sansSelected = line(rubyMarkup, undefined, 'sans-serif');
        const sansMinimum = line(rubyMarkup, 0.30, 'sans-serif');
        const inlineScale = root.style.getPropertyValue('--hoshi-furigana-scale').trim();
        const computedScale = parseFloat(getComputedStyle(root).getPropertyValue('--hoshi-furigana-scale'));
        return {
            scale: parseFloat(inlineScale || computedScale),
            inlineScale,
            computedScale,
            selectedDelta: Math.max(
                Math.abs(serifSelected.width - serifPlain.width),
                Math.abs(sansSelected.width - sansPlain.width)
            ),
            minimumDelta: Math.max(
                Math.abs(serifMinimum.width - serifPlain.width),
                Math.abs(sansMinimum.width - sansPlain.width)
            ),
            paddingLeft: parseFloat(bodyStyle.paddingLeft) || 0,
            paddingRight: parseFloat(bodyStyle.paddingRight) || 0,
            bodyLineHeight: parseFloat(bodyStyle.lineHeight),
            rubyBaseFontSize: serifSelected.baseFontSize,
            calibrationProbeCount: document.querySelectorAll('.hoshi-furigana-calibration-probe').length
        };
    })();
    """.trimIndent()

private fun progressAtPageStartScript(): String =
    """
    (() => {
        window.hoshiReader.countCharsBeforeViewport = function(node) {
            return (node.textContent || '').indexOf('一二三四五六七八九十') >= 0 ? 5 : 0;
        };
        window.hoshiReader.getScrollContext = function() {
            return { vertical: true, scrollEl: { scrollTop: 100, scrollLeft: 0 }, pageSize: 100, maxScroll: 200 };
        };
        return window.hoshiReader.calculateProgress();
    })();
    """.trimIndent()

private fun nativeSelectionScrollLockScript(): String =
    """
    (() => {
        if (typeof window.hoshiReader.setNativeSelectionActive !== 'function') {
            return { missingSetNativeSelectionActive: true };
        }
        if (typeof window.hoshiReader.handlePagedBodyScroll !== 'function') {
            return { missingHandlePagedBodyScroll: true };
        }
        window.__scrollEl = { scrollTop: 100, scrollLeft: 0 };
        window.hoshiReader.getScrollContext = function() {
            return {
                vertical: true,
                scrollEl: window.__scrollEl,
                pageSize: 100,
                maxScroll: 400
            };
        };
        window.lastPageScroll = 100;
        window.hoshiReader.setNativeSelectionActive(true);
        window.__scrollEl.scrollTop = 200;
        window.hoshiReader.handlePagedBodyScroll();
        return {
            scrollTop: window.__scrollEl.scrollTop,
            lastPageScroll: window.lastPageScroll
        };
    })();
    """.trimIndent()
