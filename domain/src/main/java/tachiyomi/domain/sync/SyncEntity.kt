package tachiyomi.domain.sync

/**
 * What syncs, how each kind of record is keyed, and whether deleting one here deletes it everywhere
 * (docs/DESKTOP_PLAN.md §3.4). The ids are written to the database and to the Drive file: never
 * rename one.
 */
enum class SyncEntity(
    val id: String,
    val keySize: Int,
    /** Whether deleting a row here deletes it on the other devices. */
    val tombstones: Boolean,
) {
    /** Source, URL. Fields: favorite, date added, reader settings, notes, and its details. */
    MANGA("manga", 2, tombstones = false),

    /** Source, entry URL, chapter URL. Fields: read, bookmark, page, and its details. */
    CHAPTER("chapter", 3, tombstones = false),

    /** Keyed as its chapter. Fields: last read, time read. */
    HISTORY("history", 3, tombstones = true),

    /** Name. A rename is the old name deleted and the new one created, with its entries. */
    CATEGORY("category", 1, tombstones = true),

    /** Source, entry URL, category name. */
    MANGA_CATEGORY("manga_category", 3, tombstones = true),

    /** Source, entry URL, tracker id. The login stays on each device. */
    TRACK("track", 3, tombstones = true),

    /** Index URL. */
    EXTENSION_STORE("extension_store", 1, tombstones = true),

    /** A reading session of the Stats tab, by its uuid, with its days. */
    SESSION("session", 1, tombstones = true),

    /** Keyed as its chapter: the pages seen, the furthest page, the first completion. */
    CHAPTER_PROGRESS("chapter_progress", 3, tombstones = false),

    /** A key of reading_stats_meta: when recording started. */
    STATS_META("stats_meta", 1, tombstones = false),

    /** Metric, period. */
    GOAL("goal", 2, tombstones = true),

    /** A reading collection by its uuid, with its items. */
    COLLECTION("collection", 1, tombstones = true),

    /** A reading plan by its uuid. */
    PLAN("plan", 1, tombstones = true),

    /**
     * The confirmed values of one field of an entry or of one of its chapters: source, entry URL,
     * chapter URL (empty for the entry), field. Clearing a field syncs as an empty list.
     */
    METADATA("metadata", 4, tombstones = false),

    /** A followed series, issue or arc of the release alerts: kind, key. */
    FOLLOW("follow", 2, tombstones = true),

    /** An alert already shown, so the other device does not show it again: release key, what. */
    NOTIFIED("notified", 2, tombstones = false),

    /**
     * What the reader said of a recommendation of the Início: kind, then source and URL for a work,
     * or the name and an empty part for a creator, character or arc.
     */
    FEEDBACK("feedback", 3, tombstones = true),
    ;

    fun strategy(field: String): SyncStrategy = when {
        this == HISTORY && field == SyncFields.TIME_READ -> SyncStrategy.MAX
        this == STATS_META -> SyncStrategy.MIN
        this == CHAPTER_PROGRESS -> SyncStrategy.PROGRESS
        else -> SyncStrategy.LATEST
    }

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): SyncEntity? = byId[id]
    }
}

/** Field names, written to the database and to the Drive file like the entity ids. */
object SyncFields {
    /** The whole row, for records that change as one. */
    const val ROW = "row"
    const val FAVORITE = "favorite"
    const val DATE_ADDED = "date_added"
    const val SETTINGS = "settings"
    const val NOTES = "notes"

    /** What it takes to create the row on a device that does not have it. Never applied to a row that exists. */
    const val INFO = "info"
    const val READ = "read"
    const val BOOKMARK = "bookmark"
    const val PAGE = "page"
    const val LAST_READ = "last_read"
    const val TIME_READ = "time_read"
    const val MEMBER = "member"
    const val VALUE = "value"

    /** Not a field: how sync_state stores a tombstone. */
    const val DELETED = "deleted"
}
