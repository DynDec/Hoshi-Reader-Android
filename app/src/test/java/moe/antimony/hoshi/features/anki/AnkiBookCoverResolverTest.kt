package moe.antimony.hoshi.features.anki

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiBookCoverResolverTest {
    @Test
    fun mixedPlaceholdersShareFirstUploadThenKeepLegacyUploadsIndependent() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        var uploads = 0
        suspend fun resolve() = resolveAnkiBookCoverReferences(
            needsCover = true,
            needsReusedCover = true,
            bookId = "book",
            backendKind = AnkiBackendKind.AnkiDroid,
            reuseStore = store,
            upload = { """<img src="cover_${++uploads}.jpeg" />""" },
        )
        val first = resolve()
        assertEquals(first.cover, first.reusedCover)
        assertEquals(1, uploads)
        val second = resolve()
        assertEquals("""<img src="cover_2.jpeg" />""", second.cover)
        assertEquals(first.reusedCover, second.reusedCover)
        assertEquals(2, uploads)
    }
}
