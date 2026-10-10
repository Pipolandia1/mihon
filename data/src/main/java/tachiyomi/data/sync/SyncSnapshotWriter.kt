package tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import tachiyomi.data.Database
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncFields
import tachiyomi.domain.sync.SyncKey
import tachiyomi.domain.sync.SyncProgress
import tachiyomi.domain.sync.SyncRecord
import tachiyomi.domain.sync.SyncSnapshot
import java.util.Date
import tachiyomi.data.sync.SyncValues as V

/**
 * Writes a merge into the database: every record whose merged version differs from this device's.
 *
 * What cannot be written yet waits in the Drive file and is tried again on the next sync: a chapter
 * of an entry this device has not refreshed, the history of such a chapter, a session the reader is
 * still writing here. Chapters are only created along with an entry created from the sync, never
 * added to an entry that exists: that would bring back chapters the source removed. An entry's
 * details ([SyncFields.INFO]) likewise only serve to create it.
 *
 * Runs inside the merge's transaction with sync_control.applying set, so the triggers stay quiet
 * and the merged times are written by [recordTimes] instead.
 */
internal class SyncSnapshotWriter(private val database: Database) {

    private val queries get() = database.sync_stateQueries
    private val stats get() = database.reading_statsQueries

    private val mangaIds = HashMap<Pair<Long, String>, Long?>()
    private val createdMangas = HashSet<Long>()

    /** Each entry's chapters by URL, loaded once per entry rather than once per chapter. */
    private val chapterIds = HashMap<Long, HashMap<String, Long>>()

    /** Chapter changes by field and value, written in batches once every chapter is through. */
    private val chapterChanges = HashMap<Pair<String, String>, MutableList<Long>>()

    /** Chapters whose sessions or progress changed: which session completed them first is worked out again. */
    private val touchedChapters = HashSet<Long>()

    class Written(
        /** Records that changed something here. */
        val keys: Set<SyncKey>,
        /** Records that have to wait for a later sync. */
        val pending: Int,
    )

    suspend fun write(local: SyncSnapshot, merged: SyncSnapshot): Written {
        val changed = merged.records.entries
            .filter { (key, record) -> local.records[key] != record }
            .groupBy { it.key.entity }
        val keys = HashSet<SyncKey>()
        var pending = 0
        ORDER.forEach { entity ->
            changed[entity].orEmpty().forEach { (key, record) ->
                val written = write(key, local.records[key], record)
                when {
                    written == null -> pending++
                    written.isNotEmpty() || record.deletedAt != local.records[key]?.deletedAt -> {
                        keys += key
                        recordTimes(key, record, written)
                    }
                    else -> recordTimes(key, record, written)
                }
            }
            if (entity == SyncEntity.CHAPTER) writeChapterChanges()
        }
        touchedChapters.forEach { reworkFirstCompletion(it) }
        return Written(keys, pending)
    }

    private suspend fun writeChapterChanges() {
        chapterChanges.forEach { (change, ids) ->
            val (field, value) = change
            ids.chunked(BATCH).forEach { batch ->
                when (field) {
                    SyncFields.READ -> queries.setChaptersRead(value == "1", batch)
                    SyncFields.BOOKMARK -> queries.setChaptersBookmark(value == "1", batch)
                    SyncFields.PAGE -> queries.setChaptersPage(value.toLong(), batch)
                }
            }
        }
        chapterChanges.clear()
    }

    /** Writes [record]; returns the fields it wrote, or null when it has to wait. */
    private suspend fun write(key: SyncKey, local: SyncRecord?, record: SyncRecord): Set<String>? {
        val parts = key.parts
        return when (key.entity) {
            SyncEntity.MANGA -> manga(parts[0].toLong(), parts[1], local, record)
            SyncEntity.CHAPTER -> chapter(parts, local, record)
            SyncEntity.HISTORY -> history(parts, record)
            SyncEntity.CATEGORY -> category(parts[0], record)
            SyncEntity.MANGA_CATEGORY -> mangaCategory(parts, record)
            SyncEntity.TRACK -> track(parts, record)
            SyncEntity.EXTENSION_STORE -> extensionStore(parts[0], record)
            SyncEntity.SESSION -> session(parts[0], record)
            SyncEntity.CHAPTER_PROGRESS -> progress(parts, record)
            SyncEntity.STATS_META -> {
                queries.putStatsMeta(parts[0], record[SyncFields.VALUE]!!.toLong())
                setOf(SyncFields.VALUE)
            }
            SyncEntity.GOAL -> goal(parts, record)
            SyncEntity.COLLECTION -> collection(parts[0], record)
            SyncEntity.PLAN -> plan(parts[0], record)
            SyncEntity.METADATA -> metadata(parts, record)
            SyncEntity.FOLLOW -> follow(parts, record)
            SyncEntity.NOTIFIED -> notified(parts, record)
            SyncEntity.FEEDBACK -> feedback(parts, record)
        }
    }

    private suspend fun manga(source: Long, url: String, local: SyncRecord?, record: SyncRecord): Set<String>? {
        val id = mangaId(source, url)
        if (id == null) {
            val info = record[SyncFields.INFO]?.let { V.decode(V.MangaInfo.serializer(), it) } ?: return null
            val settings = record[SyncFields.SETTINGS]?.let { V.decode(V.MangaSettings.serializer(), it) }
            val created = queries.insertManga(
                source = source,
                url = url,
                artist = info.artist,
                author = info.author,
                description = info.description,
                genre = info.genre,
                title = info.title,
                status = info.status,
                thumbnailUrl = info.thumbnailUrl,
                favorite = record[SyncFields.FAVORITE] == "1",
                initialized = info.initialized,
                viewer = settings?.viewer ?: 0,
                chapterFlags = settings?.chapterFlags ?: 0,
                dateAdded = record[SyncFields.DATE_ADDED]?.toLong() ?: 0,
                updateStrategy = UpdateStrategyColumnAdapter.decode(info.updateStrategy),
                notes = record[SyncFields.NOTES] ?: "",
            ).awaitAsOne()
            mangaIds[source to url] = created
            createdMangas += created
            return record.fields.keys
        }
        val fields =
            changedFields(
                local,
                record,
                SyncFields.FAVORITE,
                SyncFields.DATE_ADDED,
                SyncFields.SETTINGS,
                SyncFields.NOTES,
            )
        fields.forEach { field ->
            val value = record[field]!!
            when (field) {
                SyncFields.FAVORITE -> queries.setMangaFavorite(value == "1", id)
                SyncFields.DATE_ADDED -> queries.setMangaDateAdded(value.toLong(), id)
                SyncFields.SETTINGS -> {
                    val settings = V.decode(V.MangaSettings.serializer(), value)
                    queries.setMangaSettings(settings.viewer, settings.chapterFlags, id)
                }
                SyncFields.NOTES -> queries.setMangaNotes(value, id)
            }
        }
        return fields
    }

    private suspend fun chapter(parts: List<String>, local: SyncRecord?, record: SyncRecord): Set<String>? {
        val mangaId = mangaId(parts[0].toLong(), parts[1]) ?: return null
        val chapterId = chapterId(mangaId, parts[2])
        if (chapterId == null) {
            if (mangaId !in createdMangas) return null
            val info = record[SyncFields.INFO]?.let { V.decode(V.ChapterInfo.serializer(), it) } ?: return null
            val created = queries.insertChapter(
                mangaId = mangaId,
                url = parts[2],
                name = info.name,
                scanlator = info.scanlator,
                read = record[SyncFields.READ] == "1",
                bookmark = record[SyncFields.BOOKMARK] == "1",
                lastPageRead = record[SyncFields.PAGE]?.toLong() ?: 0,
                chapterNumber = info.number,
                sourceOrder = info.sourceOrder,
                dateFetch = info.dateFetch,
                dateUpload = info.dateUpload,
            ).awaitAsOne()
            chapterIds.getValue(mangaId)[parts[2]] = created
            return record.fields.keys
        }
        val fields = changedFields(local, record, SyncFields.READ, SyncFields.BOOKMARK, SyncFields.PAGE)
        fields.forEach { field -> chapterChanges.getOrPut(field to record[field]!!) { ArrayList() } += chapterId }
        return fields
    }

    private suspend fun history(parts: List<String>, record: SyncRecord): Set<String>? {
        val chapterId = chapterId(parts) ?: return if (record.isAlive) null else emptySet()
        if (!record.isAlive) {
            queries.deleteHistory(chapterId)
            return emptySet()
        }
        queries.putHistory(
            chapterId = chapterId,
            lastRead = Date(record[SyncFields.LAST_READ]?.toLong() ?: 0),
            timeRead = record[SyncFields.TIME_READ]?.toLong() ?: 0,
        )
        return record.fields.keys
    }

    private suspend fun category(name: String, record: SyncRecord): Set<String> {
        val id = queries.categoryIdOf(name).awaitAsOneOrNull()
        if (!record.isAlive) {
            if (id != null) queries.deleteCategory(id)
            return emptySet()
        }
        val category = V.decode(V.Category.serializer(), record[SyncFields.ROW]!!)
        if (id == null) {
            queries.insertCategory(name, category.order, category.flags)
        } else {
            queries.setCategory(category.order, category.flags, id)
        }
        return setOf(SyncFields.ROW)
    }

    private suspend fun mangaCategory(parts: List<String>, record: SyncRecord): Set<String>? {
        val mangaId = mangaId(parts[0].toLong(), parts[1])
        val categoryId = queries.categoryIdOf(parts[2]).awaitAsOneOrNull()
        if (!record.isAlive) {
            if (mangaId != null && categoryId != null) queries.deleteMangaCategory(mangaId, categoryId)
            return emptySet()
        }
        if (mangaId == null || categoryId == null) return null
        if (queries.hasMangaCategory(mangaId, categoryId).awaitAsOne() == 0L) {
            queries.insertMangaCategory(mangaId, categoryId)
        }
        return setOf(SyncFields.MEMBER)
    }

    private suspend fun track(parts: List<String>, record: SyncRecord): Set<String>? {
        val mangaId = mangaId(parts[0].toLong(), parts[1])
        val syncId = parts[2].toLong()
        if (!record.isAlive) {
            if (mangaId != null) queries.deleteTrack(mangaId, syncId)
            return emptySet()
        }
        if (mangaId == null) return null
        val track = V.decode(V.Track.serializer(), record[SyncFields.ROW]!!)
        queries.putTrack(
            mangaId = mangaId,
            syncId = syncId,
            remoteId = track.remoteId,
            libraryId = track.libraryId,
            title = track.title,
            lastChapterRead = track.lastChapterRead,
            totalChapters = track.totalChapters,
            status = track.status,
            score = track.score,
            remoteUrl = track.remoteUrl,
            startDate = track.startDate,
            finishDate = track.finishDate,
            private = track.private,
        )
        return setOf(SyncFields.ROW)
    }

    private suspend fun extensionStore(indexUrl: String, record: SyncRecord): Set<String> {
        if (!record.isAlive) {
            queries.deleteExtensionStore(indexUrl)
            return emptySet()
        }
        val store = V.decode(V.ExtensionStore.serializer(), record[SyncFields.ROW]!!)
        queries.putExtensionStore(
            indexUrl = indexUrl,
            name = store.name,
            badgeLabel = store.badgeLabel,
            signingKey = store.signingKey,
            contactWebsite = store.contactWebsite,
            contactDiscord = store.contactDiscord,
            isLegacy = store.isLegacy,
            extensionListUrl = store.extensionListUrl,
        )
        return setOf(SyncFields.ROW)
    }

    private suspend fun session(uuid: String, record: SyncRecord): Set<String>? {
        val existing = queries.sessionOf(uuid).awaitAsOneOrNull()
        // The reader is writing it here: its own version comes once it is closed, and is newer.
        if (existing?.state == SessionState.OPEN.id) return null
        existing?.chapter_id?.let(touchedChapters::add)
        if (!record.isAlive) {
            if (existing != null) queries.deleteSession(uuid)
            return emptySet()
        }
        val session = V.decode(V.Session.serializer(), record[SyncFields.ROW]!!)
        val mangaId = mangaId(session.sourceId, session.mangaUrl)
        val chapterId = mangaId?.let { chapterId(it, session.chapterUrl) }
        queries.putSession(
            uuid = uuid,
            mangaId = mangaId,
            chapterId = chapterId,
            sourceId = session.sourceId,
            mangaUrl = session.mangaUrl,
            chapterUrl = session.chapterUrl,
            mangaTitle = session.mangaTitle,
            chapterName = session.chapterName,
            chapterNumber = session.chapterNumber,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            activeMs = session.activeMs,
            firstPage = session.firstPage,
            lastPage = session.lastPage,
            pagesVisited = session.pagesVisited,
            pagesNew = session.pagesNew,
            pagesReread = session.pagesReread,
            pageAdvances = session.pageAdvances,
            totalPages = session.totalPages,
            pagesBits = V.bytes(session.pagesBits),
            completedAt = session.completedAt,
            completedOn = session.completedOn,
            origin = session.origin,
            state = session.state,
            zoneId = session.zoneId,
        )
        val sessionId = queries.sessionOf(uuid).awaitAsOne()._id
        queries.deleteSessionDays(sessionId)
        session.days.forEach {
            queries.insertSessionDay(sessionId, it.date, it.activeMs, it.pagesNew, it.pagesVisited)
        }
        chapterId?.let(touchedChapters::add)
        return setOf(SyncFields.ROW)
    }

    private suspend fun progress(parts: List<String>, record: SyncRecord): Set<String>? {
        val chapterId = chapterId(parts) ?: return null
        val progress = SyncProgress.decode(record[SyncFields.ROW]!!, chapterId)
        stats.replaceProgress(
            chapterId = chapterId,
            pagesSeen = progress.pagesSeen,
            maxPageReached = progress.maxPageReached.toLong(),
            totalPages = progress.totalPages?.toLong(),
            completedAt = progress.completedAt,
            completedOn = progress.completedOn?.toString(),
            completionOrigin = progress.completionOrigin?.id,
        )
        touchedChapters += chapterId
        return setOf(SyncFields.ROW)
    }

    private suspend fun goal(parts: List<String>, record: SyncRecord): Set<String> {
        val metric = parts[0].toLong()
        val period = parts[1].toLong()
        if (!record.isAlive) {
            queries.deleteGoal(metric, period)
            return emptySet()
        }
        val goal = V.decode(V.Goal.serializer(), record[SyncFields.ROW]!!)
        queries.putGoal(metric, period, goal.target, goal.isPrimary, goal.updatedAt)
        return setOf(SyncFields.ROW)
    }

    private suspend fun collection(uuid: String, record: SyncRecord): Set<String> {
        if (!record.isAlive) {
            queries.deleteCollection(uuid)
            return emptySet()
        }
        val collection = V.decode(V.Collection.serializer(), record[SyncFields.ROW]!!)
        val existing = queries.collectionIdOf(uuid).awaitAsOneOrNull()
        val collectionId = if (existing == null) {
            queries.insertCollection(
                uuid,
                collection.kind,
                collection.name,
                collection.createdAt,
                collection.standalone,
            )
            queries.collectionIdOf(uuid).awaitAsOne()
        } else {
            queries.setCollection(
                collection.kind,
                collection.name,
                collection.createdAt,
                collection.standalone,
                existing,
            )
            existing
        }
        queries.deleteCollectionItems(collectionId)
        collection.items.forEach { item ->
            val mangaId = mangaId(item.sourceId, item.mangaUrl)
            val chapterId = item.chapterUrl?.let { url -> mangaId?.let { chapterId(it, url) } }
            queries.insertCollectionItem(
                collectionId = collectionId,
                position = item.position,
                mangaId = mangaId,
                chapterId = chapterId,
                sourceId = item.sourceId,
                mangaUrl = item.mangaUrl,
                chapterUrl = item.chapterUrl,
                label = item.label,
            )
        }
        return setOf(SyncFields.ROW)
    }

    private suspend fun plan(uuid: String, record: SyncRecord): Set<String>? {
        if (!record.isAlive) {
            queries.deletePlan(uuid)
            return emptySet()
        }
        val plan = V.decode(V.Plan.serializer(), record[SyncFields.ROW]!!)
        val collectionId = queries.collectionIdOf(plan.collectionUuid).awaitAsOneOrNull() ?: return null
        queries.putPlan(uuid, collectionId, plan.pacePerWeek, plan.startedAt, plan.startedOn, plan.paused)
        return setOf(SyncFields.ROW)
    }

    private suspend fun metadata(parts: List<String>, record: SyncRecord): Set<String>? {
        val mangaId = mangaId(parts[0].toLong(), parts[1]) ?: return null
        val chapterId = if (parts[2].isEmpty()) null else chapterId(mangaId, parts[2]) ?: return null
        val field = parts[3].toLong()
        queries.deleteConfirmedField(mangaId, chapterId, field)
        V.decode(V.metadataList, record[SyncFields.ROW]!!).forEach {
            queries.insertConfirmedValue(mangaId, chapterId, field, it.position, it.value, it.origin)
        }
        return setOf(SyncFields.ROW)
    }

    private suspend fun follow(parts: List<String>, record: SyncRecord): Set<String> {
        val kind = parts[0].toLong()
        if (!record.isAlive) {
            queries.deleteFollow(kind, parts[1])
            return emptySet()
        }
        val follow = V.decode(V.Follow.serializer(), record[SyncFields.ROW]!!)
        queries.putFollow(
            kind,
            parts[1],
            follow.label,
            follow.seriesName,
            follow.seriesYear,
            follow.number,
            follow.createdAt,
        )
        return setOf(SyncFields.ROW)
    }

    private suspend fun notified(parts: List<String>, record: SyncRecord): Set<String> {
        val notified = V.decode(V.Notified.serializer(), record[SyncFields.ROW]!!)
        queries.putNotified(
            releaseKey = parts[0],
            what = parts[1].toLong(),
            seriesKey = notified.seriesKey,
            seriesName = notified.seriesName,
            seriesYear = notified.seriesYear,
            number = notified.number,
            storeDate = notified.storeDate,
            notifiedAt = notified.notifiedAt,
        )
        return setOf(SyncFields.ROW)
    }

    private suspend fun feedback(parts: List<String>, record: SyncRecord): Set<String>? {
        val kind = parts[0].toLong()
        val subjectKey = if (kind == 0L) {
            mangaId(parts[1].toLong(), parts[2])?.toString() ?: return if (record.isAlive) null else emptySet()
        } else {
            parts[1]
        }
        if (!record.isAlive) {
            queries.deleteFeedback(kind, subjectKey)
            return emptySet()
        }
        val feedback = V.decode(V.Feedback.serializer(), record[SyncFields.ROW]!!)
        queries.putFeedback(kind, subjectKey, feedback.verdict, feedback.untilAt, feedback.createdAt)
        return setOf(SyncFields.ROW)
    }

    /**
     * A chapter completed on both devices is completed once: by its earliest reader session, when
     * its progress says the reader completed it first, as a backup restore decides.
     */
    private suspend fun reworkFirstCompletion(chapterId: Long) {
        queries.clearFirstCompletion(chapterId)
        val progress = stats.getProgress(chapterId).awaitAsOneOrNull() ?: return
        if (progress.completion_origin != ReadingOrigin.READER.id) return
        val first = stats.getFirstCompletingSession(chapterId).awaitAsOneOrNull() ?: return
        stats.setFirstCompletion(first._id)
    }

    /** Leaves in sync_state the merged times of what was written, so this device's snapshot now matches the merge. */
    private suspend fun recordTimes(key: SyncKey, record: SyncRecord, written: Set<String>) {
        val (k1, k2, k3, k4) = key.parts + List(4 - key.parts.size) { "" }
        written.forEach { field ->
            val time = record.fields[field]?.modifiedAt ?: return@forEach
            if (time > 0) queries.putState(key.entity.id, k1, k2, k3, k4, field, time)
        }
        record.deletedAt?.let { queries.putState(key.entity.id, k1, k2, k3, k4, SyncFields.DELETED, it) }
    }

    private fun changedFields(local: SyncRecord?, record: SyncRecord, vararg fields: String): Set<String> =
        fields.filterTo(HashSet()) { field ->
            record.fields[field] != null &&
                local?.fields?.get(field) != record.fields[field]
        }

    private suspend fun mangaId(source: Long, url: String): Long? =
        mangaIds.getOrPut(source to url) { queries.mangaIdOf(source, url).awaitAsOneOrNull() }

    private suspend fun chapterId(mangaId: Long, url: String): Long? =
        chapterIds.getOrPut(mangaId) {
            queries.chapterIdsOf(mangaId).awaitAsList().associateTo(HashMap()) { it.url to it._id }
        }[url]

    private suspend fun chapterId(parts: List<String>): Long? {
        val mangaId = mangaId(parts[0].toLong(), parts[1]) ?: return null
        return chapterId(mangaId, parts[2])
    }

    private companion object {
        /** Chapters per statement: well under SQLite's limit of bound values. */
        const val BATCH = 500

        /** Categories and entries before what hangs from them, collections before plans. */
        val ORDER = listOf(
            SyncEntity.CATEGORY,
            SyncEntity.MANGA,
            SyncEntity.MANGA_CATEGORY,
            SyncEntity.CHAPTER,
            SyncEntity.HISTORY,
            SyncEntity.TRACK,
            SyncEntity.EXTENSION_STORE,
            SyncEntity.SESSION,
            SyncEntity.CHAPTER_PROGRESS,
            SyncEntity.STATS_META,
            SyncEntity.GOAL,
            SyncEntity.COLLECTION,
            SyncEntity.PLAN,
            SyncEntity.METADATA,
            SyncEntity.FOLLOW,
            SyncEntity.NOTIFIED,
            SyncEntity.FEEDBACK,
        )
    }
}
