package moe.antimony.hoshi.features.anki

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run first/restart phases in separate instrumentation processes. Preserves user settings and notes. */
@RunWith(AndroidJUnit4::class)
class AnkiBookCoverReuseDeviceTest {
    @Test
    fun miningReusesOneCoverAcrossProcessRestarts() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("ankiCoverReuseSmoke") == "true")
        val runId = arguments.getString("ankiCoverReuseRunId").orEmpty()
        require(runId.matches(Regex("[a-zA-Z0-9]{8,64}")))
        val phase = arguments.getString("ankiCoverReusePhase")
        require(phase == "first" || phase == "restart")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = AndroidAnkiContentApi(context)
        assertTrue("AnkiDroid must be initialized and grant Hoshi Debug database access", api.isAvailable())
        val createdIds = mutableListOf<Long>()
        var mediaUploads = 0
        val trackingApi = object : AnkiContentApi by api {
            override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? =
                api.addNote(modelId, deckId, fields, tags).also { id -> id?.let(createdIds::add) }
            override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? {
                mediaUploads += 1
                return api.addMediaFromUri(uriString, preferredName, mimeType)
            }
        }
        val backend = AnkiDroidBackendAdapter(trackingApi)
        val decks = backend.fetchDecks()
        val noteTypes = backend.fetchNoteTypes()
        val deck = decks.firstOrNull { it.name == "Default" } ?: decks.first()
        val noteType = noteTypes.firstOrNull { it.name == "Basic" && it.fields.size >= 2 }
            ?: noteTypes.first { it.fields.size >= 2 }
        val format = AnkiCardFormat(
            id = "cover-reuse-test",
            name = "Cover reuse test",
            selectedDeckId = deck.id,
            selectedNoteTypeId = noteType.id,
            fieldMappings = mapOf(noteType.fields[0] to "{expression}", noteType.fields[1] to "{book-cover-reuse}"),
            tags = "hoshi_cover_reuse_test_$runId",
        )
        val settings = object : AnkiSettingsRepository {
            override val settings = MutableStateFlow(AnkiSettings(cardFormats = listOf(format)))
            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
                settings.value = transform(settings.value)
            }
        }
        val fixtureDir = File(context.noBackupFilesDir, "anki-cover-reuse-smoke").also { it.mkdirs() }
        val cacheFile = File(fixtureDir, "$runId.preferences_pb")
        val expectedFile = File(fixtureDir, "$runId.reference")
        val cover = File(context.cacheDir, "anki-media/cover-reuse-$runId.png")
        val storeJob = SupervisorJob()
        try {
            if (phase == "first") {
                assertTrue("Use a fresh run ID", !cacheFile.exists() && !expectedFile.exists())
                cover.parentFile!!.mkdirs()
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.rgb(runId.hashCode() and 255, 80, 160))
                    cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
            } else {
                assertTrue("First phase must have saved a reference", expectedFile.isFile && cacheFile.isFile)
            }
            val store = AnkiBookCoverReuseStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(storeJob + Dispatchers.IO),
                produceFile = { cacheFile },
            ))
            val repository = AnkiRepository(
                context, backend, settings, LocalAudioRepository(context.filesDir), store,
            )
            var expected = if (phase == "restart") expectedFile.readText() else null
            repeat(if (phase == "first") 2 else 1) { index ->
                val expression = "hoshi_cover_reuse_${runId}_${phase}_$index"
                assertTrue(repository.mineEntry(
                    rawPayload = """{"expression":"$expression"}""",
                    context = AnkiMiningContext(
                        sentence = "",
                        bookId = "smoke-$runId",
                        coverPath = cover.absolutePath.takeIf { phase == "first" },
                    ),
                    decks = decks,
                    noteTypes = noteTypes,
                    formatId = format.id,
                ))
                val uri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, createdIds.last())
                context.contentResolver.query(uri, arrayOf(FlashCardsContract.Note.FLDS), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val fields = cursor.getString(0).split('\u001f')
                    assertEquals(expression, fields[0])
                    assertTrue("Cover must reference uploaded Anki media: ${fields[1]}", fields[1].startsWith("<img src=\"hoshi_cover_"))
                    if (expected == null) {
                        expected = fields[1]
                        expectedFile.writeText(fields[1])
                    }
                    assertEquals(expected, fields[1])
                }
            }
            assertEquals(if (phase == "first") 1 else 0, mediaUploads)
        } finally {
            storeJob.cancelAndJoin()
            try {
                createdIds.forEach { id ->
                    val uri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, id)
                    assertTrue("Delete only the note created by this test", context.contentResolver.delete(uri, null, null) > 0)
                }
            } finally {
                cover.delete()
                if (phase == "restart") {
                    cacheFile.delete()
                    expectedFile.delete()
                }
            }
        }
    }
}
