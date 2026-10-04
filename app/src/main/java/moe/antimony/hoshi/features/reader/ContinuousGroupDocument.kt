package moe.antimony.hoshi.features.reader

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element

@Serializable
internal data class ContinuousSource(val index: Int, val count: Int, val hasText: Boolean)

internal data class ContinuousGroupDocument(val html: String?, val sources: List<ContinuousSource>) {
    val sourcesJson: String get() = Json.encodeToString(sources)
    companion object {
        fun compose(book: EpubBook, group: ContinuousReadingGroup): ContinuousGroupDocument {
            val chapters = group.indices.map { book.chapters[it] }
            val html = chapters.map { book.chapterHtml(it) }
            val sources = html.mapIndexed { at, content ->
                ContinuousSource(group.indices[at], group.counts[at], ReaderChapterContent.classify(content).hasText)
            }
            if (chapters.size == 1) return ContinuousGroupDocument(null, sources)
            val document = Jsoup.parse("<html><head></head><body></body></html>")
            document.outputSettings().prettyPrint(false)
            chapters.forEachIndexed { at, chapter ->
                val index = group.indices[at]
                val source = Jsoup.parse(html[at])
                source.outputSettings().prettyPrint(false)
                val scope = "hoshi-source-$index"
                val base = URI("https", "appassets.androidplatform.net", "/epub/${chapter.href}", null).toASCIIString()
                fun url(raw: String, relativeTo: String = base): String {
                    if (raw.startsWith("#")) return "#${scope}-${raw.removePrefix("#")}"
                    return runCatching { URI(relativeTo).resolve(raw).toASCIIString() }.getOrDefault(raw)
                }
                fun localResource(resolved: String): ByteArray? {
                    val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
                    if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !uri.path.startsWith("/epub/")) return null
                    return runCatching { book.readResource(uri.path.removePrefix("/epub/")) }.getOrNull()
                }
                fun css(raw: String, relativeTo: String = base, visited: Set<String> = emptySet()): String {
                    val imported = Regex("""@import\s+(?:url\(\s*(?:"([^"]+)"|'([^']+)'|([^\s)]+))\s*\)|"([^"]+)"|'([^']+)')\s*([^;]*);""", RegexOption.IGNORE_CASE)
                        .replace(raw) { match ->
                            val resolved = url(match.groupValues.slice(1..5).first { it.isNotEmpty() }, relativeTo)
                            if (resolved in visited) "" else {
                                val bytes = localResource(resolved)
                                val nested = bytes?.let { css(it.toString(Charsets.UTF_8), resolved, visited + resolved) }.orEmpty()
                                match.groupValues[6].trim().takeIf { it.isNotEmpty() }?.let { "@media $it {$nested}" } ?: nested
                            }
                        }
                    return Regex("""url\(\s*(["']?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE).replace(imported) {
                        "url(\"${url(it.groupValues[2], relativeTo).replace("\"", "%22")}\")"
                    }
                }
                fun srcset(raw: String): String {
                    val entries = mutableListOf<String>()
                    var at = 0
                    while (at < raw.length) {
                        while (at < raw.length && (raw[at].isWhitespace() || raw[at] == ',')) at++
                        val start = at
                        val dataUrl = raw.regionMatches(at, "data:", 0, 5, ignoreCase = true)
                        while (at < raw.length && !raw[at].isWhitespace() && (dataUrl || raw[at] != ',')) at++
                        if (at == start) break
                        val token = raw.substring(start, at)
                        val target = token.trimEnd(',')
                        val descriptorStart = at
                        if (!token.endsWith(',')) while (at < raw.length && raw[at] != ',') at++
                        val descriptor = raw.substring(descriptorStart, at).trim()
                        entries += url(target) + descriptor.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
                    }
                    return entries.joinToString(", ")
                }
                source.select("style,link[rel=stylesheet]").forEach { style ->
                    val raw = if (style.normalName() == "style") css(style.data()) else {
                        val resolved = url(style.attr("href"))
                        localResource(resolved)?.let { css(it.toString(Charsets.UTF_8), resolved, setOf(resolved)) }.orEmpty()
                    }
                    document.head().appendElement("style").attr("media", "not all")
                        .attr("data-hoshi-source-style", scope).attr("data-hoshi-publisher-media", style.attr("media"))
                        .appendChild(DataNode(raw))
                    style.remove()
                }
                source.select("script,head,metadata").remove()
                source.body().getAllElements().forEach { element ->
                    for (attribute in listOf("id", "name")) {
                        if (element.hasAttr(attribute)) element.attr(attribute, "$scope-${element.attr(attribute)}")
                    }
                    for (attribute in listOf("src", "href", "xlink:href", "poster", "data")) {
                        if (element.hasAttr(attribute)) element.attr(attribute, url(element.attr(attribute)))
                    }
                    if (element.hasAttr("style")) element.attr("style", css(element.attr("style")))
                    for (attribute in listOf("fill", "stroke", "filter", "clip-path", "mask", "marker-start", "marker-mid", "marker-end")) {
                        if (element.hasAttr(attribute)) element.attr(attribute, css(element.attr(attribute)))
                    }
                    if (element.hasAttr("srcset")) element.attr("srcset", srcset(element.attr("srcset")))
                }
                val wrapper = document.body().appendElement("section").attr("id", scope)
                    .attr("data-hoshi-source-index", index.toString())
                source.selectFirst("html")?.attributes()?.forEach { attribute ->
                    if (attribute.key in setOf("class", "lang", "xml:lang", "dir", "style")) {
                        wrapper.attr(attribute.key, if (attribute.key == "style") css(attribute.value) else attribute.value)
                    }
                    if (attribute.key == "id") {
                        wrapper.attr("data-hoshi-publisher-root-id", attribute.value)
                        wrapper.appendElement("span").attr("id", "$scope-${attribute.value}")
                    }
                }
                val body = Element("div").attr("data-hoshi-source-body", "").attr("id", "$scope-body")
                source.body().attributes().forEach { attribute ->
                    body.attr(attribute.key, attribute.value)
                }
                source.body().childNodes().toList().forEach { body.appendChild(it) }
                wrapper.appendChild(body)
            }
            return ContinuousGroupDocument(document.outerHtml(), sources)
        }
    }
}
