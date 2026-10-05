package moe.antimony.hoshi.features.anki

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.antimony.hoshi.di.AnkiBookCoverReuseDataStore

@Singleton
internal class AnkiBookCoverReuseStore @Inject constructor(
    @param:AnkiBookCoverReuseDataStore private val dataStore: DataStore<Preferences>,
) {
    private val uploadMutex = Mutex()

    suspend fun getOrUpload(bookId: String?, upload: () -> String?): String? {
        if (bookId.isNullOrBlank()) return null
        return uploadMutex.withLock {
            val key = stringPreferencesKey(bookId)
            dataStore.data.first()[key]?.takeIf { it.isNotBlank() }
                ?: upload()?.takeIf { it.isNotBlank() }?.also { reference ->
                    // Media already exists even if adding the note later fails.
                    dataStore.edit { it[key] = reference }
                }
        }
    }
}
