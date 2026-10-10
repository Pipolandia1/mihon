package tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.datetime.LocalDate
import tachiyomi.data.Database
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncField
import tachiyomi.domain.sync.SyncFields
import tachiyomi.domain.sync.SyncKey
import tachiyomi.domain.sync.SyncProgress
import tachiyomi.domain.sync.SyncRecord
import tachiyomi.domain.sync.SyncSnapshot
import tachiyomi.data.sync.SyncValues as V

/**
 * This device's [SyncSnapshot]: every synced row with the times sync_state has for its fields (0
 * for what was here before sync was turned on), plus the tombstones of what was deleted.
 */
internal class SyncSnapshotReader(private val database: Database) {

    private val queries get() = database.sync_stateQueries

    suspend fun read(): SyncSnapshot {
        val times = readTimes()
        val records = HashMap<SyncKey, SyncRecord>()

        fun put(key: SyncKey, vararg fields: Pair<String, String>) {
            val recorded = times[key].orEmpty()
            records[key] = SyncRecord(
                fields = fields.associate { (name, value) -> name to SyncField(value, recorded[name] ?: 0) },
                deletedAt = recorded[SyncFields.DELETED],
            )
        }

        queries.syncMangas().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.MANGA, it.source.toString(), it.url),
                SyncFields.FAVORITE to V.bool(it.favorite),
                SyncFields.DATE_ADDED to it.date_added.toString(),
                SyncFields.SETTINGS to
                    V.encode(V.MangaSettings.serializer(), V.MangaSettings(it.viewer, it.chapter_flags)),
                SyncFields.NOTES to it.notes,
                SyncFields.INFO to V.encode(
                    V.MangaInfo.serializer(),
                    V.MangaInfo(
                        title = it.title,
                        author = it.author,
                        artist = it.artist,
                        description = it.description,
                        genre = it.genre,
                        status = it.status,
                        thumbnailUrl = it.thumbnail_url,
                        updateStrategy = UpdateStrategyColumnAdapter.encode(it.update_strategy),
                        initialized = it.initialized,
                    ),
                ),
            )
        }
        queries.syncChapters().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.CHAPTER, it.source.toString(), it.manga_url, it.url),
                SyncFields.READ to V.bool(it.read),
                SyncFields.BOOKMARK to V.bool(it.bookmark),
                SyncFields.PAGE to it.last_page_read.toString(),
                SyncFields.INFO to V.encode(
                    V.ChapterInfo.serializer(),
                    V.ChapterInfo(
                        name = it.name,
                        scanlator = it.scanlator,
                        number = it.chapter_number,
                        sourceOrder = it.source_order,
                        dateFetch = it.date_fetch,
                        dateUpload = it.date_upload,
                    ),
                ),
            )
        }
        queries.syncHistory().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.HISTORY, it.source.toString(), it.manga_url, it.chapter_url),
                SyncFields.LAST_READ to (it.last_read?.time ?: 0L).toString(),
                SyncFields.TIME_READ to it.time_read.toString(),
            )
        }
        queries.syncCategories().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.CATEGORY, it.name),
                SyncFields.ROW to V.encode(V.Category.serializer(), V.Category(it.sort, it.flags)),
            )
        }
        queries.syncMangaCategories().awaitAsList().forEach {
            put(SyncKey(SyncEntity.MANGA_CATEGORY, it.source.toString(), it.url, it.name), SyncFields.MEMBER to "1")
        }
        queries.syncTracks().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.TRACK, it.source.toString(), it.manga_url, it.sync_id.toString()),
                SyncFields.ROW to V.encode(
                    V.Track.serializer(),
                    V.Track(
                        remoteId = it.remote_id,
                        libraryId = it.library_id,
                        title = it.title,
                        lastChapterRead = it.last_chapter_read,
                        totalChapters = it.total_chapters,
                        status = it.status,
                        score = it.score,
                        remoteUrl = it.remote_url,
                        startDate = it.start_date,
                        finishDate = it.finish_date,
                        private = it.private_,
                    ),
                ),
            )
        }
        queries.syncExtensionStores().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.EXTENSION_STORE, it.index_url),
                SyncFields.ROW to V.encode(
                    V.ExtensionStore.serializer(),
                    V.ExtensionStore(
                        name = it.name,
                        badgeLabel = it.badge_label,
                        signingKey = it.signing_key,
                        contactWebsite = it.contact_website,
                        contactDiscord = it.contact_discord,
                        isLegacy = it.is_legacy,
                        extensionListUrl = it.extension_list_url,
                    ),
                ),
            )
        }
        val days = queries.syncSessionDays().awaitAsList().groupBy({ it.session_uuid }) {
            V.SessionDay(it.local_date, it.active_ms, it.pages_new, it.pages_visited)
        }
        queries.syncSessions().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.SESSION, it.session_uuid),
                SyncFields.ROW to V.encode(
                    V.Session.serializer(),
                    V.Session(
                        sourceId = it.source_id,
                        mangaUrl = it.manga_url,
                        chapterUrl = it.chapter_url,
                        mangaTitle = it.manga_title,
                        chapterName = it.chapter_name,
                        chapterNumber = it.chapter_number,
                        startedAt = it.started_at,
                        endedAt = it.ended_at,
                        activeMs = it.active_ms,
                        firstPage = it.first_page,
                        lastPage = it.last_page,
                        pagesVisited = it.pages_visited,
                        pagesNew = it.pages_new,
                        pagesReread = it.pages_reread,
                        pageAdvances = it.page_advances,
                        totalPages = it.total_pages,
                        pagesBits = V.bytes(it.pages_bits),
                        completedAt = it.completed_at,
                        completedOn = it.completed_on,
                        origin = it.origin,
                        state = it.state,
                        zoneId = it.zone_id,
                        days = days[it.session_uuid].orEmpty(),
                    ),
                ),
            )
        }
        queries.syncProgress().awaitAsList().forEach {
            val progress = ChapterProgress(
                chapterId = 0,
                pagesSeen = it.pages_seen,
                maxPageReached = it.max_page_reached.toInt(),
                totalPages = it.total_pages?.toInt(),
                completedAt = it.completed_at,
                completedOn = it.completed_on?.let(LocalDate::parse),
                completionOrigin = ReadingOrigin.of(it.completion_origin),
            )
            put(
                SyncKey(SyncEntity.CHAPTER_PROGRESS, it.source.toString(), it.manga_url, it.chapter_url),
                SyncFields.ROW to SyncProgress.encode(progress),
            )
        }
        queries.syncStatsMeta().awaitAsList().forEach {
            put(SyncKey(SyncEntity.STATS_META, it.key), SyncFields.VALUE to it.value_.toString())
        }
        queries.syncGoals().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.GOAL, it.metric.toString(), it.period.toString()),
                SyncFields.ROW to V.encode(V.Goal.serializer(), V.Goal(it.target, it.is_primary, it.updated_at)),
            )
        }
        val items = queries.syncCollectionItems().awaitAsList().groupBy({ it.uuid }) {
            V.CollectionItem(it.position, it.source_id, it.manga_url, it.chapter_url, it.label)
        }
        queries.syncCollections().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.COLLECTION, it.uuid),
                SyncFields.ROW to V.encode(
                    V.Collection.serializer(),
                    V.Collection(it.kind, it.name, it.created_at, it.standalone, items[it.uuid].orEmpty()),
                ),
            )
        }
        queries.syncPlans().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.PLAN, it.uuid),
                SyncFields.ROW to V.encode(
                    V.Plan.serializer(),
                    V.Plan(it.collection_uuid, it.pace_per_week, it.started_at, it.started_on, it.paused),
                ),
            )
        }
        queries.syncMetadata().awaitAsList()
            .groupBy {
                SyncKey(SyncEntity.METADATA, it.source.toString(), it.manga_url, it.chapter_url, it.field_.toString())
            }
            .forEach { (key, values) ->
                put(
                    key,
                    SyncFields.ROW to
                        V.encode(V.metadataList, values.map { V.MetadataValue(it.position, it.value_, it.origin) }),
                )
            }
        queries.syncFollows().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.FOLLOW, it.kind.toString(), it.follow_key),
                SyncFields.ROW to V.encode(
                    V.Follow.serializer(),
                    V.Follow(it.label, it.series_name, it.series_year, it.number, it.created_at),
                ),
            )
        }
        queries.syncNotified().awaitAsList().forEach {
            put(
                SyncKey(SyncEntity.NOTIFIED, it.release_key, it.what.toString()),
                SyncFields.ROW to V.encode(
                    V.Notified.serializer(),
                    V.Notified(it.series_key, it.series_name, it.series_year, it.number, it.store_date, it.notified_at),
                ),
            )
        }
        queries.syncFeedback().awaitAsList().forEach {
            val key = if (it.subject_kind == 0L) {
                val source = it.source ?: return@forEach
                SyncKey(SyncEntity.FEEDBACK, "0", source.toString(), it.url!!)
            } else {
                SyncKey(SyncEntity.FEEDBACK, it.subject_kind.toString(), it.subject_key, "")
            }
            put(
                key,
                SyncFields.ROW to V.encode(V.Feedback.serializer(), V.Feedback(it.verdict, it.until_at, it.created_at)),
            )
        }

        // What is no longer here but was recorded: deletions, and confirmed metadata that was cleared.
        times.forEach { (key, recorded) ->
            if (key in records) return@forEach
            val deletedAt = recorded[SyncFields.DELETED]
            when {
                key.entity.tombstones && deletedAt != null -> records[key] = SyncRecord(emptyMap(), deletedAt)
                key.entity == SyncEntity.METADATA -> recorded[SyncFields.ROW]?.let { at ->
                    records[key] =
                        SyncRecord(mapOf(SyncFields.ROW to SyncField(V.encode(V.metadataList, emptyList()), at)))
                }
            }
        }
        return SyncSnapshot(records)
    }

    private suspend fun readTimes(): Map<SyncKey, Map<String, Long>> {
        val times = HashMap<SyncKey, HashMap<String, Long>>()
        queries.allStates().awaitAsList().forEach {
            val entity = SyncEntity.of(it.entity) ?: return@forEach
            val key = SyncKey(entity, listOf(it.k1, it.k2, it.k3, it.k4).take(entity.keySize))
            times.getOrPut(key) { HashMap() }[it.field_] = it.modified_at
        }
        return times
    }
}
