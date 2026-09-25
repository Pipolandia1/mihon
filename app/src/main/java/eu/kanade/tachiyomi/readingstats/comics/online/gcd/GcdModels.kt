package eu.kanade.tachiyomi.readingstats.comics.online.gcd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Grand Comics Database API responses (comics.org/api/schema/), named as the API sends them. Links
// to other records are API URLs; only what comic metadata maps is kept.

@Serializable
data class GcdPage<T>(
    val count: Int = 0,
    val next: String? = null,
    val results: List<T> = emptyList(),
)

@Serializable
data class GcdSeries(
    @SerialName("api_url") val apiUrl: String,
    val name: String = "",
    @SerialName("year_began") val yearBegan: Int? = null,
    @SerialName("year_ended") val yearEnded: Int? = null,
    /** API URLs of the series' issues, in the series' order, variants included. */
    @SerialName("active_issues") val activeIssues: List<String> = emptyList(),
    /** Alongside [activeIssues]: "1 [Direct Sales]", "1 [Second Printing]", "2". */
    @SerialName("issue_descriptors") val issueDescriptors: List<JsonElement> = emptyList(),
    @SerialName("publishing_format") val publishingFormat: String = "",
    val binding: String = "",
    val language: String = "",
    /** API URL of the publisher. */
    val publisher: String? = null,
)

@Serializable
data class GcdPublisher(
    val name: String = "",
)

@Serializable
data class GcdStory(
    val type: String = "",
    val title: String = "",
    val script: String = "",
    val pencils: String = "",
    val inks: String = "",
    val colors: String = "",
    val letters: String = "",
    val editing: String = "",
    val characters: String = "",
)

@Serializable
data class GcdIssue(
    @SerialName("api_url") val apiUrl: String,
    val number: String = "",
    @SerialName("variant_name") val variantName: String = "",
    /** API URL of the issue this one is a variant of; null for the issue itself. */
    @SerialName("variant_of") val variantOf: String? = null,
    /** "2011-07-00" when only the month is known. */
    @SerialName("key_date") val keyDate: String = "",
    @SerialName("on_sale_date") val onSaleDate: String = "",
    /** A decimal: "44.000". */
    @SerialName("page_count") val pageCount: String? = null,
    val editing: String = "",
    @SerialName("story_set") val stories: List<GcdStory> = emptyList(),
)
