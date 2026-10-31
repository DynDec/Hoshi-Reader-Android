package moe.antimony.hoshi.features.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsCredentialCipherDeviceTest {
    @Test fun credentialsStayEncryptedAndAreBoundToTheirSource() = runBlocking {
        val cipher = TtsCredentialCipher(Dispatchers.IO)
        val encrypted = cipher.encrypt("device-test-value", TtsOnlineSource.Azure.id)
        assertFalse(encrypted.contains("device-test-value"))
        assertEquals("device-test-value", cipher.decrypt(encrypted, TtsOnlineSource.Azure.id))
        try { cipher.decrypt(encrypted, TtsOnlineSource.GoogleCloud.id); fail("Expected authenticated decryption failure") }
        catch (error: TtsException) { assertEquals(TtsFailure.Configuration, error.failure) }
    }
}
