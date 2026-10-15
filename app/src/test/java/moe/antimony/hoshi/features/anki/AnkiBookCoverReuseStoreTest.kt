package moe.antimony.hoshi.features.anki

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AnkiBookCoverReuseStoreTest {
    @Test
    fun uploadsOncePerBookAndKeepsBooksIndependent() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        var uploads = 0
        fun upload() = """<img src="cover_${++uploads}.jpeg" />"""

        val first = store.getOrUpload("book-a", ::upload)
        assertEquals(first, store.getOrUpload("book-a", ::upload))
        assertEquals("""<img src="cover_2.jpeg" />""", store.getOrUpload("book-b", ::upload))
        assertEquals(first, store.getOrUpload("book-a", ::upload))
        assertEquals(2, uploads)
    }

    @Test
    fun concurrentFirstExportsShareOneUpload() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        val uploads = AtomicInteger()
        val results = (1..12).map {
            async(Dispatchers.Default) {
                store.getOrUpload("book") {
                    """<img src="cover_${uploads.incrementAndGet()}.jpeg" />"""
                }
            }
        }.awaitAll()
        assertEquals(1, uploads.get())
        assertEquals(1, results.distinct().size)
    }

    @Test
    fun missingIdentityDoesNotUploadAndFailedUploadsAreRetried() = runBlocking {
        val store = AnkiBookCoverReuseStore(MemoryAnkiCoverPreferences())
        for (bookId in listOf(null, "", " ")) {
            assertNull(store.getOrUpload(bookId) { error("Must not upload without book identity") })
        }
        assertNull(store.getOrUpload("book") { null })
        assertNull(store.getOrUpload("book") { " " })
        val reference = """<img src="retry.jpeg" />"""
        assertEquals(reference, store.getOrUpload("book") { reference })
        assertEquals(reference, store.getOrUpload("book") { error("Must reuse saved reference") })
    }

    @Test
    fun cacheReadFailureDoesNotUpload() {
        val dataStore = object : DataStore<Preferences> by MemoryAnkiCoverPreferences() {
            override val data: Flow<Preferences> = flow { throw IOException("Read failed") }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                AnkiBookCoverReuseStore(dataStore).getOrUpload("book") { error("Must not upload") }
            }
        }
    }

    @Test
    fun cacheWriteFailureIsNotReportedAsSuccess() {
        val dataStore = object : DataStore<Preferences> by MemoryAnkiCoverPreferences() {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("Write failed")
        }
        var uploads = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                AnkiBookCoverReuseStore(dataStore).getOrUpload("book") {
                    uploads += 1
                    """<img src="cover.jpeg" />"""
                }
            }
        }
        assertEquals(1, uploads)
    }
}
