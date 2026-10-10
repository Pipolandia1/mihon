package tachiyomi.data.sync

import app.cash.sqldelight.db.QueryResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.data.readingstats.ReadingStatsFixtures
import tachiyomi.data.readingstats.ReadingStatsRepositoryImpl
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionWrite
import tachiyomi.domain.sync.SyncChanges
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncResult
import tachiyomi.domain.sync.SyncSnapshot

/**
 * Two devices, each with its own database, syncing through one file as they will through Drive
 * (docs/DESKTOP_PLAN.md §3.4, S1). Every change is made at a pinned time, so which came first is
 * part of the scenario, not an accident of the clock.
 */
class SyncScenariosTest {

    private class Device(val name: String) {
        val db = TestDatabase()
        val sync = SyncRepositoryImpl(db.database, db.driver)
        val stats = ReadingStatsRepositoryImpl(db.database)

        suspend fun at(time: Long, change: suspend Device.() -> Unit) {
            sync.setClock(time)
            change()
        }

        fun exec(sql: String) = db.exec(sql)

        fun long(sql: String): Long? = db.driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value

        fun strings(sql: String): List<String> = db.driver.executeQuery(
            null,
            sql,
            { cursor ->
                val values = mutableListOf<String>()
                while (cursor.next().value) values += cursor.getString(0).orEmpty()
                QueryResult.Value(values)
            },
            0,
        ).value
    }

    /** The Drive file. */
    private var file: ByteArray? = null

    private suspend fun Device.syncNow(): SyncResult {
        val remote = file?.let { SyncSnapshotCodec.decode(it).snapshot } ?: SyncSnapshot.EMPTY
        val result = sync.merge(remote)
        if (result.uploadNeeded) file = SyncSnapshotCodec.encode(result.merged, writtenAt = 0, device = name)
        return result
    }

    /** Two devices with sync on and their clocks pinned before anything is written. */
    private suspend fun devices(): Pair<Device, Device> {
        val pc = Device("pc")
        val phone = Device("phone")
        listOf(pc, phone).forEach {
            it.sync.enable()
            it.sync.setClock(100)
        }
        return pc to phone
    }

    @Test
    fun `read on the PC, then unread on the phone, ends unread on both`() = runTest {
        val (pc, phone) = devices()
        pc.db.insertManga(1, url = "/batman")
        pc.db.insertChapter(10, mangaId = 1, url = "/batman/1")
        // The same entry has other ids on the phone: only the URLs matter.
        phone.db.insertManga(7, url = "/batman")
        phone.db.insertChapter(70, mangaId = 7, url = "/batman/1")

        pc.at(1_000) { exec("UPDATE chapters SET read = 1 WHERE _id = 10") }
        pc.syncNow()
        phone.syncNow()
        phone.long("SELECT read FROM chapters WHERE _id = 70") shouldBe 1

        phone.at(2_000) { exec("UPDATE chapters SET read = 0 WHERE _id = 70") }
        phone.syncNow()
        pc.syncNow()

        pc.long("SELECT read FROM chapters WHERE _id = 10") shouldBe 0
        phone.long("SELECT read FROM chapters WHERE _id = 70") shouldBe 0
    }

    @Test
    fun `removed from the library on one side after being read on the other, out of it and still read`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach {
            it.db.insertManga(1, url = "/flashpoint")
            it.db.insertChapter(10, mangaId = 1, url = "/flashpoint/1")
        }
        pc.syncNow()
        phone.syncNow()

        phone.at(1_000) { exec("UPDATE chapters SET read = 1 WHERE _id = 10") }
        pc.at(2_000) { exec("UPDATE mangas SET favorite = 0 WHERE _id = 1") }
        phone.syncNow()
        pc.syncNow()
        phone.syncNow()

        listOf(pc, phone).forEach {
            it.long("SELECT favorite FROM mangas WHERE _id = 1") shouldBe 0
            it.long("SELECT read FROM chapters WHERE _id = 10") shouldBe 1
        }
    }

    @Test
    fun `a session deleted on one device is deleted on the other`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach {
            it.db.insertManga(1)
            it.db.insertChapter(10, mangaId = 1)
        }
        pc.at(1_000) { stats.saveSession(ReadingStatsFixtures.session("s1", chapterId = 10, activeMs = 60_000)) }
        pc.syncNow()
        phone.syncNow()
        phone.strings("SELECT session_uuid FROM reading_sessions") shouldContainExactly listOf("s1")
        phone.long("SELECT sum(active_ms) FROM reading_day_segments") shouldBe 60_000

        phone.at(2_000) { stats.deleteSession(long("SELECT _id FROM reading_sessions")!!) }
        phone.syncNow()
        pc.syncNow()

        pc.strings("SELECT session_uuid FROM reading_sessions").shouldBeEmpty()
    }

    @Test
    fun `a renamed category keeps its entries on the other device`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach { it.db.insertManga(1, url = "/batman") }
        pc.at(1_000) {
            exec("INSERT INTO categories(_id, name, sort, flags) VALUES (5, 'DC', 1, 0)")
            exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 5)")
        }
        val categoriesOfEntries = "SELECT C.name FROM mangas_categories MC JOIN categories C ON C._id = MC.category_id"
        pc.syncNow()
        phone.syncNow()
        phone.strings(categoriesOfEntries) shouldContainExactly listOf("DC")

        pc.at(2_000) { exec("UPDATE categories SET name = 'DC Comics' WHERE _id = 5") }
        pc.syncNow()
        phone.syncNow()

        phone.strings("SELECT name FROM categories WHERE _id > 0") shouldContainExactly listOf("DC Comics")
        phone.strings(categoriesOfEntries) shouldContainExactly listOf("DC Comics")
    }

    @Test
    fun `setting an entry's categories again within the same millisecond keeps it in them`() = runTest {
        val (pc, _) = devices()
        pc.db.insertManga(1)
        pc.at(1_000) {
            exec("INSERT INTO categories(_id, name, sort, flags) VALUES (5, 'DC', 1, 0)")
            // What Mihon does when the categories of an entry are set: all removed, then added.
            exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 5)")
            exec("DELETE FROM mangas_categories WHERE manga_id = 1")
            exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 5)")
        }

        pc.sync.snapshot().alive(SyncEntity.MANGA_CATEGORY).size shouldBe 1
    }

    @Test
    fun `an entry added on the PC appears on the phone, with what was read of it`() = runTest {
        val (pc, phone) = devices()
        pc.at(1_000) {
            db.insertManga(1, url = "/absolute-batman", title = "Absolute Batman", dateAdded = 900)
            db.insertChapter(10, mangaId = 1, url = "/absolute-batman/1", read = true)
            db.insertChapter(11, mangaId = 1, url = "/absolute-batman/2")
        }
        pc.syncNow()
        val result = phone.syncNow()

        phone.long("SELECT count(*) FROM mangas WHERE url = '/absolute-batman' AND favorite = 1") shouldBe 1
        phone.strings("SELECT title FROM mangas") shouldContainExactly listOf("Absolute Batman")
        // Only what was read travels; the source fills in the rest when the phone refreshes the entry.
        phone.strings("SELECT url FROM chapters WHERE read = 1") shouldContainExactly listOf("/absolute-batman/1")
        result.here.counts[SyncEntity.MANGA] shouldBe SyncChanges.Count(added = 1)
    }

    @Test
    fun `a chapter list fetched later does not undo what the other device read`() = runTest {
        val (pc, phone) = devices()
        pc.db.insertManga(1, url = "/batman")
        (1..3).forEach { pc.db.insertChapter(10L + it, mangaId = 1, url = "/batman/$it") }
        phone.db.insertManga(1, url = "/batman")
        pc.at(1_000) { exec("UPDATE chapters SET read = 1 WHERE url = '/batman/2'") }
        pc.syncNow()

        // The phone has the entry but not its chapters yet: the read state waits in the file.
        phone.syncNow().pending shouldBeGreaterThan 0
        phone.at(2_000) { (1..3).forEach { db.insertChapter(20L + it, mangaId = 1, url = "/batman/$it") } }
        phone.syncNow()

        phone.strings("SELECT url FROM chapters WHERE read = 1") shouldContainExactly listOf("/batman/2")
    }

    @Test
    fun `a chapter completed on both devices is completed first only once`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach {
            it.db.insertManga(1)
            it.db.insertChapter(10, mangaId = 1)
        }
        fun completing(uuid: String, at: Long): SessionWrite {
            val write = ReadingStatsFixtures.session(uuid, chapterId = 10, completedOn = DAY, endedAt = at)
            val s = write.session
            return SessionWrite(
                SessionSnapshot(
                    uuid = s.uuid, mangaId = s.mangaId, chapterId = s.chapterId, key = s.key, startedAt = s.startedAt,
                    endedAt = s.endedAt, activeMs = s.activeMs, firstPage = s.firstPage, lastPage = s.lastPage,
                    pagesVisited = s.pagesVisited, pagesNew = s.pagesNew, pagesReread = s.pagesReread,
                    pageAdvances = s.pageAdvances, totalPages = s.totalPages, pagesBits = s.pagesBits,
                    completedAt = at, completedOn = s.completedOn, firstCompletion = s.firstCompletion,
                    origin = s.origin, state = s.state, zoneId = s.zoneId, days = s.days,
                ),
                write.progress.copy(completedAt = at),
            )
        }
        pc.at(1_000) { stats.saveSession(completing("on-pc", at = 1_000)) }
        phone.at(2_000) { stats.saveSession(completing("on-phone", at = 2_000)) }

        pc.syncNow()
        phone.syncNow()
        pc.syncNow()

        listOf(pc, phone).forEach {
            it.strings("SELECT session_uuid FROM reading_sessions WHERE first_completion = 1") shouldContainExactly
                listOf("on-pc")
            it.long("SELECT completed_at FROM chapter_reading_progress WHERE chapter_id = 10") shouldBe 1_000
        }
    }

    @Test
    fun `goals, collections, plans, alerts, feedback, metadata, trackers and repositories come and go`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach {
            it.db.insertManga(1, url = "/batman")
            it.db.insertChapter(10, mangaId = 1, url = "/batman/1")
        }
        pc.at(1_000) {
            listOf(
                "INSERT INTO reading_goals VALUES (1, 2, 30, 1, 1000)",
                "INSERT INTO reading_collections(_id, uuid, kind, name, created_at) VALUES (3, 'c1', 0, 'K', 1000)",
                """
                INSERT INTO reading_collection_items(collection_id, position, manga_id, source_id, manga_url, label)
                VALUES (3, 0, 1, 1, '/batman', 'Batman')
                """,
                """
                INSERT INTO reading_plans(uuid, collection_id, pace_per_week, started_at, started_on, paused)
                VALUES ('p1', 3, 2, 1000, '2026-10-09', 0)
                """,
                "INSERT INTO release_follows VALUES (3, 'metron:arc:1606', 'Absolute Power', NULL, NULL, NULL, 1000)",
                "INSERT INTO release_notified VALUES ('metron:issue:9', 0, 'batman', 'Batman', 2025, '1', NULL, 1000)",
                "INSERT INTO start_feedback VALUES (0, '1', 1, NULL, 1000)",
                "INSERT INTO start_feedback VALUES (1, 'tom king', 1, NULL, 1000)",
                """
                INSERT INTO comic_metadata(manga_id, chapter_id, field, position, value, origin, confirmed)
                VALUES (1, NULL, 3, 0, 'Scott Snyder', 0, 1), (1, 10, 5, 0, 'Bruce Wayne', 0, 1)
                """,
                """
                INSERT INTO manga_sync(manga_id, sync_id, remote_id, title, last_chapter_read, total_chapters, status,
                    score, remote_url, start_date, finish_date)
                VALUES (1, 2, 99, 'Batman', 1, 12, 1, 8, 'u', 0, 0)
                """,
                """
                INSERT INTO extension_store
                VALUES ('https://repo/index.min.json', 'Repo', 'R', 'key', 'https://repo', NULL, 0, NULL)
                """,
            ).forEach { exec(it.trimIndent()) }
        }
        pc.syncNow()
        phone.syncNow()

        val counts = listOf(
            "SELECT count(*) FROM reading_goals",
            "SELECT count(*) FROM reading_collections",
            "SELECT count(*) FROM reading_collection_items WHERE manga_id = 1",
            "SELECT count(*) FROM reading_plans",
            "SELECT count(*) FROM release_follows",
            "SELECT count(*) FROM release_notified",
            "SELECT count(*) FROM start_feedback",
            "SELECT count(*) FROM comic_metadata WHERE confirmed = 1",
            "SELECT count(*) FROM manga_sync",
            "SELECT count(*) FROM extension_store",
        )
        counts.map { phone.long(it) } shouldContainExactly listOf(1L, 1, 1, 1, 1, 1, 2, 2, 1, 1)

        pc.at(2_000) {
            exec("DELETE FROM reading_goals")
            exec("DELETE FROM reading_plans")
            exec("DELETE FROM reading_collections")
            exec("DELETE FROM release_follows")
            exec("DELETE FROM start_feedback")
            exec("DELETE FROM comic_metadata")
            exec("DELETE FROM manga_sync")
            exec("DELETE FROM extension_store")
        }
        pc.syncNow()
        phone.syncNow()

        // Alerts already shown stay shown: they never sync a deletion.
        counts.map { phone.long(it) } shouldContainExactly listOf(0L, 0, 0, 0, 0, 1, 0, 0, 0, 0)
    }

    @Test
    fun `with nothing new on either side the two snapshots are the same and nothing is uploaded`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach {
            it.db.insertManga(1, url = "/batman")
            it.db.insertChapter(10, mangaId = 1, url = "/batman/1")
        }
        pc.at(1_000) { exec("UPDATE chapters SET read = 1, last_page_read = 12 WHERE _id = 10") }
        phone.at(2_000) { exec("UPDATE chapters SET bookmark = 1 WHERE _id = 10") }
        pc.syncNow()
        phone.syncNow()
        pc.syncNow()

        val again = phone.syncNow()

        again.uploadNeeded shouldBe false
        again.here.isEmpty shouldBe true
        pc.sync.snapshot() shouldBe phone.sync.snapshot()
    }

    @Test
    fun `a merge written here does not count as a change made here`() = runTest {
        val (pc, phone) = devices()
        listOf(pc, phone).forEach {
            it.db.insertManga(1, url = "/batman")
            it.db.insertChapter(10, mangaId = 1, url = "/batman/1")
        }
        pc.at(1_000) { exec("UPDATE chapters SET read = 1 WHERE _id = 10") }
        pc.syncNow()

        phone.at(5_000) { syncNow() }

        phone.long("SELECT modified_at FROM sync_state WHERE entity = 'chapter' AND field = 'read'") shouldBe 1_000
    }

    @Test
    fun `nothing is recorded while sync is off`() = runTest {
        val device = Device("pc")
        device.db.insertManga(1)
        device.db.insertChapter(10, mangaId = 1)

        device.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        device.long("SELECT count(*) FROM sync_state") shouldBe 0
        device.sync.isEnabled() shouldBe false

        device.sync.enable()
        device.at(1_000) { exec("UPDATE chapters SET read = 0 WHERE _id = 10") }
        device.long("SELECT count(*) FROM sync_state") shouldBe 1

        device.sync.disable()
        device.long("SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync_%'") shouldBe 0
        device.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        device.long("SELECT count(*) FROM sync_state") shouldBe 0
    }

    @Test
    fun `ten thousand chapters sync in under two seconds`() = runTest {
        val (pc, phone) = devices()
        val entries = 100
        val chaptersEach = 100
        listOf(pc, phone).forEach { device ->
            (1..entries).forEach { device.db.insertManga(it.toLong(), url = "/m/$it") }
            device.exec(
                """
                WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ${entries * chaptersEach})
                INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order,
                    date_fetch, date_upload)
                SELECT ((i - 1) / $chaptersEach) + 1, '/c/' || i, 'Chapter ' || i, ${if (device === pc) 1 else 0},
                    0, 0, i, 0, 0, 0
                FROM n
                """.trimIndent(),
            )
        }
        pc.syncNow()

        val start = System.nanoTime()
        phone.syncNow()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        phone.long("SELECT count(*) FROM chapters WHERE read = 1") shouldBe (entries * chaptersEach).toLong()
        elapsedMs shouldBeLessThan 2_000
    }

    private companion object {
        val DAY = LocalDate(2026, 10, 9)
    }
}
