package tachiyomi.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext

/** Where the sync file lives. [DriveFiles] in the app; a map in tests. */
interface RemoteFiles {

    /** A copy of the sync file and its version, which grows with every write anyone makes. */
    data class File(val id: String, val version: Long)

    /** Every copy of the sync file, newest first. Normally one; two devices creating it at once make two. */
    suspend fun list(): List<File>

    suspend fun download(id: String): ByteArray

    suspend fun version(id: String): Long

    suspend fun create(bytes: ByteArray): File

    suspend fun update(id: String, bytes: ByteArray): File

    suspend fun delete(id: String)
}

/**
 * The sync file in the app's hidden Drive folder (docs/DESKTOP_PLAN.md §3.4), through the Drive v3
 * REST API: four calls, so the Android app and the desktop one share this instead of each pulling in
 * Google's client library. Only the `drive.appdata` scope is needed: the app sees nothing else.
 *
 * [token] gives the access token of the platform's sign-in. A 401 throws [Unauthorized] for the
 * caller to get a fresh token and try again.
 */
class DriveFiles(
    private val client: OkHttpClient,
    private val token: suspend () -> String,
) : RemoteFiles {

    class Unauthorized : Exception("Drive refused the access token")

    class Failure(val code: Int, body: String) : Exception("Drive answered $code: ${body.take(MAX_ERROR_BODY)}")

    override suspend fun list(): List<RemoteFiles.File> {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("fields", "files(id,version)")
            .build()
        val files =
            call(Request.Builder().url(url).get()) { json.decodeFromString(FileList.serializer(), it.body.string()) }
        return files.files.map { it.toFile() }
    }

    override suspend fun download(id: String): ByteArray =
        call(Request.Builder().url("$API/files/$id?alt=media").get()) { it.body.bytes() }

    override suspend fun version(id: String): Long =
        call(Request.Builder().url("$API/files/$id?fields=id,version").get()) { file(it) }.version

    override suspend fun create(bytes: ByteArray): RemoteFiles.File {
        val metadata = json.encodeToString(Metadata.serializer(), Metadata(FILE_NAME, listOf("appDataFolder")))
        val body = MultipartBody.Builder()
            .setType(RELATED)
            .addPart(metadata.toRequestBody(JSON))
            .addPart(bytes.toRequestBody(OCTET_STREAM))
            .build()
        return call(Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id,version").post(body)) {
            file(it)
        }
    }

    override suspend fun update(id: String, bytes: ByteArray): RemoteFiles.File =
        call(
            Request.Builder()
                .url("$UPLOAD/files/$id?uploadType=media&fields=id,version")
                .patch(bytes.toRequestBody(OCTET_STREAM)),
        ) { file(it) }

    override suspend fun delete(id: String) {
        call(Request.Builder().url("$API/files/$id").delete()) {}
    }

    /** The address of the signed-in account, to show which Drive the file is in. */
    suspend fun account(): String? {
        val about = call(Request.Builder().url("$API/about?fields=user(emailAddress)").get()) {
            json.decodeFromString(About.serializer(), it.body.string())
        }
        return about.user?.emailAddress
    }

    private fun file(response: Response) = json.decodeFromString(
        DriveFile.serializer(),
        response.body.string(),
    ).toFile()

    private suspend fun <T> call(request: Request.Builder, read: (Response) -> T): T = withIOContext {
        val built = request.header("Authorization", "Bearer ${token()}").build()
        client.newCall(built).execute().use { response ->
            when {
                response.code == UNAUTHORIZED -> throw Unauthorized()
                !response.isSuccessful -> throw Failure(response.code, response.body.string())
                else -> read(response)
            }
        }
    }

    @Serializable
    private class FileList(val files: List<DriveFile> = emptyList())

    // Drive writes int64 fields as strings.
    @Serializable
    private class DriveFile(val id: String, val version: String = "0") {
        fun toFile() = RemoteFiles.File(id, version.toLong())
    }

    @Serializable
    private class Metadata(val name: String, val parents: List<String>)

    @Serializable
    private class About(val user: User? = null)

    @Serializable
    private class User(val emailAddress: String? = null)

    companion object {
        const val FILE_NAME = "mihon-sync.pb.gz"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val UNAUTHORIZED = 401
        private const val MAX_ERROR_BODY = 300
        private val RELATED = "multipart/related".toMediaType()
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
