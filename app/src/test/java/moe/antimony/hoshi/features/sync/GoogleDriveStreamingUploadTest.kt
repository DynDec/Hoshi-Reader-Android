package moe.antimony.hoshi.features.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoogleDriveStreamingUploadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun largeFileUsesBoundedWritesAndFixedLengthStreaming() = runTest {
        val file = temporary.newFile("large.epub")
        RandomAccessFile(file, "rw").use { it.setLength(100_770_192L) }
        val response = Response(capture = false)
        val result = client(response).write(file, file.name, "parent")

        assertEquals("uploaded", result.id)
        assertEquals("POST", response.requestMethod)
        assertEquals(response.length, response.written)
        assertTrue(response.written > file.length())
        assertTrue("Upload must not write a whole-file buffer", response.maximumWrite <= 8192)
        assertTrue(response.closed)
        assertTrue(response.disconnected)
        assertTrue(file.delete())
    }

    @Test fun multipartPreservesBinaryBytesUnicodeMetadataAndEmptyFiles() = runTest {
        for (data in listOf(byteArrayOf(0, 1, -1, 13, 10, 127), byteArrayOf())) {
            val file = temporary.newFile()
            file.writeBytes(data)
            val response = Response()
            client(response).write(file, "挿絵.epub", "parent", contentType = "application/epub+zip")
            val boundary = response.getRequestProperty("Content-Type").substringAfter("boundary=")
            val prefix = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                "{\"name\":\"挿絵.epub\",\"parents\":[\"parent\"]}\r\n--$boundary\r\n" +
                "Content-Type: application/epub+zip\r\n\r\n").toByteArray()
            val suffix = "\r\n--$boundary--\r\n".toByteArray()
            assertArrayEquals(prefix + data + suffix, response.bytes.toByteArray())
            assertEquals(response.written, response.length)
        }
    }

    @Test fun retriesReplayTheEntireFileAfterAuthorizationAndRateLimitFailures() = runTest {
        val file = temporary.newFile().apply { writeBytes(ByteArray(25_000) { it.toByte() }) }
        val responses = arrayOf(Response(401), Response(429), Response())
        val token = Token()
        client(*responses, token = token).write(file, "book.epub", "parent")
        assertEquals(1, token.cleared)
        responses.forEach {
            assertEquals(it.length, it.written)
            assertArrayEquals(responses.last().bytes.toByteArray(), it.bytes.toByteArray())
            assertTrue(it.closed)
            assertTrue(it.disconnected)
        }
    }

    @Test fun patchRetriesServerFailureWithoutAddingParents() = runTest {
        val file = temporary.newFile().apply { writeText("payload") }
        val first = Response(503)
        val second = Response()
        client(first, second).write(file, "book.epub", "parent", fileId = "existing")
        assertEquals("PATCH", second.requestMethod)
        assertTrue(second.address.contains("/upload/drive/v3/files/existing?"))
        assertFalse(second.bytes.toString("UTF-8").contains("\"parents\""))
        assertArrayEquals(first.bytes.toByteArray(), second.bytes.toByteArray())
    }

    @Test fun smallStateWritesAlsoAvoidMultipartBufferCopies() = runTest {
        val response = Response()
        client(response).write("{\"state\":1}".toByteArray(), "state.json", "parent", "existing")
        assertEquals(response.length, response.written)
        assertTrue(response.bytes.toString("UTF-8").contains("{\"state\":1}"))
        assertEquals("PATCH", response.requestMethod)
    }

    @Test fun stoppedConnectionAbortsFileCopyAndClosesStreams() = runTest {
        val file = temporary.newFile().apply { writeBytes(ByteArray(50_000)) }
        val response = Response()
        val client = client(response)
        response.onWrite = { if (response.written > 8192) client.stop() }
        val error = runCatching { client.write(file, "book.epub", "parent") }.exceptionOrNull()
        assertTrue("$error", error is CancellationException)
        assertTrue(response.written < file.length())
        assertTrue(response.closed)
        assertTrue(response.disconnected)
        assertTrue(file.delete())
    }

    @Test fun coroutineCancellationAbortsFileCopy() = runTest {
        val file = temporary.newFile().apply { writeBytes(ByteArray(50_000)) }
        val response = Response()
        val client = client(response)
        lateinit var upload: Job
        response.onWrite = { if (response.written > 8192) upload.cancel() }
        upload = launch { client.write(file, "book.epub", "parent") }
        upload.join()
        assertTrue(upload.isCancelled)
        assertTrue(response.written < file.length())
        assertTrue(response.closed)
        assertTrue(response.disconnected)
        assertTrue(file.delete())
    }

    @Test fun failedWriteClosesStreamsAndDoesNotReportSuccess() = runTest {
        val file = temporary.newFile().apply { writeBytes(ByteArray(50_000)) }
        val response = Response()
        response.onWrite = { if (response.written > 8192) throw IOException("Connection lost") }
        val error = runCatching { client(response).write(file, "book.epub", "parent") }.exceptionOrNull()
        assertTrue("$error", error is GoogleDriveUnavailableException)
        assertTrue(response.closed)
        assertTrue(response.disconnected)
        assertTrue(file.delete())
    }

    private fun TestScope.client(vararg responses: Response, token: Token = Token()): GoogleDriveClient {
        val pending = responses.toMutableList()
        return GoogleDriveClient(token, { SyncProvider.Gdrive }, {}, StandardTestDispatcher(testScheduler)) { url ->
            check(pending.isNotEmpty()) { "Unexpected retry" }
            pending.removeAt(0).also { it.address = url }
        }
    }

    private class Token : DriveAccessTokenProvider {
        var cleared = 0
        override suspend fun accessToken() = "fixture-token"
        override suspend fun clearAccessToken(token: String) { cleared++ }
    }

    private class Response(private val status: Int = 200, private val capture: Boolean = true) :
        HttpURLConnection(URL("https://fixture.invalid")) {
        var address = ""
        var written = 0L
        var maximumWrite = 0
        var closed = false
        var disconnected = false
        val bytes = ByteArrayOutputStream()
        val length get() = fixedContentLengthLong
        var onWrite: () -> Unit = {}
        override fun setRequestMethod(value: String) { method = value }
        override fun getOutputStream(): OutputStream {
            check(length >= 0) { "HTTP body buffering must be disabled before opening output" }
            return object : OutputStream() {
                override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    written += count
                    maximumWrite = maxOf(maximumWrite, count)
                    if (capture) bytes.write(data, offset, count)
                    onWrite()
                }
                override fun close() { closed = true }
            }
        }
        override fun getResponseCode() = status
        override fun getInputStream() = """{"id":"uploaded","name":"book.epub","mimeType":"application/octet-stream","createdTime":"2020-01-01T00:00:00Z"}""".byteInputStream()
        override fun getErrorStream() = "{}".byteInputStream()
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
    }
}
