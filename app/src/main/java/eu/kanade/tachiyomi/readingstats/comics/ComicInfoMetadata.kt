package eu.kanade.tachiyomi.readingstats.comics

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.IssueNumbers
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue

/**
 * The ComicInfo.xml fields comic metadata reads (anansi-project ComicInfo v2.0), including those
 * Mihon's own ComicInfo model leaves out. Everything is text, read leniently.
 */
@Serializable
@XmlSerialName("ComicInfo", "", "")
data class ComicInfoMetadata(
    @XmlElement(true) @XmlSerialName("Series", "", "") val series: String? = null,
    @XmlElement(true) @XmlSerialName("Number", "", "") val number: String? = null,
    @XmlElement(true) @XmlSerialName("Count", "", "") val count: String? = null,
    @XmlElement(true) @XmlSerialName("Volume", "", "") val volume: String? = null,
    @XmlElement(true) @XmlSerialName("Year", "", "") val year: String? = null,
    @XmlElement(true) @XmlSerialName("Month", "", "") val month: String? = null,
    @XmlElement(true) @XmlSerialName("Day", "", "") val day: String? = null,
    @XmlElement(true) @XmlSerialName("Writer", "", "") val writer: String? = null,
    @XmlElement(true) @XmlSerialName("Penciller", "", "") val penciller: String? = null,
    @XmlElement(true) @XmlSerialName("Inker", "", "") val inker: String? = null,
    @XmlElement(true) @XmlSerialName("Colorist", "", "") val colorist: String? = null,
    @XmlElement(true) @XmlSerialName("Letterer", "", "") val letterer: String? = null,
    @XmlElement(true) @XmlSerialName("CoverArtist", "", "") val coverArtist: String? = null,
    @XmlElement(true) @XmlSerialName("Editor", "", "") val editor: String? = null,
    @XmlElement(true) @XmlSerialName("Publisher", "", "") val publisher: String? = null,
    @XmlElement(true) @XmlSerialName("Imprint", "", "") val imprint: String? = null,
    @XmlElement(true) @XmlSerialName("Genre", "", "") val genre: String? = null,
    @XmlElement(true) @XmlSerialName("PageCount", "", "") val pageCount: String? = null,
    @XmlElement(true) @XmlSerialName("Format", "", "") val format: String? = null,
    @XmlElement(true) @XmlSerialName("StoryArc", "", "") val storyArc: String? = null,
    @XmlElement(true) @XmlSerialName("Characters", "", "") val characters: String? = null,
    @XmlElement(true) @XmlSerialName("Teams", "", "") val teams: String? = null,
)

/** Suggestions from one chapter's ComicInfo.xml: the series' fields, and the chapter's own. */
object ComicInfoMapping {

    fun seriesValues(info: ComicInfoMetadata): List<MetadataValue> = buildList {
        fun add(field: ComicField, value: String?) {
            val text = value?.trim()
            if (!text.isNullOrEmpty()) add(MetadataValue(field, null, listOf(text), MetadataOrigin.COMIC_INFO))
        }
        add(ComicField.SERIES_NAME, info.series)
        add(ComicField.VOLUME, info.volume?.takeIf { it.trim().toIntOrNull() != null })
        add(ComicField.PUBLISHER, info.publisher)
        add(ComicField.IMPRINT, info.imprint)
        add(ComicField.TOTAL_ISSUES, info.count?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.toString())
        unitOf(info.format)?.let { add(ComicField.UNIT, it.name) }
        list(info.genre).takeIf { it.isNotEmpty() }?.let {
            add(MetadataValue(ComicField.GENRE, null, it, MetadataOrigin.COMIC_INFO))
        }
    }

    fun chapterValues(info: ComicInfoMetadata, chapterId: Long): List<MetadataValue> = buildList {
        fun add(field: ComicField, values: List<String>) {
            if (values.isNotEmpty()) add(MetadataValue(field, chapterId, values, MetadataOrigin.COMIC_INFO))
        }
        info.number?.takeIf { it.isNotBlank() }?.let { add(ComicField.ISSUE_LABEL, listOf(IssueNumbers.labelOf(it))) }
        dateOf(info)?.let { add(ComicField.COVER_DATE, listOf(it)) }
        info.pageCount?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { add(ComicField.PAGE_COUNT, listOf("$it")) }
        add(ComicField.WRITER, list(info.writer))
        add(ComicField.ARTIST, (list(info.penciller) + list(info.inker)).distinct())
        add(ComicField.COLORIST, list(info.colorist))
        add(ComicField.LETTERER, list(info.letterer))
        add(ComicField.COVER_ARTIST, list(info.coverArtist))
        add(ComicField.EDITOR, list(info.editor))
        add(ComicField.CHARACTER, list(info.characters))
        add(ComicField.TEAM, list(info.teams))
        add(ComicField.ARC, list(info.storyArc))
    }

    /** ComicInfo's Format: "TPB", "Hardcover", "Omnibus"...; issue formats say nothing to change. */
    fun unitOf(format: String?): ComicUnit? {
        val text = format?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            "omnibus" in text -> ComicUnit.OMNIBUS
            "hardcover" in text || text == "hc" -> ComicUnit.HARDCOVER
            "tpb" in text || "trade" in text -> ComicUnit.TPB
            "graphic novel" in text -> ComicUnit.OTHER
            else -> null
        }
    }

    private fun dateOf(info: ComicInfoMetadata): String? {
        val year = info.year?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val month = info.month?.trim()?.toIntOrNull()?.takeIf { it in 1..12 }
        val day = info.day?.trim()?.toIntOrNull()?.takeIf { it in 1..31 }
        return buildString {
            append(year.toString().padStart(4, '0'))
            if (month != null) {
                append('-').append(month.toString().padStart(2, '0'))
                if (day != null) append('-').append(day.toString().padStart(2, '0'))
            }
        }
    }

    /** ComicInfo lists are comma separated. */
    private fun list(text: String?): List<String> =
        text.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()
}
