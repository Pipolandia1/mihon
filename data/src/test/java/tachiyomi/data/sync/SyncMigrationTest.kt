package tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.readingstats.TestDatabase

/** Migration 25 must leave an upgraded database with what a fresh install gets (S1). */
class SyncMigrationTest {

    @Test
    fun `migration 25 creates the sync tables a fresh install has`() {
        val fresh = TestDatabase()
        val upgraded = TestDatabase()
        upgraded.exec("DROP TABLE sync_state")
        upgraded.exec("DROP TABLE sync_control")

        Database.Schema.synchronous().migrate(upgraded.driver, 25, Database.Schema.version)

        Database.Schema.version shouldBe 26
        columns(upgraded, "sync_state") shouldBe columns(fresh, "sync_state")
        columns(upgraded, "sync_control") shouldBe columns(fresh, "sync_control")
        rows(upgraded, "SELECT id || ',' || applying || ',' || triggers_version FROM sync_control") shouldBe
            listOf("0,0,0")
        // No triggers come with the migration: sync is off until it is turned on.
        rows(upgraded, "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync_%'") shouldBe
            emptyList()
    }

    private fun columns(db: TestDatabase, table: String) = rows(
        db,
        """
        SELECT name || ' ' || type || ' ' || "notnull" || ' ' || coalesce(dflt_value, '') || ' ' || pk
        FROM pragma_table_info('$table')
        """.trimIndent(),
    )

    private fun rows(db: TestDatabase, sql: String): List<String> = db.driver.executeQuery(
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
