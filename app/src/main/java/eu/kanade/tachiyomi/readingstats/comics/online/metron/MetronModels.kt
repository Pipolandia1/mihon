package eu.kanade.tachiyomi.readingstats.comics.online.metron

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Metron API responses, named as the API sends them (the official Mokkari client renames some:
// "series" is its display_name, "title" its collection_title, "name" its story_titles, "page" its
// page_count). Only what comic metadata maps is kept; the rest is ignored.

@Serializable
data class MetronPage<T>(
    val count: Int = 0,
    val next: String? = null,
    val results: List<T> = emptyList(),
)

@Serializable
data class MetronItem(
    val id: Long,
    val name: String = "",
)

/** A series as a search lists it. */
@Serializable
data class MetronSeriesSummary(
    val id: Long,
    @SerialName("series") val displayName: String = "",
    @SerialName("year_began") val yearBegan: Int? = null,
    @SerialName("year_end") val yearEnd: Int? = null,
    @SerialName("issue_count") val issueCount: Int? = null,
    val volume: Int? = null,
    val publisher: MetronItem? = null,
    @SerialName("series_type") val seriesType: MetronItem? = null,
)

@Serializable
data class MetronSeries(
    val id: Long,
    val name: String = "",
    val volume: Int? = null,
    @SerialName("year_began") val yearBegan: Int? = null,
    @SerialName("year_end") val yearEnd: Int? = null,
    @SerialName("issue_count") val issueCount: Int? = null,
    val status: String = "",
    val publisher: MetronItem? = null,
    val imprint: MetronItem? = null,
    @SerialName("series_type") val seriesType: MetronItem? = null,
    val genres: List<MetronItem> = emptyList(),
    @SerialName("resource_url") val resourceUrl: String? = null,
)

/** An issue as a series' issue list gives it. */
@Serializable
data class MetronIssueSummary(
    val id: Long,
    val number: String = "",
    @SerialName("cover_date") val coverDate: String? = null,
    val modified: String? = null,
)

@Serializable
data class MetronCredit(
    /** The creator's id (not the credit's): Metron's credit serializer gives `creator.id` here. */
    val id: Long? = null,
    val creator: String = "",
    val role: List<MetronItem> = emptyList(),
)

@Serializable
data class MetronReprint(
    val id: Long,
    val issue: String = "",
)

@Serializable
data class MetronIssue(
    val id: Long,
    val number: String = "",
    @SerialName("alt_number") val altNumber: String = "",
    @SerialName("title") val collectionTitle: String = "",
    @SerialName("cover_date") val coverDate: String? = null,
    @SerialName("store_date") val storeDate: String? = null,
    @SerialName("page") val pageCount: Int? = null,
    val publisher: MetronItem? = null,
    val imprint: MetronItem? = null,
    val arcs: List<MetronItem> = emptyList(),
    val credits: List<MetronCredit> = emptyList(),
    val characters: List<MetronItem> = emptyList(),
    val teams: List<MetronItem> = emptyList(),
    val universes: List<MetronItem> = emptyList(),
    val reprints: List<MetronReprint> = emptyList(),
    val modified: String? = null,
    @SerialName("resource_url") val resourceUrl: String? = null,
)

/** An issue as a filtered issue list gives it (the Início's discovery, H5): what matters is its series. */
@Serializable
data class MetronIssueListItem(
    val id: Long,
    val series: MetronIssueSeries? = null,
    val number: String = "",
    @SerialName("store_date") val storeDate: String? = null,
    /** The cover. */
    val image: String? = null,
)

@Serializable
data class MetronIssueSeries(
    val id: Long,
    val name: String = "",
    val volume: Int? = null,
    @SerialName("year_began") val yearBegan: Int? = null,
)

/** What the Início asks Metron for issues by: its name search, and the issue list's filter. */
enum class MetronNamed(val path: String, val filter: String) {
    CREATOR("creator", "creator_id"),
    CHARACTER("character", "character_id"),
    TEAM("team", "team_id"),
}
