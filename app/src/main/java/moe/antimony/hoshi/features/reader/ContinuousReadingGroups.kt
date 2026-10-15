package moe.antimony.hoshi.features.reader

import java.net.URI
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

internal data class ReaderChapterContent(val imageOnly: Boolean, val hasText: Boolean) {
    companion object {
        fun classify(html: String): ReaderChapterContent = classify(Jsoup.parse(html))

        /** Reads [document] without modifying it, so callers can reuse one parse. */
        fun classify(document: Document): ReaderChapterContent {
            var hasText = false
            var hasImage = false
            fun visit(element: Element, inSvg: Boolean) {
                for (node in element.childNodes()) {
                    if (hasText) return
                    when (node) {
                        is TextNode -> if (node.wholeText.any { !it.isWhitespace() && it != '\u00a0' }) hasText = true
                        is Element -> {
                            val name = node.normalName()
                            if (name in ReaderChapterIgnoredElements) continue
                            if (!hasImage && node.isReaderImage(name, inSvg)) hasImage = true
                            visit(node, inSvg || name == "svg")
                        }
                    }
                }
            }
            visit(document.body(), inSvg = false)
            return ReaderChapterContent(!hasText && hasImage, hasText)
        }

        private fun Element.isReaderImage(name: String, inSvg: Boolean): Boolean = when {
            name == "img" -> attr("src").isNotBlank() || attr("srcset").isNotBlank()
            !inSvg -> false
            name == "image" || name == "use" -> attr("href").isNotBlank() || attr("xlink:href").isNotBlank()
            else -> name in ReaderSvgShapeElements
        }
    }
}

private val ReaderChapterIgnoredElements = setOf("script", "style", "head", "metadata", "title", "desc", "rt", "rp", "template")
private val ReaderSvgShapeElements = setOf("path", "rect", "circle", "polygon", "ellipse")
private val FragmentPrefixSkippedElements = setOf("script", "style", "metadata", "title", "desc", "rt", "rp", "template")
private val FragmentPrefixMediaElements = setOf("img", "svg")
private val WhitespacePattern = Regex("\\s+")

/** Source indices and source character counts never change with the display preference. */
internal data class ContinuousReadingGroup(
    val indices: List<Int>,
    val counts: List<Int>,
    /** Classification captured while grouping; null when grouping did not need to parse sources. */
    val contents: List<ReaderChapterContent>? = null,
) {
    val firstIndex get() = indices.first()
    val lastIndex get() = indices.last()
    private val weights = counts.map { it.coerceAtLeast(1) }
    private val weightsBefore = weights.runningFold(0) { sum, weight -> sum + weight }
    private val totalWeight = weightsBefore.last()
    fun nextPosition(lastSpineIndex: Int): ReaderChapterPosition? =
        (lastIndex + 1).takeIf { it <= lastSpineIndex }?.let { ReaderChapterPosition(it) }
    fun previousPosition(): ReaderChapterPosition? =
        (firstIndex - 1).takeIf { it >= 0 }?.let { ReaderChapterPosition(it, 1.0) }
    fun renderedProgress(position: ReaderChapterPosition): Double {
        val at = indices.indexOf(position.index).coerceAtLeast(0)
        return (weightsBefore[at] + weights[at] * position.progress.coerceIn(0.0, 1.0)) / totalWeight.toDouble()
    }
    fun sourcePosition(progress: Double, preferredIndex: Int? = null): ReaderChapterPosition {
        var remaining = progress.coerceIn(0.0, 1.0) * totalWeight
        val preferred = indices.indexOf(preferredIndex)
        if (preferred >= 0) {
            val before = weightsBefore[preferred]
            if (remaining >= before - 1e-9 && remaining <= before + weights[preferred] + 1e-9) {
                return ReaderChapterPosition(indices[preferred], ((remaining - before) / weights[preferred]).coerceIn(0.0, 1.0))
            }
        }
        weights.forEachIndexed { at, weight ->
            if (remaining < weight || at == weights.lastIndex) {
                return ReaderChapterPosition(indices[at], (remaining / weight).coerceIn(0.0, 1.0))
            }
            remaining -= weight
        }
        return ReaderChapterPosition(lastIndex, 1.0)
    }
    fun fragment(index: Int, fragment: String): String =
        if (indices.size == 1) fragment else "hoshi-source-$index-$fragment"
}

internal object ContinuousReadingGroups {
    /** Indexes groups by source chapter so per-scroll lookups do not scan every group. */
    fun byChapter(groups: List<ContinuousReadingGroup>): List<ContinuousReadingGroup?> {
        val size = groups.maxOfOrNull { it.lastIndex + 1 } ?: 0
        val lookup = arrayOfNulls<ContinuousReadingGroup>(size)
        // Keep the first containing group, matching a linear firstOrNull scan.
        groups.asReversed().forEach { group -> group.indices.forEach { lookup[it] = group } }
        return lookup.asList()
    }

    fun build(book: EpubBook, merge: Boolean): List<ContinuousReadingGroup> {
        val joins = BooleanArray((book.chapters.size - 1).coerceAtLeast(0))
        val contents = if (merge) navigationBoundaries(book)?.let { joinSections(book, it, joins) } else null
        val result = mutableListOf<ContinuousReadingGroup>()
        var start = 0
        book.chapters.indices.forEach { end ->
            if (end == book.chapters.lastIndex || !joins[end]) {
                val indices = (start..end).toList()
                result += ContinuousReadingGroup(
                    indices,
                    indices.map { book.bookInfo.chapterInfo[book.chapters[it].href]?.chapterCount ?: 0 },
                    contents?.subList(start, end + 1)?.toList(),
                )
                start = end + 1
            }
        }
        return result
    }

    /** Parses each source file once, fills [joins] and returns the per-chapter content. */
    private fun joinSections(
        book: EpubBook,
        boundaries: Map<Int, List<String>>,
        joins: BooleanArray,
    ): List<ReaderChapterContent> {
        val content = ArrayList<ReaderChapterContent>(book.chapters.size)
        val sections = IntArray(book.chapters.size)
        val ambiguous = BooleanArray(book.chapters.size)
        // Valid navigation bounds the front matter before its first named target.
        var section = -1
        book.chapters.forEachIndexed { index, chapter ->
            val document = Jsoup.parse(book.chapterHtml(chapter))
            // A link to a missing fragment opens its file at the top, so it marks the file start.
            val targets = boundaries[index].orEmpty().map { fragment ->
                if (fragment.isNotEmpty() && document.getElementById(fragment) == null) "" else fragment
            }
            if (targets.isNotEmpty()) {
                section = index
                if (targets.size != 1 || targets.single().isNotEmpty() &&
                    !fragmentStartsContent(document, targets.single())) {
                    ambiguous[index] = true
                }
            }
            sections[index] = section
            content += ReaderChapterContent.classify(document)
        }
        joins.indices.forEach { index ->
            joins[index] = !ambiguous[index] && !ambiguous[index + 1] &&
                sections[index] == sections[index + 1] &&
                (content[index].imageOnly || content[index].hasText) &&
                (content[index + 1].imageOnly || content[index + 1].hasText) &&
                (content[index].imageOnly || content[index + 1].imageOnly)
        }
        return content
    }

    private fun navigationBoundaries(book: EpubBook): Map<Int, List<String>>? {
        val href = book.navigationDocumentHref ?: return null
        val bytes = runCatching { book.readResource(href) }.getOrNull() ?: return null
        val document = Jsoup.parse(bytes.toString(Charsets.UTF_8))
        val anchors: List<Pair<String, String>> = if (book.mediaType(href) == "application/x-dtbncx+xml") {
            document.select("navMap navPoint").mapNotNull { point ->
                val label = point.children().firstOrNull { it.normalName() == "navlabel" }
                    ?.selectFirst("text")?.text().orEmpty()
                point.children().firstOrNull { it.normalName() == "content" }?.attr("src")?.let { label to it }
            }
        } else {
            val nav = document.select("nav").filter { it.attr("epub:type").split(WhitespacePattern).contains("toc") || it.attr("role") == "doc-toc" }.singleOrNull()
                ?: return null
            nav.select("a[href]").map { it.text().ifBlank { it.attr("title") } to it.attr("href") }
        }
        val chapterIndices = book.chapters.withIndex().reversed().associate { (index, chapter) -> chapter.href to index }
        val targets = anchors.filter { it.first.isNotBlank() }.map { (_, target) ->
            val resolved = runCatching { URI("https", "appassets.androidplatform.net", "/epub/$href", null).resolve(target) }.getOrNull() ?: return null
            if (resolved.host != "appassets.androidplatform.net" || resolved.scheme != "https") return null
            val chapter = chapterIndices[resolved.path.removePrefix("/epub/")] ?: return null
            // joinSections resolves missing fragments to the file start while parsing each file once.
            chapter to resolved.fragment.orEmpty()
        }
        if (targets.isEmpty() || targets.zipWithNext().any { (a, b) -> a.first > b.first }) return null
        return targets.groupBy({ it.first }, { it.second })
    }

    private fun fragmentStartsContent(document: Document, fragment: String): Boolean {
        if (document.selectFirst("html")?.id() == fragment) return true
        val body = document.body()
        val target = body.getElementById(fragment) ?: return false
        // A fragment after either text or media divides a source file into sections.
        val prefix = StringBuilder()
        var reached = false
        fun visit(element: Element) {
            if (reached) return
            if (element === target) { reached = true; return }
            for (node in element.childNodes()) {
                if (reached) break
                if (node is Element) {
                    if (node === target) { reached = true; break }
                    if (node.normalName() in FragmentPrefixSkippedElements) continue
                    if (node.normalName() in FragmentPrefixMediaElements) prefix.append("<img src='boundary'/>")
                    visit(node)
                } else prefix.append(node.outerHtml())
            }
        }
        visit(body)
        val before = ReaderChapterContent.classify("<body>$prefix</body>")
        return reached && !before.hasText && !before.imageOnly
    }
}

internal fun EpubBook.chapterHtml(chapter: EpubChapter): String =
    chapter.html.ifEmpty { runCatching { readResource(chapter.href)?.toString(Charsets.UTF_8) }.getOrNull().orEmpty() }
