package moe.antimony.hoshi.features.bookshelf

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.testing.testPreferencesDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookshelfSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsIosDefaultsWhenNoSettingsWereSaved() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertEquals(BookSortOption.Recent, settings.sortOption)
            assertEquals(2, settings.bookColumns)
            assertFalse(settings.showReading)
            assertFalse(settings.hideCollapsedShelfThumbnails)
            assertEquals(BookshelfCoverMode.Show, settings.coverMode)
        }
    }

    @Test
    fun persistsSortOptionAndReadingShelfVisibility() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(sortOption = BookSortOption.Title, showReading = true) }

            val settings = repository.settings.first()

            assertEquals(BookSortOption.Title, settings.sortOption)
            assertTrue(settings.showReading)
        }
    }

    @Test
    fun persistsEveryBookshelfCoverMode() = runBlocking {
        repository().use { repository ->
            BookshelfCoverMode.entries.forEach { mode ->
                repository.update { it.copy(coverMode = mode) }

                assertEquals(mode, repository.settings.first().coverMode)
            }
        }
    }

    @Test
    fun invalidStoredCoverModeFallsBackToShow() = runBlocking {
        repository().use { repository ->
            repository.writeRawCoverMode("Unknown")

            assertEquals(BookshelfCoverMode.Show, repository.settings.first().coverMode)
        }
    }

    @Test
    fun restoresCollapsedThumbnailPreferenceAndPreservesItAcrossOtherUpdates() = runBlocking {
        val file = tempFolder.newFile("restored-settings.preferences_pb")
        val firstJob = Job()
        val firstStore = testPreferencesDataStore(
            scope = CoroutineScope(Dispatchers.IO + firstJob),
            produceFile = { file },
        )
        try {
            BookshelfSettingsRepository(firstStore).update { it.copy(hideCollapsedShelfThumbnails = true, bookColumns = 6) }
        } finally {
            firstJob.cancel()
            firstJob.join()
        }
        val secondJob = Job()
        val secondStore = testPreferencesDataStore(
            scope = CoroutineScope(Dispatchers.IO + secondJob),
            produceFile = { file },
        )
        try {
            val restored = BookshelfSettingsRepository(secondStore)
            assertTrue(restored.settings.first().hideCollapsedShelfThumbnails)
            assertEquals(6, restored.settings.first().bookColumns)
            restored.update { it.copy(sortOption = BookSortOption.Title, showReading = true, coverMode = BookshelfCoverMode.Hide) }
            assertTrue(restored.settings.first().hideCollapsedShelfThumbnails)
            restored.update { it.copy(hideCollapsedShelfThumbnails = false) }
            val settings = restored.settings.first()
            assertFalse(settings.hideCollapsedShelfThumbnails)
            assertEquals(BookSortOption.Title, settings.sortOption)
            assertTrue(settings.showReading)
            assertEquals(BookshelfCoverMode.Hide, settings.coverMode)
            assertEquals(6, settings.bookColumns)
            restored.update { it.copy(bookColumns = 2) }
            assertEquals(2, restored.settings.first().bookColumns)
        } finally {
            secondJob.cancel()
            secondJob.join()
        }
    }

    @Test
    fun invalidColumnCountsAreNormalizedWithoutCappingWideScreenPreferences() = runBlocking {
        repository().use { repository ->
            repository.writeRawBookColumns(-1)
            assertEquals(2, repository.settings.first().bookColumns)
            repository.update { it.copy(bookColumns = 0) }
            assertEquals(2, repository.settings.first().bookColumns)
            repository.update { it.copy(bookColumns = 10) }
            assertEquals(10, repository.settings.first().bookColumns)
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = testPreferencesDataStore(
            scope = scope,
            produceFile = { tempFolder.newFile("bookshelf-settings.preferences_pb") },
        )
        return RepositoryHandle(BookshelfSettingsRepository(dataStore), dataStore, scope)
    }

    private class RepositoryHandle(
        private val repository: BookshelfSettingsRepository,
        private val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (BookshelfSettings) -> BookshelfSettings) {
            repository.update(transform)
        }

        suspend fun writeRawCoverMode(value: String) {
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("bookshelfCoverMode")] = value
            }
        }

        suspend fun writeRawBookColumns(value: Int) {
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("bookshelfBookColumns")] = value
            }
        }

        override fun close() {
            scope.cancel()
        }
    }
}
