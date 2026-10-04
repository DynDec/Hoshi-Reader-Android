package moe.antimony.hoshi.features.reader

import androidx.compose.ui.unit.IntSize
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the actual Kotlin-generated setup after navigation, including XHTML and composed SVG pages. */
class ContinuousReaderBrowserTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root = listOf(File("."), File("..")).first { File(it, "app/src/main/assets").isDirectory }.canonicalFile
    private val chromium = listOfNotNull(System.getenv("HOSHI_TEST_CHROMIUM"),
        "C:/Program Files/Google/Chrome/Application/chrome.exe",
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe").firstOrNull { File(it).isFile }
    private fun asset(path: String) = File(root, "app/src/main/assets/hoshi-web/$path").readText()
    private val assets by lazy {
        ReaderWebAssets(
            languageJapaneseJs = asset("shared/language-ja.js"),
            selectionJapaneseJs = asset("shared/selection-ja.js"),
            selectionEnglishJs = asset("shared/selection-en.js"),
            selectionJs = asset("shared/selection.js") + "\n" + asset("reader/reader-selection-preview.js"),
            readerPaginatedJs = asset("reader/reader-paginated.js"), readerContinuousJs = asset("reader/reader-continuous.js"),
            readerVisualNovelJs = asset("reader/reader-visual-novel.js"), readerViewportJs = asset("reader/reader-viewport.js"),
            readerTextSemanticsJs = asset("reader/reader-text-semantics.js"), readerDomTextJs = asset("reader/reader-dom-text.js"),
            readerMediaSemanticsJs = asset("reader/reader-media-semantics.js"), readerLayoutSemanticsJs = asset("reader/reader-layout-semantics.js"),
            readerVnContentStreamJs = asset("reader/reader-vn-content-stream.js"), readerVnRangeMapJs = asset("reader/reader-vn-range-map.js"),
            readerVnSelectionProjectionJs = asset("reader/reader-vn-selection-projection.js"), readerSasayakiJs = asset("reader/reader-sasayaki.js"),
            highlightsJs = asset("reader/highlights.js"), readerCss = asset("reader/reader.css"),
            readerContinuousGroupJs = asset("reader/reader-continuous-group.js"),
        )
    }
    private fun fixture(): EpubBook {
        val image = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(
            "<svg xmlns='http://www.w3.org/2000/svg' width='1266' height='1800'><rect width='1266' height='1800' fill='green'/></svg>".toByteArray())
        val svg = "<div class='main'><svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='100%' height='100%' viewBox='0 0 1266 1800'><image width='1266' height='1800' xlink:href='$image'/></svg></div>"
        val content = listOf("<p>" + "Alpha猫 ".repeat(100) + "</p>", svg, "<p>" + "Beta犬 ".repeat(100) + "</p>")
        val css = ".vrtl{writing-mode:vertical-rl}.p-image{writing-mode:vertical-rl;font-size:2em}.main{display:block;width:auto;height:auto;margin:0;padding:0}"
        return EpubBook("Browser fixture", chapters = content.mapIndexed { index, body ->
            EpubChapter("c$index", "text/c$index.xhtml", "application/xhtml+xml",
                "<html xmlns='http://www.w3.org/1999/xhtml' class='vrtl'><head><link rel='stylesheet' href='../style.css'/></head><body class='${if (index == 1) "p-image" else "p-text"}'>$body</body></html>")
        }, navigationDocumentHref = "nav.xhtml", resources = mapOf(
            "style.css" to EpubResource("text/css", css.toByteArray()),
            "nav.xhtml" to EpubResource("application/xhtml+xml", "<nav epub:type='toc'><a href='text/c0.xhtml'>Section</a></nav>".toByteArray()),
        ))
    }
    private fun runBrowser(book: EpubBook, sample: Boolean = false) {
        assumeTrue("Chromium unavailable", chromium != null)
        val resourceBridge = ReaderWebResourceBridge(book) { null }
        fun embeddedResources(html: String): String {
            // Keep production URL resolution assertions, then embed resources for the isolated browser.
            return Regex("https://appassets\\.androidplatform\\.net/epub/[^\\s\"'<>)]*").replace(html) { match ->
                val resource = resourceBridge.resourceForUrl(match.value) ?: error("Missing resource ${match.value}")
                assertEquals("Resource ${match.value}", 200, resource.statusCode)
                "data:${resource.mediaType};base64," + Base64.getEncoder().encodeToString(resource.data)
            }
        }
        val fixtures = buildJsonArray {
            for (vertical in listOf(false, true)) for (merge in listOf(false, true)) {
                val allGroups = ContinuousReadingGroups.build(book, merge)
                val groups = if (!sample) allGroups else {
                    fun text(group: ContinuousReadingGroup) = group.indices.any { ReaderChapterContent.classify(book.chapterHtml(book.chapters[it])).hasText }
                    fun images(group: ContinuousReadingGroup) = group.indices.any { ReaderChapterContent.classify(book.chapterHtml(book.chapters[it])).imageOnly }
                    listOfNotNull(allGroups.firstOrNull { text(it) }, allGroups.firstOrNull { text(it) && images(it) },
                        allGroups.firstOrNull { images(it) && !text(it) }, allGroups.lastOrNull { text(it) }).distinct()
                }
                    // Include text, mixed content, image-only content and chapter endings.
                for (group in groups) {
                    val document = ContinuousGroupDocument.compose(book, group)
                    val chapter = book.chapters[group.firstIndex]
                    val html = document.html ?: book.chapterHtml(chapter)
                    val setup = readerSetupScript(0.0, null, ReaderSettings(viewMode = ReaderViewMode.Continuous,
                        verticalWriting = vertical, fontSize = 24, mergeImageOnlyChapters = merge), null, null, false, false,
                        ContentLanguageProfile.Default, IntSize(360, 640), 0xFF000000, 0x6687CEEB, null, null, "fixture", assets,
                        document.sourcesJson, group.firstIndex)
                    // Singleton linked styles need the same source-relative resolution as navigation.
                    val dom = org.jsoup.Jsoup.parse(html)
                    dom.select("link[rel=stylesheet]").forEach { link ->
                        val resolved = java.net.URI("https", "appassets.androidplatform.net", "/epub/${chapter.href}", null).resolve(link.attr("href"))
                        val css = resourceBridge.resourceForUrl(resolved.toString())!!.data.toString(Charsets.UTF_8)
                        link.replaceWith(org.jsoup.nodes.Element("style").appendChild(org.jsoup.nodes.DataNode(css)))
                    }
                    if (document.html == null) dom.select("img,svg image").forEach { image ->
                        for (attribute in listOf("src", "href", "xlink:href")) {
                            if (image.hasAttr(attribute)) image.attr(attribute,
                                java.net.URI("https", "appassets.androidplatform.net", "/epub/${chapter.href}", null)
                                    .resolve(image.attr(attribute)).toString())
                        }
                    }
                    // XHTML is serialized as XML so the browser follows the original response MIME.
                    if (document.html == null) dom.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                    add(buildJsonObject {
                        put("vertical", vertical); put("merge", merge); put("indices", group.indices.joinToString(","))
                        put("html", embeddedResources(if (document.html == null) dom.outerHtml() else html)); put("setup", setup)
                        put("mime", if (document.html == null) chapter.mediaType else "text/html")
                        put("hasText", document.sources.any { it.hasText })
                        put("expectedSvgImages", group.indices.sumOf { Regex("<image\\b", RegexOption.IGNORE_CASE).findAll(book.chapterHtml(book.chapters[it])).count() })
                    })
                }
            }
        }
        val file = temporary.newFile("fixtures.json").apply { writeText(fixtures.toString()) }
        val output = temporary.newFile("browser-output.txt")
        val process = ProcessBuilder("node", "--test", "app/src/test/js/reader-continuous-production.test.mjs")
            .directory(root).redirectErrorStream(true).redirectOutput(output)
            .apply { environment()["HOSHI_TEST_READER_FIXTURES"] = file.absolutePath; environment()["HOSHI_TEST_CHROMIUM"] = chromium!! }
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Browser test timed out") }
        assertEquals(output.readText(), 0, process.exitValue())
    }
    @Test fun generatedSetupRendersXhtmlBuffersAndMergedSvgIllustrations() = runBrowser(fixture())
    @Test fun suppliedEpubRendersWithTheProductionSetup() {
        val path = System.getenv("HOSHI_TEST_EPUB_PATH")
        assumeTrue("No optional local EPUB supplied", !path.isNullOrBlank())
        runBrowser(EpubBookParser(temporary.newFolder("parser")).parse(File(path!!)), sample = true)
    }
}
