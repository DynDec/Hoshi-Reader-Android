package moe.antimony.hoshi.features.anki

/** Field positions belong to the selected note type, not to a preset's field names. */
data class AnkiBookCoverDiscoveryRequest(
    val modelId: Long,
    val titleFieldIndex: Int,
    val coverFieldIndex: Int,
    val titleFieldValue: String,
)

internal const val AnkiBookCoverDiscoveryLimit = 100

internal fun ankiBookCoverDiscoveryRequest(
    noteType: AnkiNoteType,
    fieldMappings: Map<String, String>,
    render: (String) -> String,
): AnkiBookCoverDiscoveryRequest? {
    val titleField = noteType.fields.firstOrNull { field ->
        fieldMappings[field]?.contains("{document-title}") == true
    } ?: return null
    val coverField = noteType.fields.firstOrNull { field ->
        fieldMappings[field]?.contains("{book-cover-reuse}") == true
    } ?: return null
    if (titleField == coverField) return null
    val titleValue = render(fieldMappings.getValue(titleField)).takeIf { it.isNotBlank() } ?: return null
    return AnkiBookCoverDiscoveryRequest(
        modelId = noteType.id,
        titleFieldIndex = noteType.fields.indexOf(titleField),
        coverFieldIndex = noteType.fields.indexOf(coverField),
        titleFieldValue = titleValue,
    )
}

/** Select IDs first so SQLite bounds the field payload before crossing the provider boundary. */
internal const val AnkiRecentBookCoverSelection =
    "id IN (SELECT id FROM notes WHERE mid = ? ORDER BY id DESC LIMIT ?)"

private val imageTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val imageSource = Regex("\\ssrc\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE)

internal fun ankiBookCoverFromFields(fields: String, request: AnkiBookCoverDiscoveryRequest): String? {
    if (ankiNoteFieldAt(fields, request.titleFieldIndex) != request.titleFieldValue) return null
    val cover = ankiNoteFieldAt(fields, request.coverFieldIndex) ?: return null
    return imageTag.findAll(cover).firstOrNull { tag ->
        imageSource.find(tag.value)?.groupValues?.drop(1)?.any { it.isNotBlank() } == true
    }?.value
}

private fun ankiNoteFieldAt(fields: String, index: Int): String? {
    if (index < 0) return null
    var start = 0
    repeat(index) {
        val separator = fields.indexOf('\u001f', start)
        if (separator < 0) return null
        start = separator + 1
    }
    val end = fields.indexOf('\u001f', start).takeIf { it >= 0 } ?: fields.length
    return fields.substring(start, end)
}
