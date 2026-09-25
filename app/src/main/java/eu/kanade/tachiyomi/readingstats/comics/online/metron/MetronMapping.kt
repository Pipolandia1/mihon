package eu.kanade.tachiyomi.readingstats.comics.online.metron

import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.IssueNumbers
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue

/** Turns Metron's series and issues into metadata suggestions. */
object MetronMapping {

    fun seriesValues(series: MetronSeries): List<MetadataValue> = buildList {
        add(ComicField.METRON_ID, series.id.toString())
        add(ComicField.SERIES_NAME, series.name)
        series.volume?.let { add(ComicField.VOLUME, it.toString()) }
        series.yearBegan?.let { add(ComicField.START_YEAR, it.toString()) }
        series.yearEnd?.let { add(ComicField.END_YEAR, it.toString()) }
        series.publisher?.let { add(ComicField.PUBLISHER, it.name) }
        series.imprint?.let { add(ComicField.IMPRINT, it.name) }
        series.genres.map { it.name }.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
            add(MetadataValue(ComicField.GENRE, null, it, MetadataOrigin.METRON))
        }
        add(ComicField.UNIT, unitOf(series.seriesType?.name).name)
        // A total only once the series has ended; an ongoing count would read as a finish line.
        if (ended(series)) series.issueCount?.let { add(ComicField.TOTAL_ISSUES, it.toString()) }
    }

    /** Metron's series types: collected editions by their format, everything else counted as issues. */
    fun unitOf(seriesType: String?): ComicUnit {
        val type = seriesType.orEmpty().lowercase()
        return when {
            "omnibus" in type -> ComicUnit.OMNIBUS
            "hard cover" in type || "hardcover" in type -> ComicUnit.HARDCOVER
            "trade paperback" in type -> ComicUnit.TPB
            "graphic novel" in type -> ComicUnit.OTHER
            else -> ComicUnit.ISSUE
        }
    }

    fun ended(series: MetronSeries): Boolean {
        val status = series.status.lowercase()
        return status == "completed" || status == "cancelled" || (status.isEmpty() && series.yearEnd != null)
    }

    fun issueValues(issue: MetronIssue, chapterId: Long): List<MetadataValue> = buildList {
        fun chapter(field: ComicField, values: List<String>) {
            val kept = values.map(String::trim).filter(String::isNotEmpty).distinct()
            if (kept.isNotEmpty()) add(MetadataValue(field, chapterId, kept, MetadataOrigin.METRON))
        }
        chapter(ComicField.METRON_ID, listOf(issue.id.toString()))
        if (issue.number.isNotBlank()) chapter(ComicField.ISSUE_LABEL, listOf(IssueNumbers.labelOf(issue.number)))
        chapter(ComicField.COVER_DATE, listOfNotNull(issue.coverDate ?: issue.storeDate))
        chapter(ComicField.PAGE_COUNT, listOfNotNull(issue.pageCount?.takeIf { it > 0 }?.toString()))
        CREDIT_FIELDS.forEach { field ->
            val people = issue.credits.filter { credit -> credit.role.any { fieldOf(it.name) == field } }
            chapter(field, people.map { it.creator })
        }
        chapter(ComicField.CHARACTER, issue.characters.map { it.name })
        chapter(ComicField.TEAM, issue.teams.map { it.name })
        chapter(ComicField.UNIVERSE, issue.universes.map { it.name })
        chapter(ComicField.ARC, issue.arcs.map { it.name })
        chapter(ComicField.CONTAINS_ISSUES, issue.reprints.map { it.issue })
    }

    /** The field a Metron credit role fills; cover and editing roles first, as their names overlap. */
    fun fieldOf(role: String): ComicField? {
        val name = role.lowercase()
        return when {
            "cover" in name -> ComicField.COVER_ARTIST
            "editor" in name -> ComicField.EDITOR
            "color" in name || "colour" in name -> ComicField.COLORIST
            "letter" in name -> ComicField.LETTERER
            listOf("writer", "script", "plot", "story").any { it in name } -> ComicField.WRITER
            listOf("artist", "pencil", "ink", "illustrat").any { it in name } -> ComicField.ARTIST
            else -> null
        }
    }

    private fun MutableList<MetadataValue>.add(field: ComicField, value: String) {
        if (value.isNotBlank()) add(MetadataValue(field, null, listOf(value.trim()), MetadataOrigin.METRON))
    }

    private val CREDIT_FIELDS = listOf(
        ComicField.WRITER,
        ComicField.ARTIST,
        ComicField.COLORIST,
        ComicField.LETTERER,
        ComicField.COVER_ARTIST,
        ComicField.EDITOR,
    )
}
