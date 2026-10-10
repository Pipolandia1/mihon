package tachiyomi.data.sync

import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncFields

/**
 * The triggers that record in sync_state when each synced field changes on this device
 * (docs/DESKTOP_PLAN.md §3.4).
 *
 * They are not part of the schema: [SyncRepositoryImpl.enable] installs them when sync is turned on
 * and [SyncRepositoryImpl.disable] drops them, so until then every write is exactly what it was
 * before sync existed. Writing them here rather than in a migration also keeps them in one place
 * for the Android app and the desktop one.
 *
 * Every trigger stays quiet while a merge is being written (sync_control.applying), and records
 * nothing when the row it would key on is gone: a chapter deleted with its entry leaves no
 * tombstone, because cleaning up a local database is not something to repeat on the other devices.
 * An insert records only what differs from a fresh row, so fetching a chapter list never looks like
 * marking every chapter unread.
 */
internal object SyncTriggers {

    /** Bump when a trigger changes: [SyncRepositoryImpl.enable] reinstalls them all. */
    const val VERSION = 1L

    private const val QUIET = "(SELECT applying FROM sync_control WHERE id = 0) = 0"

    private const val NOW = "coalesce((SELECT fixed_now FROM sync_control WHERE id = 0), " +
        "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))"

    private fun source(alias: String) = "CAST($alias.source AS TEXT)"

    /** The key of an entry, from a joined `mangas M`. */
    private val ENTRY = listOf(source("M"), "M.url")

    /** The key of a chapter, from a joined `chapters C JOIN mangas M`. */
    private val CHAPTER = ENTRY + "C.url"

    private fun chapterOf(row: String) = "chapters C JOIN mangas M ON M._id = C.manga_id" to "C._id = $row.chapter_id"

    private fun keyColumns(keys: List<String>) = (keys + List(4 - keys.size) { "''" }).joinToString()

    /** Records [field] of [entity] as changed now, once per row of [from] matching [where]. */
    private fun mark(
        entity: SyncEntity,
        keys: List<String>,
        field: String,
        from: String? = null,
        where: String? = null,
    ): String = buildString {
        append("INSERT OR REPLACE INTO sync_state(entity, k1, k2, k3, k4, field, modified_at) ")
        append("SELECT '${entity.id}', ${keyColumns(keys)}, '$field', $NOW")
        if (from != null) append(" FROM $from")
        if (where != null) append(" WHERE $where")
        append(";")
    }

    private fun tombstone(entity: SyncEntity, keys: List<String>, from: String? = null, where: String? = null) =
        mark(entity, keys, SyncFields.DELETED, from, where)

    /**
     * Forgets the tombstone of a row created again. Without it a row deleted and inserted within the
     * same millisecond, as Mihon does when it sets an entry's categories, would read as deleted.
     */
    private fun revive(entity: SyncEntity, keys: List<String>, from: String? = null, where: String? = null) =
        buildString {
            append("DELETE FROM sync_state WHERE entity = '${entity.id}' AND field = '${SyncFields.DELETED}' ")
            append("AND (k1, k2, k3, k4) IN (SELECT ${keyColumns(keys)}")
            if (from != null) append(" FROM $from")
            if (where != null) append(" WHERE $where")
            append(");")
        }

    private fun changed(vararg columns: String) = columns.joinToString(" OR ") { "old.$it IS NOT new.$it" }

    private fun trigger(name: String, event: String, condition: String? = null, vararg body: String): Trigger {
        val whenClause = listOfNotNull(QUIET, condition).joinToString(" AND ") { "($it)" }
        return Trigger(name, "CREATE TRIGGER $name $event WHEN $whenClause BEGIN\n${body.joinToString("\n")}\nEND")
    }

    data class Trigger(val name: String, val sql: String)

    private val MANGA_LIBRARY = arrayOf("favorite", "date_added", "viewer", "chapter_flags", "notes")

    private val MANGA_INFO = arrayOf(
        "title", "author", "artist", "description", "genre", "status", "thumbnail_url", "update_strategy",
        "initialized",
    )

    private val TRACK_COLUMNS = arrayOf(
        "remote_id", "library_id", "title", "last_chapter_read", "total_chapters", "status", "score", "remote_url",
        "start_date", "finish_date", "private",
    )

    private val STORE_COLUMNS = arrayOf(
        "name",
        "badge_label",
        "signing_key",
        "contact_website",
        "contact_discord",
        "is_legacy",
        "extension_list_url",
    )

    private val SESSION_COLUMNS = arrayOf(
        "source_id", "manga_url", "chapter_url", "manga_title", "chapter_name", "chapter_number", "started_at",
        "ended_at", "active_ms", "first_page", "last_page", "pages_visited", "pages_new", "pages_reread",
        "page_advances", "total_pages", "pages_bits", "completed_at", "completed_on", "origin", "state", "zone_id",
    )

    val all: List<Trigger> = buildList {
        // Entries.
        val entry = listOf(source("new"), "new.url")
        add(
            trigger(
                "sync_mangas_insert",
                "AFTER INSERT ON mangas",
                null,
                mark(SyncEntity.MANGA, entry, SyncFields.FAVORITE, where = "new.favorite = 1"),
                mark(SyncEntity.MANGA, entry, SyncFields.DATE_ADDED, where = "new.date_added > 0"),
                mark(SyncEntity.MANGA, entry, SyncFields.SETTINGS, where = "new.viewer != 0 OR new.chapter_flags != 0"),
                mark(SyncEntity.MANGA, entry, SyncFields.NOTES, where = "new.notes != ''"),
            ),
        )
        add(
            trigger(
                "sync_mangas_update",
                "AFTER UPDATE OF ${(MANGA_LIBRARY + MANGA_INFO).joinToString()} ON mangas",
                null,
                mark(SyncEntity.MANGA, entry, SyncFields.FAVORITE, where = changed("favorite")),
                mark(SyncEntity.MANGA, entry, SyncFields.DATE_ADDED, where = changed("date_added")),
                mark(SyncEntity.MANGA, entry, SyncFields.SETTINGS, where = changed("viewer", "chapter_flags")),
                mark(SyncEntity.MANGA, entry, SyncFields.NOTES, where = changed("notes")),
                mark(SyncEntity.MANGA, entry, SyncFields.INFO, where = changed(*MANGA_INFO)),
            ),
        )

        // Chapters.
        val chapter = ENTRY + "new.url"
        val entryOfChapter = "mangas M"
        add(
            trigger(
                "sync_chapters_insert",
                "AFTER INSERT ON chapters",
                null,
                mark(
                    SyncEntity.CHAPTER,
                    chapter,
                    SyncFields.READ,
                    entryOfChapter,
                    "M._id = new.manga_id AND new.read = 1",
                ),
                mark(
                    SyncEntity.CHAPTER,
                    chapter,
                    SyncFields.BOOKMARK,
                    entryOfChapter,
                    "M._id = new.manga_id AND new.bookmark = 1",
                ),
                mark(
                    SyncEntity.CHAPTER,
                    chapter,
                    SyncFields.PAGE,
                    entryOfChapter,
                    "M._id = new.manga_id AND new.last_page_read > 0",
                ),
            ),
        )
        add(
            trigger(
                "sync_chapters_update",
                "AFTER UPDATE OF read, bookmark, last_page_read ON chapters",
                null,
                mark(
                    SyncEntity.CHAPTER,
                    chapter,
                    SyncFields.READ,
                    entryOfChapter,
                    "M._id = new.manga_id AND (${changed("read")})",
                ),
                mark(
                    SyncEntity.CHAPTER,
                    chapter,
                    SyncFields.BOOKMARK,
                    entryOfChapter,
                    "M._id = new.manga_id AND (${changed("bookmark")})",
                ),
                mark(
                    SyncEntity.CHAPTER,
                    chapter,
                    SyncFields.PAGE,
                    entryOfChapter,
                    "M._id = new.manga_id AND (${changed("last_page_read")})",
                ),
            ),
        )

        // History.
        val (historyFrom, newHistory) = chapterOf("new")
        val (_, oldHistory) = chapterOf("old")
        add(
            trigger(
                "sync_history_insert",
                "AFTER INSERT ON history",
                null,
                revive(SyncEntity.HISTORY, CHAPTER, historyFrom, newHistory),
                mark(SyncEntity.HISTORY, CHAPTER, SyncFields.LAST_READ, historyFrom, newHistory),
                mark(SyncEntity.HISTORY, CHAPTER, SyncFields.TIME_READ, historyFrom, newHistory),
            ),
        )
        add(
            trigger(
                "sync_history_update",
                "AFTER UPDATE OF last_read, time_read ON history",
                null,
                mark(
                    SyncEntity.HISTORY,
                    CHAPTER,
                    SyncFields.LAST_READ,
                    historyFrom,
                    "$newHistory AND (${changed("last_read")})",
                ),
                mark(
                    SyncEntity.HISTORY,
                    CHAPTER,
                    SyncFields.TIME_READ,
                    historyFrom,
                    "$newHistory AND (${changed("time_read")})",
                ),
            ),
        )
        add(
            trigger(
                "sync_history_delete",
                "AFTER DELETE ON history",
                null,
                tombstone(SyncEntity.HISTORY, CHAPTER, historyFrom, oldHistory),
            ),
        )

        // Categories, by name. The default category (id 0) is every device's own.
        val pairsOf = { category: String ->
            "mangas_categories MC JOIN mangas M ON M._id = MC.manga_id" to
                "MC.category_id = $category"
        }
        val (pairsFrom, pairsOfNew) = pairsOf("new._id")
        val (_, pairsOfOld) = pairsOf("old._id")
        add(
            trigger(
                "sync_categories_insert",
                "AFTER INSERT ON categories",
                "new._id > 0",
                revive(SyncEntity.CATEGORY, listOf("new.name")),
                mark(SyncEntity.CATEGORY, listOf("new.name"), SyncFields.ROW),
            ),
        )
        add(
            trigger(
                "sync_categories_update",
                "AFTER UPDATE OF sort, flags ON categories",
                "new._id > 0 AND old.name IS new.name AND (${changed("sort", "flags")})",
                mark(SyncEntity.CATEGORY, listOf("new.name"), SyncFields.ROW),
            ),
        )
        // A rename is the old name deleted and the new one created, entries and all.
        add(
            trigger(
                "sync_categories_rename",
                "AFTER UPDATE OF name ON categories",
                "new._id > 0 AND old.name IS NOT new.name",
                tombstone(SyncEntity.CATEGORY, listOf("old.name")),
                revive(SyncEntity.CATEGORY, listOf("new.name")),
                mark(SyncEntity.CATEGORY, listOf("new.name"), SyncFields.ROW),
                tombstone(SyncEntity.MANGA_CATEGORY, ENTRY + "old.name", pairsFrom, pairsOfNew),
                revive(SyncEntity.MANGA_CATEGORY, ENTRY + "new.name", pairsFrom, pairsOfNew),
                mark(SyncEntity.MANGA_CATEGORY, ENTRY + "new.name", SyncFields.MEMBER, pairsFrom, pairsOfNew),
            ),
        )
        // Before the delete, while its entries can still be listed: they leave it on every device.
        add(
            trigger(
                "sync_categories_delete",
                "BEFORE DELETE ON categories",
                "old._id > 0",
                tombstone(SyncEntity.CATEGORY, listOf("old.name")),
                tombstone(SyncEntity.MANGA_CATEGORY, ENTRY + "old.name", pairsFrom, pairsOfOld),
            ),
        )

        // An entry's categories.
        val pair = ENTRY + "CA.name"
        val pairFrom = "mangas M, categories CA"
        val newPair = "M._id = new.manga_id AND CA._id = new.category_id AND CA._id > 0"
        val oldPair = "M._id = old.manga_id AND CA._id = old.category_id AND CA._id > 0"
        add(
            trigger(
                "sync_mangas_categories_insert",
                "AFTER INSERT ON mangas_categories",
                null,
                revive(SyncEntity.MANGA_CATEGORY, pair, pairFrom, newPair),
                mark(SyncEntity.MANGA_CATEGORY, pair, SyncFields.MEMBER, pairFrom, newPair),
            ),
        )
        add(
            trigger(
                "sync_mangas_categories_delete",
                "AFTER DELETE ON mangas_categories",
                null,
                tombstone(SyncEntity.MANGA_CATEGORY, pair, pairFrom, oldPair),
            ),
        )

        // Trackers.
        val newTrack = ENTRY + "CAST(new.sync_id AS TEXT)"
        val oldTrack = ENTRY + "CAST(old.sync_id AS TEXT)"
        add(
            trigger(
                "sync_manga_sync_insert",
                "AFTER INSERT ON manga_sync",
                null,
                revive(SyncEntity.TRACK, newTrack, "mangas M", "M._id = new.manga_id"),
                mark(SyncEntity.TRACK, newTrack, SyncFields.ROW, "mangas M", "M._id = new.manga_id"),
            ),
        )
        add(
            trigger(
                "sync_manga_sync_update",
                "AFTER UPDATE OF ${TRACK_COLUMNS.joinToString()} ON manga_sync",
                changed(*TRACK_COLUMNS),
                mark(SyncEntity.TRACK, newTrack, SyncFields.ROW, "mangas M", "M._id = new.manga_id"),
            ),
        )
        add(
            trigger(
                "sync_manga_sync_delete",
                "AFTER DELETE ON manga_sync",
                null,
                tombstone(SyncEntity.TRACK, oldTrack, "mangas M", "M._id = old.manga_id"),
            ),
        )

        // Extension repositories.
        add(
            trigger(
                "sync_extension_store_insert",
                "AFTER INSERT ON extension_store",
                null,
                revive(SyncEntity.EXTENSION_STORE, listOf("new.index_url")),
                mark(SyncEntity.EXTENSION_STORE, listOf("new.index_url"), SyncFields.ROW),
            ),
        )
        add(
            trigger(
                "sync_extension_store_update",
                "AFTER UPDATE OF ${STORE_COLUMNS.joinToString()} ON extension_store",
                changed(*STORE_COLUMNS),
                mark(SyncEntity.EXTENSION_STORE, listOf("new.index_url"), SyncFields.ROW),
            ),
        )
        add(
            trigger(
                "sync_extension_store_delete",
                "AFTER DELETE ON extension_store",
                null,
                tombstone(SyncEntity.EXTENSION_STORE, listOf("old.index_url")),
            ),
        )

        // Reading sessions and their days. first_completion and the local ids are not synced.
        add(
            trigger(
                "sync_reading_sessions_insert",
                "AFTER INSERT ON reading_sessions",
                null,
                revive(SyncEntity.SESSION, listOf("new.session_uuid")),
                mark(SyncEntity.SESSION, listOf("new.session_uuid"), SyncFields.ROW),
            ),
        )
        add(
            trigger(
                "sync_reading_sessions_update",
                "AFTER UPDATE OF ${SESSION_COLUMNS.joinToString()} ON reading_sessions",
                null,
                mark(SyncEntity.SESSION, listOf("new.session_uuid"), SyncFields.ROW),
            ),
        )
        add(
            trigger(
                "sync_reading_sessions_delete",
                "AFTER DELETE ON reading_sessions",
                null,
                tombstone(SyncEntity.SESSION, listOf("old.session_uuid")),
            ),
        )
        listOf("insert" to "new", "update" to "new", "delete" to "old").forEach { (event, row) ->
            add(
                trigger(
                    "sync_reading_day_segments_$event",
                    "AFTER ${event.uppercase()} ON reading_day_segments",
                    null,
                    mark(
                        SyncEntity.SESSION,
                        listOf("S.session_uuid"),
                        SyncFields.ROW,
                        "reading_sessions S",
                        "S._id = $row.session_id",
                    ),
                ),
            )
        }

        // Chapter progress of the Stats tab.
        listOf("insert", "update").forEach { event ->
            val (from, where) = chapterOf("new")
            add(
                trigger(
                    "sync_chapter_reading_progress_$event",
                    "AFTER ${event.uppercase()} ON chapter_reading_progress",
                    null,
                    mark(SyncEntity.CHAPTER_PROGRESS, CHAPTER, SyncFields.ROW, from, where),
                ),
            )
        }

        // When recording started.
        listOf("insert", "update").forEach { event ->
            add(
                trigger(
                    "sync_reading_stats_meta_$event",
                    "AFTER ${event.uppercase()} ON reading_stats_meta",
                    null,
                    mark(SyncEntity.STATS_META, listOf("new.key"), SyncFields.VALUE),
                ),
            )
        }

        // Rows that sync whole: goals, collections, plans, follows, notified alerts.
        fun whole(table: String, entity: SyncEntity, key: (String) -> List<String>, children: String? = null) {
            add(
                trigger(
                    "sync_${table}_insert",
                    "AFTER INSERT ON $table",
                    null,
                    *listOfNotNull(
                        if (entity.tombstones) revive(entity, key("new")) else null,
                        mark(entity, key("new"), SyncFields.ROW),
                    ).toTypedArray(),
                ),
            )
            add(
                trigger(
                    "sync_${table}_update",
                    "AFTER UPDATE ON $table",
                    null,
                    mark(entity, key("new"), SyncFields.ROW),
                ),
            )
            if (entity.tombstones) {
                add(trigger("sync_${table}_delete", "AFTER DELETE ON $table", null, tombstone(entity, key("old"))))
            }
        }
        whole("reading_goals", SyncEntity.GOAL, { listOf("CAST($it.metric AS TEXT)", "CAST($it.period AS TEXT)") })
        whole("reading_collections", SyncEntity.COLLECTION, { listOf("$it.uuid") })
        whole("reading_plans", SyncEntity.PLAN, { listOf("$it.uuid") })
        whole("release_follows", SyncEntity.FOLLOW, { listOf("CAST($it.kind AS TEXT)", "$it.follow_key") })
        whole("release_notified", SyncEntity.NOTIFIED, { listOf("$it.release_key", "CAST($it.what AS TEXT)") })

        // A collection's items change the collection.
        listOf("insert" to "new", "update" to "new", "delete" to "old").forEach { (event, row) ->
            add(
                trigger(
                    "sync_reading_collection_items_$event",
                    "AFTER ${event.uppercase()} ON reading_collection_items",
                    null,
                    mark(
                        SyncEntity.COLLECTION,
                        listOf("C.uuid"),
                        SyncFields.ROW,
                        "reading_collections C",
                        "C._id = $row.collection_id",
                    ),
                ),
            )
        }

        // Confirmed comic metadata, one record per field of an entry or of one of its chapters.
        fun metadataGroup(row: String): Pair<List<String>, Pair<String, String>> =
            (ENTRY + "coalesce(C.url, '')" + "CAST($row.field AS TEXT)") to
                (
                    "mangas M LEFT JOIN chapters C ON C._id = $row.chapter_id" to
                        "M._id = $row.manga_id AND ($row.chapter_id IS NULL OR C._id IS NOT NULL)"
                    )
        val (newGroup, newGroupFrom) = metadataGroup("new")
        val (oldGroup, oldGroupFrom) = metadataGroup("old")
        add(
            trigger(
                "sync_comic_metadata_insert",
                "AFTER INSERT ON comic_metadata",
                "new.confirmed = 1",
                mark(SyncEntity.METADATA, newGroup, SyncFields.ROW, newGroupFrom.first, newGroupFrom.second),
            ),
        )
        add(
            trigger(
                "sync_comic_metadata_update",
                "AFTER UPDATE ON comic_metadata",
                "old.confirmed = 1 OR new.confirmed = 1",
                mark(
                    SyncEntity.METADATA,
                    oldGroup,
                    SyncFields.ROW,
                    oldGroupFrom.first,
                    "${oldGroupFrom.second} AND old.confirmed = 1",
                ),
                mark(
                    SyncEntity.METADATA,
                    newGroup,
                    SyncFields.ROW,
                    newGroupFrom.first,
                    "${newGroupFrom.second} AND new.confirmed = 1",
                ),
            ),
        )
        add(
            trigger(
                "sync_comic_metadata_delete",
                "AFTER DELETE ON comic_metadata",
                "old.confirmed = 1",
                mark(SyncEntity.METADATA, oldGroup, SyncFields.ROW, oldGroupFrom.first, oldGroupFrom.second),
            ),
        )

        // Feedback on recommendations: a work by its source and URL, anything else by its key.
        fun feedback(row: String, write: (List<String>, String?, String) -> String): Array<String> = arrayOf(
            write(
                listOf("'0'") + ENTRY,
                "mangas M",
                "$row.subject_kind = 0 AND M._id = CAST($row.subject_key AS INTEGER)",
            ),
            write(listOf("CAST($row.subject_kind AS TEXT)", "$row.subject_key", "''"), null, "$row.subject_kind != 0"),
        )
        add(
            trigger(
                "sync_start_feedback_insert",
                "AFTER INSERT ON start_feedback",
                null,
                *feedback("new") { keys, from, where -> revive(SyncEntity.FEEDBACK, keys, from, where) },
                *feedback("new") { keys, from, where -> mark(SyncEntity.FEEDBACK, keys, SyncFields.ROW, from, where) },
            ),
        )
        add(
            trigger(
                "sync_start_feedback_update",
                "AFTER UPDATE ON start_feedback",
                null,
                *feedback("new") { keys, from, where -> mark(SyncEntity.FEEDBACK, keys, SyncFields.ROW, from, where) },
            ),
        )
        add(
            trigger(
                "sync_start_feedback_delete",
                "AFTER DELETE ON start_feedback",
                null,
                *feedback("old") { keys, from, where -> tombstone(SyncEntity.FEEDBACK, keys, from, where) },
            ),
        )
    }

    /** Every trigger name any version installed, so an upgrade can drop the ones it no longer has. */
    val names: List<String> get() = all.map { it.name }
}
