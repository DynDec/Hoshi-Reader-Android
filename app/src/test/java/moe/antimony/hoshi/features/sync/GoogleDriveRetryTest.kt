package moe.antimony.hoshi.features.sync

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoogleDriveRetryTest {
    private class Token : DriveAccessTokenProvider {
        var cleared = 0
        override suspend fun accessToken() = "fixture-token"
        override suspend fun clearAccessToken(token: String) { cleared++ }
    }

    private class Response(private val status: Int, private val reason: String = "") : HttpURLConnection(URL("https://fixture.invalid")) {
        override fun setRequestMethod(value: String) { method = value }
        override fun getResponseCode() = status
        override fun getInputStream() = "ok".byteInputStream()
        override fun getErrorStream() = """{"error":{"errors":[{"reason":"$reason"}]}}""".byteInputStream()
        override fun disconnect() = Unit
        override fun connect() = Unit
        override fun usingProxy() = false
    }

    @Test fun rateLimitsAndServerReadsRetryButAmbiguousCreatesDoNot() = runTest {
        for ((method, status, reason, expected) in listOf(
            RetryCase("GET", 429, "", 5),
            RetryCase("PATCH", 403, "userRateLimitExceeded", 5),
            RetryCase("GET", 503, "", 5),
            RetryCase("POST", 503, "", 1),
            RetryCase("GET", 403, "insufficientPermissions", 1),
        )) {
            var requests = 0
            val client = GoogleDriveClient(Token(), { SyncProvider.Gdrive }, {}, StandardTestDispatcher(testScheduler)) {
                requests++; Response(status, reason)
            }
            val error = runCatching { client.performRequest("https://fixture.invalid", method) }.exceptionOrNull()
            assertTrue("$method $status: $error", error is GoogleDriveApiException)
            assertEquals(expected, requests)
        }
    }

    @Test fun authorizationRefreshIsLimitedToOneRetry() = runTest {
        var requests = 0
        val token = Token()
        val client = GoogleDriveClient(token, { SyncProvider.Gdrive }, {}, StandardTestDispatcher(testScheduler)) {
            requests++; Response(401)
        }
        assertTrue(runCatching { client.performRequest("https://fixture.invalid", "GET") }.exceptionOrNull() is GoogleDriveApiException)
        assertEquals(2, requests)
        assertEquals(1, token.cleared)
    }

    @Test fun cancellationDuringBackoffPreventsAnotherRequest() = runTest {
        var requests = 0
        val client = GoogleDriveClient(Token(), { SyncProvider.Gdrive }, {}, StandardTestDispatcher(testScheduler)) {
            requests++; Response(429)
        }
        val work = launch { client.performRequest("https://fixture.invalid", "GET") }
        runCurrent()
        assertEquals(1, requests)
        work.cancelAndJoin()
        testScheduler.advanceUntilIdle()
        assertEquals(1, requests)
    }

    @Test fun requestsShareAnEightConnectionLimit() = runBlocking {
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val client = GoogleDriveClient(Token(), { SyncProvider.Gdrive }, {}, Dispatchers.IO) {
            object : HttpURLConnection(URL("https://fixture.invalid")) {
                override fun getResponseCode(): Int {
                    val count = active.incrementAndGet()
                    maximum.accumulateAndGet(count, ::maxOf)
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return 200
                }
                override fun getInputStream() = "ok".byteInputStream()
                override fun disconnect() { active.decrementAndGet() }
                override fun connect() = Unit
                override fun usingProxy() = false
            }
        }
        val work = (0 until 16).map { async(Dispatchers.IO) { client.performRequest("https://fixture.invalid", "GET") } }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)) } finally { release.countDown() }
        work.awaitAll()
        assertEquals(8, maximum.get())
        assertEquals(0, active.get())
    }

    private data class RetryCase(val method: String, val status: Int, val reason: String, val expected: Int)
}
