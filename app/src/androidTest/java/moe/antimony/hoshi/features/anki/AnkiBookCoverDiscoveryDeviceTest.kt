package moe.antimony.hoshi.features.anki

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-provider benchmark. Only generated notes and private fixture files are changed. */
@RunWith(AndroidJUnit4::class)
class AnkiBookCoverDiscoveryDeviceTest {
    @Test
    fun discoversExistingCoverAndBenchmarksBoundedQueries() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("ankiCoverDiscoveryBenchmark") == "true")
        val context = instrumentation.targetContext
        val runId = System.currentTimeMillis().toString()
        val api = AndroidAnkiContentApi(context)
        val backend = AnkiDroidBackendAdapter(api)
        val createdIds = mutableListOf<Long>()
        val fixtureDir = File(context.noBackupFilesDir, "anki-cover-discovery-test-$runId").also { it.mkdirs() }
        val cover = File(context.cacheDir, "anki-media/discovery-$runId.png")
        val storeJobs = mutableListOf<kotlinx.coroutines.Job>()
        fun report(value: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$value\n") })
        }
        fun measure(label: String, action: () -> Unit) {
            repeat(3) { action() }
            val samples = (1..30).map {
                val start = SystemClock.elapsedRealtimeNanos()
                action()
                (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }.sorted()
            report("COVER_DISCOVERY $label median_ms=${samples[15]} p95_ms=${samples[28]} max_ms=${samples.last()}")
        }
        fun newStore(name: String): AnkiBookCoverReuseStore {
            val job = SupervisorJob().also(storeJobs::add)
            return AnkiBookCoverReuseStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { File(fixtureDir, "$name.preferences_pb") },
            ))
        }
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            val decks = backend.fetchDecks()
            report("COVER_DISCOVERY initial_provider_access_ms=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0}")
            val noteTypes = backend.fetchNoteTypes()
            val noteType = noteTypes.firstOrNull { it.name == "Basic" && it.fields.size >= 2 }
                ?: noteTypes.first { it.fields.size >= 2 }
            val deck = decks.firstOrNull { it.name == "Default" } ?: decks.first()
            cover.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            val coverUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cover)
            context.grantUriPermission("com.ichi2.anki", coverUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val reference = requireNotNull(backend.addMediaFromUri(
                coverUri.toString(),
                "hoshi_cover_discovery_$runId.png", "image/png",
            )) { "Uploading the test cover must succeed" }
            val padding = "x".repeat(5 * 1024)
            repeat(500) { index ->
                val fields = Array(noteType.fields.size) { "" }
                fields[0] = "hoshi_discovery_${runId}_$index"
                fields[1] = "<div>$padding</div>$reference"
                val id = api.addNote(noteType.id, deck.id, fields, setOf("hoshi_discovery_test_$runId"))
                assertTrue("Creating a test note must succeed", id != null)
                createdIds += id!!
                if ((index + 1) % 100 == 0) report("COVER_DISCOVERY seeded=${index + 1}")
            }
            val request = AnkiBookCoverDiscoveryRequest(noteType.id, 0, 1, "hoshi_discovery_${runId}_499")
            val missing = request.copy(titleFieldValue = "hoshi_discovery_missing_$runId")
            val baselineUri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, createdIds.last())
            measure("single_note_baseline") {
                context.contentResolver.query(baselineUri, arrayOf(FlashCardsContract.Note.FLDS), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertTrue(it.getString(0).isNotEmpty())
                }
            }
            for (limit in listOf(25, 50, 100, 200, 500)) {
                measure("N=$limit miss") { assertNull(api.findBookCoverReference(missing, limit)) }
                measure("N=$limit newest_hit") { assertEquals(reference, api.findBookCoverReference(request, limit)) }
            }
            val boundary = request.copy(titleFieldValue = "hoshi_discovery_${runId}_${500 - AnkiBookCoverDiscoveryLimit}")
            assertNull(api.findBookCoverReference(boundary, AnkiBookCoverDiscoveryLimit - 1))
            measure("N=$AnkiBookCoverDiscoveryLimit oldest_hit") {
                assertEquals(reference, api.findBookCoverReference(boundary, AnkiBookCoverDiscoveryLimit))
            }
            // A different model must never return these notes, even for the same title.
            noteTypes.firstOrNull { it.id != noteType.id }?.let { other ->
                assertNull(api.findBookCoverReference(request.copy(modelId = other.id), 100))
            }

            var uploads = 0
            var discoveries = 0
            val trackingApi = object : AnkiContentApi by api {
                override fun findBookCoverReference(request: AnkiBookCoverDiscoveryRequest, limit: Int): String? {
                    discoveries++
                    return api.findBookCoverReference(request, limit)
                }
                override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? {
                    uploads++
                    return api.addMediaFromUri(uriString, preferredName, mimeType)
                }
                override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? =
                    api.addNote(modelId, deckId, fields, tags).also { it?.let(createdIds::add) }
            }
            val format = AnkiCardFormat(
                id = "discovery-test", name = "Discovery test", selectedDeckId = deck.id, selectedNoteTypeId = noteType.id,
                fieldMappings = mapOf(noteType.fields[0] to "{document-title}", noteType.fields[1] to "{book-cover-reuse}"),
                tags = "hoshi_discovery_test_$runId",
            )
            val settings = object : AnkiSettingsRepository {
                override val settings = MutableStateFlow(AnkiSettings(cardFormats = listOf(format), allowDupes = true))
                override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) { settings.value = transform(settings.value) }
            }
            val store = newStore("discovered")
            val repository = AnkiRepository(context, AnkiDroidBackendAdapter(trackingApi), settings, LocalAudioRepository(context.filesDir), store)
            repeat(2) {
                assertTrue(repository.mineEntry("""{"expression":"discovery-test"}""",
                    AnkiMiningContext(sentence = "", bookId = "test-$runId", documentTitle = request.titleFieldValue),
                    decks, noteTypes, format.id,
                ))
                context.contentResolver.query(
                    ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, createdIds.last()),
                    arrayOf(FlashCardsContract.Note.FLDS), null, null, null,
                )!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(reference, cursor.getString(0).split('\u001f')[1])
                }
            }
            assertEquals(1, discoveries)
            assertEquals(0, uploads)
            measure("cached_reuse") {
                runBlocking { assertEquals(reference, store.getOrUpload("test-$runId") { error("Cache must be used") }) }
            }
            var benchmarkBook = 0
            measure("first_local_persistence_baseline") {
                runBlocking { assertEquals(reference, store.getOrUpload("baseline-${benchmarkBook++}") { reference }) }
            }
            measure("first_discovered_reuse_N=$AnkiBookCoverDiscoveryLimit") {
                runBlocking {
                    assertEquals(reference, resolveAnkiBookCoverReferences(false, true,
                        "discovered-${benchmarkBook++}", AnkiBackendKind.AnkiDroid, store,
                        upload = { error("Existing cover must avoid upload") },
                        discover = { api.findBookCoverReference(request, AnkiBookCoverDiscoveryLimit) },
                    ).reusedCover)
                }
            }
            report("COVER_DISCOVERY live_export_verified discoveries=$discoveries uploads=$uploads")
        } finally {
            storeJobs.forEach { it.cancelAndJoin() }
            try {
                createdIds.forEach { id ->
                    assertTrue("Delete only test-created notes", context.contentResolver.delete(
                        ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, id), null, null,
                    ) > 0)
                }
                report("COVER_DISCOVERY cleaned_test_notes=${createdIds.size}")
            } finally {
                cover.delete()
                context.revokeUriPermission(
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cover),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                fixtureDir.listFiles()?.forEach { it.delete() }
                fixtureDir.delete()
            }
        }
    }
}
