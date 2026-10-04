package moe.antimony.hoshi.features.reader

import java.net.URI
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal data class ReaderChapterContent(val imageOnly: Boolean, val hasText: Boolean) {
    companion object {
        fun classify(html: String): ReaderChapterContent {
            val body = Jsoup.parse(html).body()
            body.select("script,style,head,metadata,title,desc,rt,rp,template").remove()
            val hasText = body.wholeText().any { !it.isWhitespace() && it != '\u00a0' }
            val images = body.select("img,svg image,svg path,svg rect,svg circle,svg polygon,svg ellipse,svg use").filter {
                when (it.normalName()) {
                    "img" -> it.attr("src").isNotBlank() || it.attr("srcset").isNotBlank()
                    "image", "use" -> it.attr("href").isNotBlank() || it.attr("xlink:href").isNotBlank()
                    else -> true
                }
            }
            return ReaderChapterContent(!hasText && images.isNotEmpty(), hasText)
        }
    }
}

/** Source indices and source character counts never change with the display preference. */
internal data class ContinuousReadingGroup(val indices: List<Int>, val counts: List<Int>) {
    val firstIndex get() = indices.first()
    val lastIndex get() = indices.last()
    private val weights get() = counts.map { it.coerceAtLeast(1) }
    fun nextPosition(lastSpineIndex: Int): ReaderChapterPosition? =
        (lastIndex + 1).takeIf { it <= lastSpineIndex }?.let { ReaderChapterPosition(it) }
    fun previousPosition(): ReaderChapterPosition? =
        (firstIndex - 1).takeIf { it >= 0 }?.let { ReaderChapterPosition(it, 1.0) }
    fun renderedProgress(position: ReaderChapterPosition): Double {
        val at = indices.indexOf(position.index).coerceAtLeast(0)
        return (weights.take(at).sum() + weights[at] * position.progress.coerceIn(0.0, 1.0)) / weights.sum().toDouble()
    }
    fun sourcePosition(progress: Double, preferredIndex: Int? = null): ReaderChapterPosition {
        var remaining = progress.coerceIn(0.0, 1.0) * weights.sum()
        val preferred = indices.indexOf(preferredIndex)
        if (preferred >= 0) {
            val before = weights.take(preferred).sum()
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
    fun build(book: EpubBook, merge: Boolean): List<ContinuousReadingGroup> {
        val joins = BooleanArray((book.chapters.size - 1).coerceAtLeast(0))
        if (merge) {
            val boundaries = navigationBoundaries(book)
            if (boundaries != null) {
                val content = book.chapters.map { ReaderChapterContent.classify(book.chapterHtml(it)) }
                val sections = Array<Int?>(book.chapters.size) { null }
                val ambiguous = mutableSetOf<Int>()
                var section: Int? = null
                book.chapters.indices.forEach { index ->
                    val targets = boundaries[index].orEmpty()
                    if (targets.isNotEmpty()) {
                        section = index
                        if (targets.size != 1 || targets.single().isNotEmpty() &&
                            !fragmentStartsContent(book.chapterHtml(book.chapters[index]), targets.single())) {
                            ambiguous += index
                        }
                    }
                    sections[index] = section
                }
                joins.indices.forEach { index ->
                    joins[index] = index !in ambiguous && index + 1 !in ambiguous &&
                        sections[index] != null && sections[index] == sections[index + 1] &&
                        (content[index].imageOnly || content[index].hasText) &&
                        (content[index + 1].imageOnly || content[index + 1].hasText) &&
                        (content[index].imageOnly || content[index + 1].imageOnly)
                }
            }
        }
        val result = mutableListOf<ContinuousReadingGroup>()
        var start = 0
        book.chapters.indices.forEach { end ->
            if (end == book.chapters.lastIndex || !joins[end]) {
                val indices = (start..end).toList()
                result += ContinuousReadingGroup(indices, indices.map { book.bookInfo.chapterInfo[book.chapters[it].href]?.chapterCount ?: 0 })
                start = end + 1
            }
        }
        return result
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
            val nav = document.select("nav").filter { it.attr("epub:type").split(Regex("\\s+")).contains("toc") || it.attr("role") == "doc-toc" }.singleOrNull()
                ?: return null
            nav.select("a[href]").map { it.text().ifBlank { it.attr("title") } to it.attr("href") }
        }
        val targets = anchors.filter { it.first.isNotBlank() }.map { (_, target) ->
            val resolved = runCatching { URI("https", "appassets.androidplatform.net", "/epub/$href", null).resolve(target) }.getOrNull() ?: return null
            if (resolved.host != "appassets.androidplatform.net" || resolved.scheme != "https") return null
            val chapter = book.chapters.indexOfFirst { it.href == resolved.path.removePrefix("/epub/") }
            if (chapter < 0) return null
            val fragment = resolved.fragment.orEmpty()
            if (fragment.isNotEmpty() && Jsoup.parse(book.chapterHtml(book.chapters[chapter])).getElementById(fragment) == null) return null
            chapter to fragment
        }
        if (targets.isEmpty() || targets.zipWithNext().any { (a, b) -> a.first > b.first }) return null
        return targets.groupBy({ it.first }, { it.second })
    }

    private fun fragmentStartsContent(html: String, fragment: String): Boolean {
        val document = Jsoup.parse(html)
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
                    if (node.normalName() in setOf("script", "style", "metadata", "title", "desc", "rt", "rp", "template")) continue
                    if (node.normalName() in setOf("img", "svg")) prefix.append("<img src='boundary'/>")
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
