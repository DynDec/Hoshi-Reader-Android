package moe.antimony.hoshi.testing

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * JVM tests see `Build.VERSION.SDK_INT == 0`, so DataStore's file storage falls back to
 * `File.renameTo`, which cannot replace an existing file on Windows. Okio storage moves atomically.
 */
internal fun testPreferencesDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { produceFile().toOkioPath() },
    scope = scope,
)
