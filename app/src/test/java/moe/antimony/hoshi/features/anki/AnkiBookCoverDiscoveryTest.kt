package moe.antimony.hoshi.features.anki

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiBookCoverDiscoveryTest {
    private val noteType = AnkiNoteType(7L, "Custom", listOf("Expression", "Source", "Artwork"))
    private val reference = """<img src="hoshi_cover_existing.png" />"""

    @Test
    fun usesMappedFieldPositionsAndRenderedTitleIncludingHtml() {
        val request = ankiBookCoverDiscoveryRequest(
            noteType,
            mapOf("Source" to "<b>{document-title}</b>", "Artwork" to "<div>{book-cover-reuse}</div>"),
        ) { it.replace("{document-title}", "Book & title") }!!
        assertEquals(AnkiBookCoverDiscoveryRequest(7L, 1, 2, "<b>Book & title</b>"), request)
        assertEquals(reference, ankiBookCoverFromFields("word\u001f<b>Book & title</b>\u001f<div>$reference</div>", request))
        assertNull(ankiBookCoverFromFields("word\u001fDifferent title\u001f$reference", request))
    }

    @Test
    fun inactiveFieldsAndMissingMappingsDoNotCreateDiscoveryRequests() {
        for (mappings in listOf(
            mapOf("MiscInfo" to "{document-title}", "Artwork" to "{book-cover-reuse}"),
            mapOf("Source" to "{document-title}", "Inactive" to "{book-cover-reuse}"),
            mapOf("Source" to "{document-title}{book-cover-reuse}"),
        )) assertNull(ankiBookCoverDiscoveryRequest(noteType, mappings) { "Book" })
        assertNull(ankiBookCoverDiscoveryRequest(noteType, mapOf(
            "Source" to "{document-title}", "Artwork" to "{book-cover-reuse}",
        )) { " " })
    }

    @Test
    fun extractsOnlyTheCoverFieldAndIgnoresBlankOrMalformedImages() {
        val request = AnkiBookCoverDiscoveryRequest(7L, 1, 2, "Book")
        for (cover in listOf("", "[sound:audio.mp3]", "<img src=''>", "<img data-src='other.png'>")) {
            assertNull(ankiBookCoverFromFields("$reference\u001fBook\u001f$cover", request))
        }
        assertNull(ankiBookCoverFromFields("word\u001fBook", request))
        for (tag in listOf("<IMG SRC='cover.png'>", "<img src=cover.png>")) {
            assertEquals(tag, ankiBookCoverFromFields("word\u001fBook\u001f<div>$tag</div>", request))
        }
    }

    @Test
    fun discoverySeedsCacheWithoutUploadingAndConcurrentExportsDiscoverOnlyOnce() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        val discoveries = AtomicInteger()
        val results = (1..12).map {
            async(Dispatchers.Default) {
                resolveAnkiBookCoverReferences(false, true, "book", AnkiBackendKind.AnkiDroid, store,
                    upload = { error("Discovery must avoid uploading") },
                    discover = { discoveries.incrementAndGet(); reference },
                ).reusedCover
            }
        }.awaitAll()
        assertEquals(listOf(reference), results.distinct())
        assertEquals(1, discoveries.get())
        assertEquals(reference, store.getOrUpload("book") { error("Must be persisted") })
    }

    @Test
    fun discoveryMissSharesNormalUploadForMixedPlaceholders() = runBlocking {
        var uploads = 0
        var discoveries = 0
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        val result = resolveAnkiBookCoverReferences(true, true, "book", AnkiBackendKind.AnkiDroid, store,
            upload = { uploads++; reference }, discover = { discoveries++; null },
        )
        assertEquals(reference, result.cover)
        assertEquals(reference, result.reusedCover)
        assertEquals(1, uploads)
        assertEquals(1, discoveries)
    }

    @Test
    fun discoveredCoverDoesNotReplaceLegacyUploadInMixedMappings() = runBlocking {
        val result = resolveAnkiBookCoverReferences(true, true, "book", AnkiBackendKind.AnkiDroid,
            AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences()),
            upload = { "<img src='new.png'>" }, discover = { reference },
        )
        assertEquals("<img src='new.png'>", result.cover)
        assertEquals(reference, result.reusedCover)
    }

    @Test
    fun connectInactiveMappingsAndMissingIdentityNeverDiscover() = runBlocking {
        for ((backend, active, bookId) in listOf(
            Triple(AnkiBackendKind.AnkiConnect, true, "book"),
            Triple(AnkiBackendKind.AnkiDroid, false, "book"),
            Triple(AnkiBackendKind.AnkiDroid, true, null),
        )) {
            resolveAnkiBookCoverReferences(false, active, bookId, backend,
                AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences()),
                upload = { reference }, discover = { error("Must not discover") },
            )
        }
    }
}
