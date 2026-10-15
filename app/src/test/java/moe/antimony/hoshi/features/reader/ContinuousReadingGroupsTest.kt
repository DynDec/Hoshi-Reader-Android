package moe.antimony.hoshi.features.reader

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import moe.antimony.hoshi.epub.EpubBookParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContinuousReadingGroupsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val image = "<img src='../images/picture.png' alt='Description is not reading text'/>"
    private fun book(content: List<String>, targets: List<String>? = listOf("0.xhtml")): EpubBook {
        val nav = targets?.joinToString("") { "<li><a href='../text/$it'>Repeated label</a></li>" }
        return EpubBook(
            "Fixture", chapters = content.mapIndexed { i, html -> EpubChapter("source-$i", "OPS/text/$i.xhtml", "application/xhtml+xml", "<html><body>$html</body></html>") },
            navigationDocumentHref = if (nav == null) null else "OPS/nav/tree.xhtml",
            resources = if (nav == null) emptyMap() else mapOf("OPS/nav/tree.xhtml" to EpubResource("application/xhtml+xml", "<html><body><nav epub:type='toc'><ol>$nav</ol></nav></body></html>".toByteArray())),
        )
    }
    private fun groups(book: EpubBook) = ContinuousReadingGroups.build(book, true).map { it.indices }

    @Test fun textImageTextAndConsecutiveImagesCombineWithoutDuplicatingSources() {
        assertEquals(listOf(listOf(0, 1, 2, 3, 4)), groups(book(listOf("Alpha", image, image, "Beta", image))))
        assertEquals(listOf(listOf(0), listOf(1)), groups(book(listOf("Alpha", "Beta"))))
        assertEquals(listOf(listOf(0, 1, 2)), groups(book(listOf(image, image, "Alpha"))))
        assertEquals(listOf(listOf(0, 1)), groups(book(listOf(image, image))))
    }

    @Test fun navigationBoundariesAllowOnlyTheEligibleSideAndDoNotUseLabels() {
        val content = listOf("Alpha", image, "Beta")
        assertEquals(listOf(listOf(0, 1), listOf(2)), groups(book(content, listOf("0.xhtml", "2.xhtml"))))
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups(book(content, listOf("0.xhtml", "1.xhtml"))))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups(book(content, null)))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups(book(content, listOf("2.xhtml", "0.xhtml"))))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups(book(content, listOf("missing.xhtml"))))
    }

    @Test fun frontMatterMergesBeforeTheFirstNamedSectionWithoutCrossingItsBoundary() {
        val content = List(5) { image } + listOf("Contents", "Alpha", image, "Beta")
        val fixture = book(content, listOf("6.xhtml", "8.xhtml"))
        val merged = ContinuousReadingGroups.build(fixture, true)
        assertEquals(listOf((0..5).toList(), listOf(6, 7), listOf(8)), merged.map { it.indices })
        assertNull(merged.first().previousPosition())
        assertEquals(ReaderChapterPosition(6), merged.first().nextPosition(8))
        assertEquals(ReaderChapterPosition(5, 1.0), merged[1].previousPosition())
        assertEquals(fixture.chapters.indices.map { listOf(it) }, ContinuousReadingGroups.build(fixture, false).map { it.indices })
        for (targets in listOf(null, emptyList(), listOf("missing.xhtml"))) {
            assertEquals(fixture.chapters.indices.map { listOf(it) }, groups(book(content, targets)))
        }
    }

    @Test fun frontMatterRetainsTextEmptySourceAndFragmentBoundaries() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5)),
            groups(book(listOf("Introduction", "Credits", image, "", image, "Alpha"), listOf("5.xhtml"))))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)),
            groups(book(listOf(image, image, "<p>Preface</p><h1 id='chapter'>Alpha</h1>", image, "Beta"), listOf("2.xhtml#chapter"))))
    }

    @Test fun fragmentSectionsAndNestedTargetsConservativelyKeepSpanningFilesSeparate() {
        val content = listOf("<h1 id='first'>Alpha</h1><p id='second'>Beta</p>", image, "Gamma")
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups(book(content, listOf("0.xhtml#first", "0.xhtml#second"))))
        assertEquals(listOf(listOf(0, 1, 2)), groups(book(content, listOf("0.xhtml#first"))))
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups(book(content, listOf("0.xhtml#second"))))
        val fixture = book(listOf("Alpha", image, "<h1 id='nested'>Beta</h1>"))
        val nested = "<nav epub:type='toc'><ol><li><a href='../text/0.xhtml'>Parent</a><ol><li><a href='../text/2.xhtml#nested'>Child</a></li></ol></li></ol></nav>"
        assertEquals(listOf(listOf(0, 1), listOf(2)), groups(fixture.copy(resources = mapOf("OPS/nav/tree.xhtml" to EpubResource("application/xhtml+xml", nested.toByteArray())))))
        assertEquals(listOf(listOf(0, 1)), groups(book(listOf("<script>Metadata</script><img id='start' src='image.png'/>", "Alpha"), listOf("0.xhtml#start"))))
    }

    @Test fun ncxMetadataBoundariesAndUnavailableOrEmptySourcesAreConservative() {
        val fixture = book(listOf("Alpha", image, "Beta"))
        val ncx = "<ncx><navMap><navPoint><navLabel><text>Parent</text></navLabel><content src='text/0.xhtml'/><navPoint><navLabel><text>Child</text></navLabel><content src='text/2.xhtml'/></navPoint></navPoint></navMap></ncx>"
        val ncxBook = fixture.copy(navigationDocumentHref = "OPS/toc.ncx", resources = mapOf("OPS/toc.ncx" to EpubResource("application/x-dtbncx+xml", ncx.toByteArray())))
        assertEquals(listOf(listOf(0, 1), listOf(2)), groups(ncxBook))
        val unnamedParent = ncx.replace("<navLabel><text>Parent</text></navLabel>", "")
        assertEquals(listOf(listOf(0, 1), listOf(2)), groups(ncxBook.copy(resources = mapOf("OPS/toc.ncx" to EpubResource("application/x-dtbncx+xml", unnamedParent.toByteArray())))))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups(fixture.copy(resources = emptyMap())))
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups(book(listOf("", image, "Alpha"))))
        assertFalse(ReaderChapterContent.classify("<img src=''/>").imageOnly)
        assertFalse(ReaderChapterContent.classify("<svg><image/></svg>").imageOnly)
    }

    @Test fun linkedAndImportedStylesBodyIdsSvgPaintAndDataSrcsetsKeepTheirSourceResources() {
        val fixture = book(listOf("Alpha<link rel='stylesheet' href='../style/main.css'/>", image, "<p>Beta</p><svg><path id='paint' d='M0 0L1 1'/><rect fill='url(#paint)'/></svg><img srcset='data:image/png;base64,YWJj 1x, ../images/picture.png 2x'/>")).let {
            it.copy(chapters = it.chapters.mapIndexed { index, chapter -> if (index == 0) chapter.copy(html = chapter.html.replace("<body>", "<body id='publisher-body'>")) else chapter }, resources = it.resources + mapOf(
                "OPS/style/main.css" to EpubResource("text/css", "@import url(child.css) screen;body#publisher-body{background:url('../images/picture.png')}".toByteArray()),
                "OPS/style/child.css" to EpubResource("text/css", "p{color:red}@import 'main.css';".toByteArray()),
            ))
        }
        val dom = Jsoup.parse(ContinuousGroupDocument.compose(fixture, ContinuousReadingGroups.build(fixture, true).single()).html!!)
        assertNotNull(dom.getElementById("hoshi-source-0-publisher-body"))
        assertTrue(dom.selectFirst("style")!!.data().contains("@media screen {p{color:red}"))
        assertFalse(dom.selectFirst("style")!!.data().contains("@import"))
        assertEquals("url(\"#hoshi-source-2-paint\")", dom.selectFirst("rect")!!.attr("fill"))
        assertEquals("data:image/png;base64,YWJj 1x, https://appassets.androidplatform.net/epub/OPS/images/picture.png 2x", dom.select("img[srcset]").single().attr("srcset"))
    }

    @Test fun classifierIgnoresMetadataScriptsAndAlternativeDescriptionsButNotCaptions() {
        assertTrue(ReaderChapterContent.classify("<head><title>Title</title></head><body>$image<script>code</script></body>").imageOnly)
        assertTrue(ReaderChapterContent.classify("<svg><title>Alt</title><desc>Alternative</desc><image href='picture.png'/></svg>").imageOnly)
        assertTrue(ReaderChapterContent.classify("<svg><path d='M0 0L10 10'/></svg>").imageOnly)
        for (text in listOf("<h1>Heading</h1>", "<figcaption>Caption</figcaption>", "<p>&#x732b;</p>", "<svg><text>Text</text></svg>")) {
            assertFalse(ReaderChapterContent.classify(image + text).imageOnly)
            assertTrue(ReaderChapterContent.classify(image + text).hasText)
        }
        assertFalse(ReaderChapterContent.classify("<p> \n&nbsp;</p>").hasText)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups(book(listOf("Alpha", image + "<figcaption>Caption</figcaption>", "Beta"))))
    }

    @Test fun renderedMappingRoundTripsSourcePositionsIncludingZeroTextFiles() {
        val group = ContinuousReadingGroups.build(book(listOf("Alpha", image, "Beta")), true).single()
        for (index in 0..2) for (progress in listOf(0.0, 0.25, 0.75)) {
            val original = ReaderChapterPosition(index, progress)
            val mapped = group.sourcePosition(group.renderedProgress(original))
            assertEquals(original.index, mapped.index)
            assertEquals(original.progress, mapped.progress, 0.000001)
        }
        assertEquals(ReaderChapterPosition(2, 1.0), group.sourcePosition(1.0))
        for (index in 0..2) {
            val original = ReaderChapterPosition(index, 1.0)
            assertEquals(original, group.sourcePosition(group.renderedProgress(original), preferredIndex = index))
        }
        assertEquals(0, book(listOf("Alpha", image, "Beta")).bookInfo.chapterInfo.getValue("OPS/text/1.xhtml").chapterCount)
    }

    @Test fun chapterNavigationStepsBeyondDisplayedGroupsAndStopsAtBookEdges() {
        val fixture = book(listOf("Alpha", image, "Beta", "Gamma", image, "Delta"), listOf("0.xhtml", "3.xhtml"))
        val groups = ContinuousReadingGroups.build(fixture, true)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), groups.map { it.indices })
        assertNull(groups.first().previousPosition())
        assertEquals(ReaderChapterPosition(3), groups.first().nextPosition(5))
        assertEquals(ReaderChapterPosition(2, 1.0), groups.last().previousPosition())
        assertNull(groups.last().nextPosition(5))
    }

    @Test fun composedDocumentPreservesResourcesStylesAndUniqueFragmentTargets() {
        val fixture = book(listOf("<style>body{color:red}#same{background:url('../images/picture.png')}</style><p id='same'>Alpha</p>", image, "<style>body{color:blue}</style><p id='same'>Beta</p><svg><path id='paint'/><use href='#paint'/></svg>"))
        val document = ContinuousGroupDocument.compose(fixture, ContinuousReadingGroups.build(fixture, true).single())
        val dom = Jsoup.parse(document.html!!)
        val ids = dom.select("[id]").map { it.id() }
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(dom.getElementById("hoshi-source-0-same"))
        assertNotNull(dom.getElementById("hoshi-source-2-same"))
        assertEquals("https://appassets.androidplatform.net/epub/OPS/images/picture.png", dom.selectFirst("img")!!.attr("src"))
        assertEquals("#hoshi-source-2-paint", dom.selectFirst("use")!!.attr("href"))
        assertEquals(2, dom.select("style[data-hoshi-source-style]").size)
        assertTrue(dom.selectFirst("style")!!.data().contains("https://appassets.androidplatform.net/epub/OPS/images/picture.png"))
        assertEquals(listOf(true, false, true), document.sources.map { it.hasText })
        assertEquals(listOf("hoshi-source-1"), dom.select("[data-hoshi-merged-image]").map { it.id() })
    }

    @Test fun imageSpacingMarkersRequireAnActualMergeAndExcludeImagesInTextSources() {
        val fixture = book(listOf("<p>Alpha</p>$image", image, image, "<p>Beta</p>$image"))
        val merged = ContinuousGroupDocument.compose(fixture, ContinuousReadingGroups.build(fixture, true).single())
        assertEquals(listOf("hoshi-source-1", "hoshi-source-2"),
            Jsoup.parse(merged.html!!).select("[data-hoshi-merged-image]").map { it.id() })
        for (merge in listOf(false, true)) {
            val separate = book(listOf("Alpha", image), listOf("0.xhtml", "1.xhtml"))
            ContinuousReadingGroups.build(separate, merge).forEach { group ->
                assertNull(ContinuousGroupDocument.compose(separate, group).html)
            }
        }
    }

    @Test fun generatedPackedEpubUsesManifestNavigationAndWorksWithCachedChapterShells() {
        val content = listOf(image, image, "Contents", "Alpha", image, "Beta")
        val archive = temporary.newFile("fixture.epub")
        ZipOutputStream(archive.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            put("OPS/package.opf", "<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Fixture</dc:title><dc:identifier id='id'>fixture</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id='nav' href='nav/tree.xhtml' properties='nav' media-type='application/xhtml+xml'/>" + content.indices.joinToString("") { "<item id='c$it' href='text/$it.xhtml' media-type='application/xhtml+xml'/>" } + "</manifest><spine>" + content.indices.joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>")
            put("OPS/nav/tree.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body><nav epub:type='toc'><ol><li><a href='../text/3.xhtml'>Section</a></li></ol></nav></body></html>")
            content.forEachIndexed { index, body -> put("OPS/text/$index.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body>$body</body></html>") }
        }
        val parser = EpubBookParser(temporary.newFolder("parser"))
        val first = parser.parse(archive)
        val cached = parser.parse(archive, cachedBookInfo = first.bookInfo)
        assertEquals("OPS/nav/tree.xhtml", cached.navigationDocumentHref)
        assertTrue(cached.chapters.all { it.html.isEmpty() })
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), groups(first))
        assertEquals(groups(first), groups(cached))
        assertEquals(first.bookInfo, cached.bookInfo)
        val merged = ContinuousReadingGroups.build(cached, true)
        val frontMatter = ContinuousGroupDocument.compose(cached, merged.first())
        assertEquals(listOf(0, 1, 2), frontMatter.sources.map { it.index })
        assertEquals(listOf(false, false, true), frontMatter.sources.map { it.hasText })
        assertTrue(frontMatter.html!!.contains("Contents"))
        assertTrue(ContinuousGroupDocument.compose(cached, merged.last()).html!!.contains("Alpha"))
    }

    @Test fun groupingClassificationProducesTheSameComposedDocument() {
        val fixture = book(listOf("<script>code</script>Alpha", image, "<svg><path d='M0 0'/></svg>", "Beta", image), listOf("0.xhtml", "3.xhtml"))
        val merged = ContinuousReadingGroups.build(fixture, true)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), merged.map { it.indices })
        assertEquals(fixture.chapters.map { ReaderChapterContent.classify(it.html) }, merged.flatMap { it.contents.orEmpty() })
        for (group in merged + ContinuousReadingGroups.build(fixture, false)) {
            assertEquals(
                ContinuousGroupDocument.compose(fixture, group.copy(contents = null)),
                ContinuousGroupDocument.compose(fixture, group),
            )
        }
    }

    @Test fun chapterLookupFindsEachSourceGroup() {
        val groups = ContinuousReadingGroups.build(book(listOf("Alpha", image, "Beta", "Gamma", image)), true)
        val lookup = ContinuousReadingGroups.byChapter(groups)
        assertEquals(5, lookup.size)
        (0 until 5).forEach { index -> assertSame(groups.first { index in it.indices }, lookup[index]) }
        assertTrue(ContinuousReadingGroups.byChapter(emptyList()).isEmpty())
    }

    @Test fun missingNavigationFragmentsMarkTheirFileStartInsteadOfDisablingMerging() {
        // Converted books can link the TOC to anchors that no longer exist in the file.
        val content = listOf(image, image, "Notice", "Contents", image, "<p id='real'>Alpha</p>", image, "Beta")
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5, 6), listOf(7)),
            groups(book(content, listOf("3.xhtml#gone", "5.xhtml#real", "7.xhtml#gone-too"))),
        )
        // Two targets in one file remain ambiguous even when one fragment is missing.
        assertEquals(
            listOf(listOf(0, 1), listOf(2), listOf(3, 4)),
            groups(book(listOf(image, "Alpha", "<h1 id='real'>Beta</h1>", image, "Gamma"), listOf("0.xhtml", "2.xhtml#gone", "2.xhtml#real"))),
        )
    }
}
