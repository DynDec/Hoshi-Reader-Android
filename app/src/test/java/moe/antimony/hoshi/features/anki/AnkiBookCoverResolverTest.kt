package moe.antimony.hoshi.features.anki

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun cachedReferenceDoesNotConsultChangedOrMissingCover() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        val reference = """<img src="original.jpeg" />"""
        store.getOrUpload("book") { reference }
        val result = resolveAnkiBookCoverReferences(
            needsCover = false,
            needsReusedCover = true,
            bookId = "book",
            backendKind = AnkiBackendKind.AnkiDroid,
            reuseStore = store,
            upload = { error("Must not inspect or upload the source cover on a cache hit") },
        )
        assertNull(result.cover)
        assertEquals(reference, result.reusedCover)
    }

    @Test
    fun ankiConnectUsesNormalUploadWithoutReadingOrWritingReuseStore() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        store.getOrUpload("book") { """<img src="ankidroid.jpeg" />""" }
        var uploads = 0
        repeat(2) {
            val result = resolveAnkiBookCoverReferences(
                needsCover = true,
                needsReusedCover = true,
                bookId = "book",
                backendKind = AnkiBackendKind.AnkiConnect,
                reuseStore = store,
                upload = { """<img src="connect_${++uploads}.jpeg" />""" },
            )
            assertEquals("""<img src="connect_${it + 1}.jpeg" />""", result.reusedCover)
            assertEquals(result.cover, result.reusedCover)
        }
        assertEquals(2, uploads)
        assertEquals("""<img src="ankidroid.jpeg" />""", store.getOrUpload("book") { error("Must not overwrite cache") })
    }

    @Test
    fun inactiveCoverMappingsPerformNoUploadOrCacheAccess() = runBlocking {
        val result = resolveAnkiBookCoverReferences(
            needsCover = false,
            needsReusedCover = false,
            bookId = "book",
            backendKind = AnkiBackendKind.AnkiDroid,
            reuseStore = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences()),
            upload = { error("No active cover mappings") },
        )
        assertNull(result.cover)
        assertNull(result.reusedCover)
    }
}
