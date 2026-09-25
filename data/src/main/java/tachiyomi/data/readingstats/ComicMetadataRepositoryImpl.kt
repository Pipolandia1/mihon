package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import tachiyomi.data.Comic_metadata
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.LibraryChapter
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.ExportedMetadata

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ComicMetadataRepositoryImpl(
    private val database: Database,
) : ComicMetadataRepository {

    private val queries get() = database.comic_metadataQueries

    override fun subscribe(mangaId: Long): Flow<ComicMetadata> {
        return queries.metadataOf(mangaId).subscribeToList().map { rows -> metadata(mangaId, rows) }
    }

    override suspend fun get(mangaId: Long): ComicMetadata {
        return metadata(mangaId, queries.metadataOf(mangaId).awaitAsList())
    }

    override suspend fun confirm(mangaId: Long, values: List<MetadataValue>) {
        database.transaction {
            values.forEach { value ->
                queries.deleteField(mangaId, value.chapterId, value.field.id, true)
                insert(mangaId, value, confirmed = true)
            }
        }
    }

    override suspend fun remove(mangaId: Long, field: ComicField, chapterId: Long?) {
        queries.deleteField(mangaId, chapterId, field.id, true)
    }

    override suspend fun replaceSuggestions(mangaId: Long, origin: MetadataOrigin, values: List<MetadataValue>) {
        database.transaction {
            queries.deleteSuggestions(mangaId, origin.id)
            values.forEach { insert(mangaId, it.copy(origin = origin), confirmed = false) }
        }
    }

    override fun subscribeLibrary(): Flow<List<LibraryEntry>> {
        return combine(
            queries.libraryEntries().subscribeToList(),
            queries.libraryChapters().subscribeToList(),
            queries.confirmedOfLibrary().subscribeToList(),
            queries.lastReadByManga().subscribeToList(),
            queries.lastOpenedByManga().subscribeToList(),
        ) { entries, chapters, metadata, lastRead, lastOpened ->
            val chaptersByManga = chapters.groupBy { it.manga_id }
            val metadataByManga = metadata.groupBy { it.manga_id }
            val read = lastRead.associate { it.manga_id!! to it.last_read }
            val opened = lastOpened.associate { it.manga_id to it.last_read?.time }
            entries.map { manga ->
                LibraryEntry(
                    mangaId = manga._id,
                    title = manga.title,
                    cover = MangaCover(
                        mangaId = manga._id,
                        sourceId = manga.source,
                        isMangaFavorite = manga.favorite,
                        url = manga.thumbnail_url,
                        lastModified = manga.cover_last_modified,
                    ),
                    publicationFinished = manga.status.toInt() in FINISHED,
                    dateAdded = manga.date_added,
                    lastReadAt = listOfNotNull(read[manga._id], opened[manga._id]).maxOrNull()?.takeIf { it > 0 },
                    chapters = chaptersByManga[manga._id].orEmpty().map {
                        LibraryChapter(it._id, it.read, it.name, it.chapter_number, it.date_fetch)
                    },
                    metadata = metadata(manga._id, metadataByManga[manga._id].orEmpty()),
                )
            }
        }
    }

    override suspend fun completedChapterIds(from: LocalDate, to: LocalDate): Set<Long> {
        return queries.completedChapterIds(from.toString(), to.toString()).awaitAsList().filterNotNull().toSet()
    }

    override suspend fun arcs(): List<String> = queries.confirmedArcs().awaitAsList()

    override suspend fun chaptersOfArc(arc: String): List<CollectionItem> {
        return queries.chaptersOfArc(arc).awaitAsList()
            .distinctBy { it.chapter_id }
            .sortedWith(compareBy(nullsLast()) { it.cover_date })
            .map {
                CollectionItem(
                    mangaId = it.manga_id,
                    chapterId = it.chapter_id,
                    sourceId = it.source,
                    mangaUrl = it.manga_url,
                    chapterUrl = it.chapter_url,
                    label = "${it.title} · ${it.chapter_name}",
                )
            }
    }

    override suspend fun export(): List<ExportedMetadata> {
        return queries.exportConfirmed().awaitAsList()
            .groupBy { Triple(it.manga_id, it.chapter_id, it.field_) }
            .mapNotNull { (_, rows) ->
                val first = rows.first()
                val field = ComicField.of(first.field_) ?: return@mapNotNull null
                ExportedMetadata(
                    sourceId = first.source,
                    mangaUrl = first.manga_url,
                    chapterUrl = first.chapter_url,
                    value = MetadataValue(
                        field = field,
                        chapterId = first.chapter_id,
                        values = rows.sortedBy { it.position }.map { it.value_ },
                        origin = MetadataOrigin.of(first.origin),
                    ),
                )
            }
    }

    override suspend fun import(values: List<ExportedMetadata>) {
        database.transaction {
            values.forEach { exported ->
                val mangaId = database.mangasQueries.getMangaByUrlAndSource(exported.mangaUrl, exported.sourceId)
                    .awaitAsOneOrNull()?._id ?: return@forEach
                val chapterUrl = exported.chapterUrl
                val chapterId = if (chapterUrl != null) {
                    database.chaptersQueries.getChapterByUrlAndMangaId(chapterUrl, mangaId).awaitAsOneOrNull()?._id
                        ?: return@forEach
                } else {
                    null
                }
                val value = exported.value.copy(chapterId = chapterId)
                queries.deleteField(mangaId, chapterId, value.field.id, true)
                insert(mangaId, value, confirmed = true)
            }
        }
    }

    private suspend fun insert(mangaId: Long, value: MetadataValue, confirmed: Boolean) {
        value.values.filter { it.isNotBlank() }.forEachIndexed { position, text ->
            queries.insertValue(
                mangaId = mangaId,
                chapterId = value.chapterId,
                field = value.field.id,
                position = position.toLong(),
                value = text.trim(),
                origin = value.origin.id,
                confirmed = confirmed,
            )
        }
    }

    private fun metadata(mangaId: Long, rows: List<Comic_metadata>): ComicMetadata {
        fun values(rows: List<Comic_metadata>) = rows
            .groupBy { it.chapter_id to it.field_ }
            .mapNotNull { (key, group) ->
                val field = ComicField.of(key.second) ?: return@mapNotNull null
                // A field's values may come from different origins only across a re-fetch; the first wins.
                MetadataValue(
                    field = field,
                    chapterId = key.first,
                    values = group.sortedBy { it.position }.map { it.value_ },
                    origin = MetadataOrigin.of(group.first().origin),
                )
            }
        val (confirmed, suggested) = rows.partition { it.confirmed }
        return ComicMetadata(mangaId, values(confirmed), values(suggested))
    }

    private companion object {
        /** Publication statuses after which the chapters a source lists are all there will be. */
        val FINISHED = setOf(SManga.COMPLETED, SManga.PUBLISHING_FINISHED, SManga.CANCELLED)
    }
}
