package tachiyomi.data.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test

/** The Drive v3 calls, against answers shaped like Drive's own (docs/DESKTOP_PLAN.md §3.4). */
class DriveFilesTest {

    private val requests = mutableListOf<Pair<Request, String>>()
    private var answer: (Request) -> Pair<Int, String> = { 200 to "{}" }

    private val drive = DriveFiles(
        client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                    requests += request to body
                    val (code, content) = answer(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("")
                        .body(content.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build(),
        token = { "token-1" },
    )

    @Test
    fun `the file is looked for by name in the app folder only, newest first`() = runTest {
        answer = { 200 to """{"files":[{"id":"b","version":"7"},{"id":"a","version":"3"}]}""" }

        drive.list() shouldBe listOf(RemoteFiles.File("b", 7), RemoteFiles.File("a", 3))

        val (request) = requests.single()
        request.method shouldBe "GET"
        request.header("Authorization") shouldBe "Bearer token-1"
        request.url.queryParameter("spaces") shouldBe "appDataFolder"
        request.url.queryParameter("q") shouldBe "name = 'mihon-sync.pb.gz' and trashed = false"
        request.url.queryParameter("orderBy") shouldBe "modifiedTime desc"
    }

    @Test
    fun `a new file goes into the app folder as one multipart upload`() = runTest {
        answer = { 200 to """{"id":"new","version":"1"}""" }

        drive.create("hello".toByteArray()) shouldBe RemoteFiles.File("new", 1)

        val (request, body) = requests.single()
        request.method shouldBe "POST"
        request.url.toString() shouldContain "/upload/drive/v3/files?uploadType=multipart"
        request.body!!.contentType().toString() shouldContain "multipart/related"
        body shouldContain """{"name":"mihon-sync.pb.gz","parents":["appDataFolder"]}"""
        body shouldContain "hello"
    }

    @Test
    fun `an update replaces the content and returns the new version`() = runTest {
        answer = { 200 to """{"id":"a","version":"8"}""" }

        drive.update("a", "bytes".toByteArray()) shouldBe RemoteFiles.File("a", 8)

        val (request, body) = requests.single()
        request.method shouldBe "PATCH"
        request.url.encodedPath shouldBe "/upload/drive/v3/files/a"
        request.url.queryParameter("uploadType") shouldBe "media"
        body shouldBe "bytes"
    }

    @Test
    fun `download, version and account read what Drive sends`() = runTest {
        answer = { request ->
            when {
                request.url.queryParameter("alt") == "media" -> 200 to "content"
                request.url.encodedPath.endsWith("/about") -> 200 to """{"user":{"emailAddress":"me@example.com"}}"""
                else -> 200 to """{"id":"a","version":"12"}"""
            }
        }

        drive.download("a").decodeToString() shouldBe "content"
        drive.version("a") shouldBe 12
        drive.account() shouldBe "me@example.com"
    }

    @Test
    fun `an expired token and other refusals are told apart`() = runTest {
        answer = { 401 to """{"error":{"code":401}}""" }
        shouldThrow<DriveFiles.Unauthorized> { drive.list() }

        answer = { 403 to """{"error":{"code":403,"message":"The user has not granted the app access"}}""" }
        shouldThrow<DriveFiles.Failure> { drive.download("a") }.code shouldBe 403
    }
}
