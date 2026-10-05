package moe.antimony.hoshi.features.anki

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MemoryAnkiCoverPreferences : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        lock.withLock { transform(data.value).also { data.value = it } }
}
