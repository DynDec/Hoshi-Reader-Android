package moe.antimony.hoshi.features.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.antimony.hoshi.profiles.ProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderProfileCompatibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun retiredAdaptiveFuriganaPreservesProfileSettingsOnLoadSaveAndReopen() = runBlocking {
        val profiles = ProfileRepository(temporaryFolder.newFolder("profiles"))
        profiles.readerSettingsFile().apply {
            parentFile?.mkdirs()
            writeText("""{"theme":"Dark","adaptiveFurigana":true,"furiganaMode":"Dimmed","removeVerticalBorders":true,"verticalPadding":12,"topSafeAreaDp":40,"bottomSafeAreaDp":46}""")
        }
        val store = InMemoryPreferences()
        val repository = ReaderSettingsRepository(store, profileRepository = profiles)
        val loaded = repository.settings.first()
        assertEquals(ReaderTheme.Dark, loaded.theme)
        assertEquals(FuriganaMode.Dimmed, loaded.furiganaMode)
        assertTrue(loaded.removeVerticalBorders)
        assertTrue(loaded.hideBookshelfProgress)

        repository.update { it.copy(fontSize = 30) }
        val reopened = ReaderSettingsRepository(store, profileRepository = profiles).settings.first()
        assertEquals(loaded.copy(fontSize = 30), reopened)
        val restored = reopened.copy(removeVerticalBorders = false)
        assertEquals(12, restored.effectiveVerticalPadding)
        assertEquals(40, restored.effectiveTopSafeAreaDp)
        assertEquals(46, restored.effectiveBottomSafeAreaDp)
    }

    // Exercise real profile JSON persistence independently of the host's
    // DataStore file replacement semantics.
    private class InMemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf(
            booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences") to true,
            booleanPreferencesKey("readerAdaptiveFurigana") to true,
            booleanPreferencesKey("hideBookshelfProgress") to true,
        ))
        private val mutex = Mutex()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
