package moe.antimony.hoshi.features.anki

internal data class AnkiBookCoverReferences(
    val cover: String?,
    val reusedCover: String?,
)

internal suspend fun resolveAnkiBookCoverReferences(
    needsCover: Boolean,
    needsReusedCover: Boolean,
    bookId: String?,
    backendKind: AnkiBackendKind,
    reuseStore: AnkiBookCoverReuseStore,
    upload: () -> String?,
): AnkiBookCoverReferences {
    var uploaded = false
    var freshReference: String? = null
    fun uploadOnce(): String? {
        if (!uploaded) {
            freshReference = upload()
            uploaded = true
        }
        return freshReference
    }

    val reusedCover = if (!needsReusedCover) {
        null
    } else if (backendKind == AnkiBackendKind.AnkiDroid) {
        reuseStore.getOrUpload(bookId, ::uploadOnce)
    } else {
        uploadOnce()
    }
    return AnkiBookCoverReferences(
        cover = if (needsCover) uploadOnce() else null,
        reusedCover = reusedCover,
    )
}
