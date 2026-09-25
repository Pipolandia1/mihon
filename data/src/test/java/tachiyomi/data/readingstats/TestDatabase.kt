package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.Properties

/** A fresh in-memory database with Mihon's whole schema, and helpers to fill it. */
class TestDatabase {

    val driver: SqlDriver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
    )

    val database = Database(
        driver = driver,
        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
            memoAdapter = MemoColumnAdapter,
        ),
        chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
    )

    init {
        Database.Schema.synchronous().create(driver)
    }

    fun insertManga(
        id: Long,
        url: String = "/manga/$id",
        favorite: Boolean = true,
        title: String = "Title $id",
        status: Int = 0,
        dateAdded: Long = 0,
    ) {
        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added)
            VALUES ($id, 1, '$url', '$title', $status, ${if (favorite) 1 else 0}, 1, 0, 0, 0, $dateAdded)
            """.trimIndent(),
        )
    }

    fun insertChapter(
        id: Long,
        mangaId: Long,
        url: String = "/chapter/$id",
        read: Boolean = false,
        lastPageRead: Int = 0,
        number: Double = id.toDouble(),
        scanlator: String? = null,
        name: String = "Chapter $id",
        dateFetch: Long = 0,
    ) {
        exec(
            """
            INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
                chapter_number, source_order, date_fetch, date_upload)
            VALUES ($id, $mangaId, '$url', '$name', ${scanlator?.let { "'$it'" } ?: "NULL"},
                ${if (read) 1 else 0}, 0, $lastPageRead, $number, 0, $dateFetch, 0)
            """.trimIndent(),
        )
    }

    fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }
}
