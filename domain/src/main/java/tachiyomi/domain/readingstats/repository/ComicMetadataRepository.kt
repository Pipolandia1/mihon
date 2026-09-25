package tachiyomi.domain.readingstats.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.plans.CollectionItem

interface ComicMetadataRepository {

    /** An entry's confirmed metadata and stored suggestions. */
    fun subscribe(mangaId: Long): Flow<ComicMetadata>

    suspend fun get(mangaId: Long): ComicMetadata

    /** Confirms values, each replacing what was confirmed for its field and scope. Empty values remove it. */
    suspend fun confirm(mangaId: Long, values: List<MetadataValue>)

    suspend fun remove(mangaId: Long, field: ComicField, chapterId: Long?)

    /** Replaces the stored suggestions that came from [origin] (Metron, ComicInfo). */
    suspend fun replaceSuggestions(mangaId: Long, origin: MetadataOrigin, values: List<MetadataValue>)

    /** Every library entry with its chapters, confirmed metadata and when it was last read. */
    fun subscribeLibrary(): Flow<List<LibraryEntry>>

    /** Chapters first completed in the reader, or marked read, between [from] and [to]. */
    suspend fun completedChapterIds(from: LocalDate, to: LocalDate): Set<Long>

    /** Every arc named in confirmed chapter metadata. */
    suspend fun arcs(): List<String>

    /** The chapters that confirmed metadata puts in [arc], ordered by cover date where known. */
    suspend fun chaptersOfArc(arc: String): List<CollectionItem>

    // region Backup

    /** Confirmed metadata, each value with the entry's source and URL and the chapter's URL. */
    suspend fun export(): List<ExportedMetadata>

    /** Confirms the values of entries and chapters found by source and URL; others are skipped. */
    suspend fun import(values: List<ExportedMetadata>)

    // endregion
}

data class ExportedMetadata(
    val sourceId: Long,
    val mangaUrl: String,
    /** Null for series-level values. */
    val chapterUrl: String?,
    val value: MetadataValue,
)
