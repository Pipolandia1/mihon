package tachiyomi.data.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * What the records of [tachiyomi.domain.sync.SyncSnapshot] hold, field by field. Read from the
 * database and written back by the same classes, so a row that did not change encodes to the same
 * string on every device: the merge compares strings.
 */
internal object SyncValues {

    private val json = Json { encodeDefaults = true }

    fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, value: String): T = json.decodeFromString(serializer, value)

    fun bool(value: Boolean) = if (value) "1" else "0"

    fun bytes(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    fun bytes(value: String): ByteArray = Base64.getDecoder().decode(value)

    val metadataList = ListSerializer(MetadataValue.serializer())

    @Serializable
    data class MangaSettings(val viewer: Long, val chapterFlags: Long)

    /** Enough to create the entry on a device that does not have it, before its source is asked. */
    @Serializable
    data class MangaInfo(
        val title: String,
        val author: String?,
        val artist: String?,
        val description: String?,
        val genre: List<String>?,
        val status: Long,
        val thumbnailUrl: String?,
        val updateStrategy: Long,
        val initialized: Boolean,
    )

    /** Enough to create the chapter when the entry is created from the sync. */
    @Serializable
    data class ChapterInfo(
        val name: String,
        val scanlator: String?,
        val number: Double,
        val sourceOrder: Long,
        val dateFetch: Long,
        val dateUpload: Long,
    )

    @Serializable
    data class Category(val order: Long, val flags: Long)

    @Serializable
    data class Track(
        val remoteId: Long,
        val libraryId: Long?,
        val title: String,
        val lastChapterRead: Double,
        val totalChapters: Long,
        val status: Long,
        val score: Double,
        val remoteUrl: String,
        val startDate: Long,
        val finishDate: Long,
        val private: Boolean,
    )

    @Serializable
    data class ExtensionStore(
        val name: String,
        val badgeLabel: String,
        val signingKey: String,
        val contactWebsite: String,
        val contactDiscord: String?,
        val isLegacy: Boolean,
        val extensionListUrl: String?,
    )

    /** A closed reading session, without its local ids and without first_completion, which each device works out. */
    @Serializable
    data class Session(
        val sourceId: Long,
        val mangaUrl: String,
        val chapterUrl: String,
        val mangaTitle: String,
        val chapterName: String,
        val chapterNumber: Double,
        val startedAt: Long,
        val endedAt: Long,
        val activeMs: Long,
        val firstPage: Long,
        val lastPage: Long,
        val pagesVisited: Long,
        val pagesNew: Long,
        val pagesReread: Long,
        val pageAdvances: Long,
        val totalPages: Long?,
        val pagesBits: String,
        val completedAt: Long?,
        val completedOn: String?,
        val origin: Long,
        val state: Long,
        val zoneId: String,
        val days: List<SessionDay>,
    )

    @Serializable
    data class SessionDay(val date: String, val activeMs: Long, val pagesNew: Long, val pagesVisited: Long)

    @Serializable
    data class Goal(val target: Long, val isPrimary: Boolean, val updatedAt: Long)

    @Serializable
    data class Collection(
        val kind: Long,
        val name: String,
        val createdAt: Long,
        val standalone: Boolean,
        val items: List<CollectionItem>,
    )

    @Serializable
    data class CollectionItem(
        val position: Long,
        val sourceId: Long,
        val mangaUrl: String,
        val chapterUrl: String?,
        val label: String,
    )

    @Serializable
    data class Plan(
        val collectionUuid: String,
        val pacePerWeek: Long,
        val startedAt: Long,
        val startedOn: String,
        val paused: Boolean,
    )

    @Serializable
    data class MetadataValue(val position: Long, val value: String, val origin: Long)

    @Serializable
    data class Follow(
        val label: String,
        val seriesName: String?,
        val seriesYear: Long?,
        val number: String?,
        val createdAt: Long,
    )

    @Serializable
    data class Notified(
        val seriesKey: String,
        val seriesName: String,
        val seriesYear: Long?,
        val number: String,
        val storeDate: String?,
        val notifiedAt: Long,
    )

    @Serializable
    data class Feedback(val verdict: Long, val untilAt: Long?, val createdAt: Long)
}
