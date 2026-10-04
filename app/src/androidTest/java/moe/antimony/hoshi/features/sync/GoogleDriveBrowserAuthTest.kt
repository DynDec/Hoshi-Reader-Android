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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory authorization fixtures: never read or overwrite installed credentials. */
@RunWith(AndroidJUnit4::class)
class GoogleDriveBrowserAuthTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val clientId = "fixture.apps.googleusercontent.com"
    private fun auth(store: MemoryPreferences, id: String = clientId, exchange: suspend (Map<String, String>) -> BrowserTokenResponse = {
        BrowserTokenResponse("access", 3600, "refresh")
    }) = GoogleDriveBrowserAuth(ApplicationProvider.getApplicationContext(), store, id, exchange)

    @Test fun missingConfigurationAndCanceledAuthorizationNeverConnect() = runBlocking {
        val missing = auth(MemoryPreferences(), "")
        assertEquals(DriveAuthStatus.MissingConfiguration, missing.status())
        assertTrue(runCatching { missing.authorizationRequest() }.exceptionOrNull() is DriveAuthorizationRequiredException)
        val configured = auth(MemoryPreferences())
        configured.authorizationRequest()
        assertTrue(runCatching { configured.accept(null) }.exceptionOrNull() is DriveAuthorizationRequiredException)
        assertEquals(DriveAuthStatus.NotConnected, configured.status())
    }

    @Test fun rejectsWrongCallbackStateDuplicatesFragmentsAndDeniedConsent() = runBlocking {
        for (suffix in listOf("?code=c&state=wrong", "?code=c&state=s&state=s", "?code=c&code=d&state=s", "?code=c&state=s#fragment", "?error=access_denied&state=s")) {
            var exchanges = 0
            val configured = auth(MemoryPreferences()) { exchanges++; BrowserTokenResponse("access", 3600, "refresh") }
            val request = configured.authorizationRequest()
            val callback = request.redirectUri + suffix.replace("state=s", "state=${request.state}")
            assertTrue(runCatching { configured.accept(Intent().setData(Uri.parse(callback))) }.exceptionOrNull() is DriveAuthorizationRequiredException)
            assertEquals(0, exchanges)
            assertEquals(DriveAuthStatus.NotConnected, configured.status())
        }
        val configured = auth(MemoryPreferences())
        val request = configured.authorizationRequest()
        assertTrue(runCatching { configured.accept(Intent().setData(Uri.parse("other:/oauth2callback?code=c&state=${request.state}"))) }.exceptionOrNull() is DriveAuthorizationRequiredException)
    }

    @Test fun acceptedTokensPersistAcrossInstancesAndCallbackCannotReplay() = runBlocking {
        val store = MemoryPreferences()
        var exchanges = 0
        val configured = auth(store) { exchanges++; BrowserTokenResponse("access", 3600, "refresh") }
        val request = configured.authorizationRequest()
        val uri = request.toUri()
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(request.redirectUri, uri.getQueryParameter("redirect_uri"))
        assertEquals(GoogleDriveAuth.DriveFileScope, uri.getQueryParameter("scope"))
        val result = Intent().setData(Uri.parse("${request.redirectUri}?code=c&state=${request.state}"))
        configured.accept(result)
        assertEquals(DriveAuthStatus.Connected, auth(store).status())
        assertEquals("access", auth(store).accessToken())
        assertTrue(runCatching { configured.accept(result) }.exceptionOrNull() is DriveAuthorizationRequiredException)
        assertEquals(1, exchanges)
        assertEquals(DriveAuthStatus.NotConnected, auth(store, "different.apps.googleusercontent.com").status())
        configured.revokeAccess()
        assertEquals(DriveAuthStatus.NotConnected, auth(store).status())
    }
}
