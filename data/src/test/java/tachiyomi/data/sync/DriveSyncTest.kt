package tachiyomi.data.sync

import app.cash.sqldelight.db.QueryResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.sync.SyncEntity

/** The sync loop of S2 against a Drive kept in memory, shared by two devices. */
class DriveSyncTest {

    /** The app's hidden Drive folder. [beforeVersionCheck] runs once, as another device would. */
    private class FakeDrive : RemoteFiles {
        val files = LinkedHashMap<String, Pair<ByteArray, Long>>()
        var beforeVersionCheck: (suspend () -> Unit)? = null
        var writes = 0
        private var nextId = 0

        override suspend fun list() = files.entries.reversed().map { RemoteFiles.File(it.key, it.value.second) }
        override suspend fun download(id: String) = files.getValue(id).first
        override suspend fun version(id: String): Long {
            beforeVersionCheck?.let {
                beforeVersionCheck = null
                it()
            }
            return files.getValue(id).second
        }
        override suspend fun create(bytes: ByteArray): RemoteFiles.File {
            writes++
            val id = "file${nextId++}"
            files[id] = bytes to 1
            return RemoteFiles.File(id, 1)
        }
        override suspend fun update(id: String, bytes: ByteArray): RemoteFiles.File {
            writes++
            val version = files.getValue(id).second + 1
            files[id] = bytes to version
            return RemoteFiles.File(id, version)
        }
        override suspend fun delete(id: String) {
            files.remove(id)
        }
    }

    private class Device(name: String, drive: RemoteFiles) {
        val db = TestDatabase()
        val repository = SyncRepositoryImpl(db.database, db.driver)
        val sync = DriveSync(repository, drive, device = name, now = { 0 })

        fun long(sql: String): Long? = db.driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value
    }

    private val drive = FakeDrive()

    private suspend fun device(name: String) = Device(name, drive).also {
        it.repository.enable()
        it.repository.setClock(100)
        it.db.insertManga(1, url = "/batman")
        it.db.insertChapter(10, mangaId = 1, url = "/batman/1")
    }

    @Test
    fun `the first device creates the file, the second takes it in, and nothing more is written`() = runTest {
        val pc = device("pc")
        val phone = device("phone")
        pc.repository.setClock(1_000)
        pc.db.exec("UPDATE chapters SET read = 1 WHERE _id = 10")

        pc.sync.sync().uploaded shouldBe true
        drive.files.size shouldBe 1
        phone.sync.sync()
        phone.long("SELECT read FROM chapters WHERE _id = 10") shouldBe 1

        val again = pc.sync.sync()
        again.uploaded shouldBe false
        again.result.here.isEmpty shouldBe true
    }

    @Test
    fun `a file written by another device in the middle is merged before uploading`() = runTest {
        val pc = device("pc")
        val phone = device("phone")
        pc.sync.sync()
        phone.repository.setClock(2_000)
        phone.db.exec("UPDATE chapters SET bookmark = 1 WHERE _id = 10")
        pc.repository.setClock(3_000)
        pc.db.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        // The phone uploads its bookmark right after the PC downloaded the file.
        drive.beforeVersionCheck = { phone.sync.sync() }

        val outcome = pc.sync.sync()

        outcome.attempts shouldBe 2
        pc.long("SELECT bookmark FROM chapters WHERE _id = 10") shouldBe 1
        phone.sync.sync()
        phone.long("SELECT read FROM chapters WHERE _id = 10") shouldBe 1
    }

    @Test
    fun `two copies made at the same time are merged into one`() = runTest {
        val pc = device("pc")
        val phone = device("phone")
        pc.repository.setClock(1_000)
        pc.db.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        phone.repository.setClock(2_000)
        phone.db.exec("UPDATE chapters SET bookmark = 1 WHERE _id = 10")
        // Both find no file and create one.
        drive.create(SyncSnapshotCodec.encode(pc.repository.snapshot(), 0, "pc"))
        drive.create(SyncSnapshotCodec.encode(phone.repository.snapshot(), 0, "phone"))

        pc.sync.sync()

        drive.files.size shouldBe 1
        pc.long("SELECT bookmark FROM chapters WHERE _id = 10") shouldBe 1
        SyncSnapshotCodec.decode(drive.files.values.single().first).snapshot
            .alive(SyncEntity.CHAPTER).values.single()["read"] shouldBe "1"
    }

    @Test
    fun `a device that never gets a quiet moment gives up instead of overwriting`() = runTest {
        val pc = device("pc")
        pc.repository.setClock(1_000)
        pc.db.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        pc.sync.sync()
        pc.repository.setClock(2_000)
        pc.db.exec("UPDATE chapters SET bookmark = 1 WHERE _id = 10")
        val busy = object : RemoteFiles by drive {
            override suspend fun version(id: String) = drive.version(id) + 1
        }

        shouldThrow<DriveSync.Conflict> { DriveSync(pc.repository, busy, "pc") { 0 }.sync() }
        drive.writes shouldBe 1
    }

    @Test
    fun `the preview counts both sides without writing either`() = runTest {
        val pc = device("pc")
        val phone = device("phone")
        pc.repository.setClock(1_000)
        pc.db.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        pc.sync.sync()
        val writes = drive.writes

        val preview = phone.sync.preview()

        // An unread chapter is not in the phone's snapshot: the PC's read state arrives as a new record.
        preview.result.here.counts[SyncEntity.CHAPTER]?.total shouldBe 1
        preview.fileExists shouldBe true
        phone.long("SELECT read FROM chapters WHERE _id = 10") shouldBe 0
        drive.writes shouldBe writes
    }
}
