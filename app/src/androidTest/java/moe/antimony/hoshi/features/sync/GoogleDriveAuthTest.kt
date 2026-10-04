package moe.antimony.hoshi.features.sync

import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only in-memory credentials and token fixtures; never contacts Google. */
@RunWith(AndroidJUnit4::class)
class GoogleDriveAuthTest {
    @Test fun accountPickerConnectionRetainsTtuLogin() = runBlocking {
        val fixture = Fixture()
        fixture.connectBrowser()
        fixture.auth.accept()

        assertEquals(DriveAuthStatus.Connected, fixture.auth.status(SyncProvider.Ttu))
        assertEquals(DriveAuthStatus.Connected, fixture.auth.status(SyncProvider.Gdrive))
        assertEquals(DriveAuthStatus.NotConnected, fixture.browser.status())
        assertEquals(0, fixture.ttu.signOuts)
    }

    @Test fun browserConnectionRetainsTtuLoginAndSurvivesRecreation() = runBlocking {
        val fixture = Fixture()
        fixture.connectBrowser()
        val recreated = fixture.createAuth()

        assertEquals(DriveAuthStatus.Connected, recreated.status(SyncProvider.Ttu))
        assertEquals(DriveAuthStatus.Connected, recreated.status(SyncProvider.Gdrive))
        assertEquals(0, fixture.ttu.signOuts)
    }

    @Test fun switchingProviderRoutesTokensWithoutDisconnectingEitherLogin() = runBlocking {
        val fixture = Fixture()
        fixture.connectBrowser()
        repeat(2) {
            fixture.provider = SyncProvider.Ttu
            assertEquals(DriveAuthStatus.Connected, fixture.auth.status())
            assertEquals("ttu-access", fixture.auth.accessToken())
            fixture.provider = SyncProvider.Gdrive
            assertEquals(DriveAuthStatus.Connected, fixture.auth.status())
            assertEquals("hoshi-access", fixture.auth.accessToken())
        }
        assertEquals(0, fixture.ttu.signOuts)
    }

    @Test fun signOutDisconnectsOnlySelectedProviderForBothHoshiAuthMethods() = runBlocking {
        for (useBrowser in listOf(false, true)) {
            val fixture = Fixture()
            if (useBrowser) fixture.connectBrowser() else fixture.auth.accept()
            fixture.provider = SyncProvider.Ttu
            fixture.auth.revokeAccess()
            assertEquals(DriveAuthStatus.NotConnected, fixture.auth.status(SyncProvider.Ttu))
            assertEquals(DriveAuthStatus.Connected, fixture.auth.status(SyncProvider.Gdrive))

            fixture.ttu.connected = true
            fixture.provider = SyncProvider.Gdrive
            fixture.auth.revokeAccess()
            assertEquals(DriveAuthStatus.Connected, fixture.auth.status(SyncProvider.Ttu))
            assertEquals(DriveAuthStatus.NotConnected, fixture.auth.status(SyncProvider.Gdrive))
            assertEquals(1, fixture.ttu.signOuts)
        }
    }

    @Test fun canceledBrowserConnectionPreservesExistingLogins() = runBlocking {
        val fixture = Fixture()
        fixture.auth.accept()
        fixture.browser.authorizationRequest()
        assertTrue(runCatching { fixture.auth.acceptBrowserAuthorization(null) }.isFailure)

        assertEquals(DriveAuthStatus.Connected, fixture.auth.status(SyncProvider.Ttu))
        assertEquals(DriveAuthStatus.Connected, fixture.auth.status(SyncProvider.Gdrive))
        assertEquals(0, fixture.ttu.signOuts)
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        private val store = MemoryPreferences()
        private val browserStore = MemoryPreferences()
        val ttu = FakeTtuAuthorizer()
        var provider = SyncProvider.Gdrive
        val browser = GoogleDriveBrowserAuth(context, browserStore, "fixture.apps.googleusercontent.com") {
            BrowserTokenResponse("hoshi-access", 3600, "hoshi-refresh")
        }
        val auth = createAuth()

        fun createAuth() = GoogleDriveAuth(context, ttu, { provider }, browser, store, { false })

        suspend fun connectBrowser() {
            val request = browser.authorizationRequest()
            auth.acceptBrowserAuthorization(Intent().setData(Uri.parse("${request.redirectUri}?code=c&state=${request.state}")))
        }
    }

    private class FakeTtuAuthorizer : DriveAuthorizer {
        var connected = true
        var signOuts = 0
        override suspend fun accessToken(): String {
            check(connected)
            return "ttu-access"
        }
        override suspend fun status() = if (connected) DriveAuthStatus.Connected else DriveAuthStatus.NotConnected
        override suspend fun revokeAccess() {
            signOuts++
            connected = false
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
