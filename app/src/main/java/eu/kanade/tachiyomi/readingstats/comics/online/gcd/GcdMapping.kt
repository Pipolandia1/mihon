package eu.kanade.tachiyomi.readingstats.comics.online.gcd

import eu.kanade.tachiyomi.readingstats.comics.online.IssueRef
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.IssueNumbers
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue

/** Turns GCD series and issues into metadata suggestions. The GCD writes credits as text. */
object GcdMapping {

    private val ID = Regex("""/(?:series|issue|publisher)/(\d+)/""")

    /** The record id in one of the GCD's API URLs. */
    fun idOf(apiUrl: String?): Long? = apiUrl?.let { ID.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** The series' issues with their numbers; "1 [Direct Sales]" is issue 1. */
    fun issuesOf(series: GcdSeries): List<IssueRef> =
        series.activeIssues.mapIndexedNotNull { index, url ->
            val id = idOf(url) ?: return@mapIndexedNotNull null
            val descriptor = (series.issueDescriptors.getOrNull(index) as? JsonPrimitive)?.content.orEmpty()
            IssueRef(id, descriptor.substringBefore('[').trim())
        }

    fun seriesValues(series: GcdSeries, publisher: String?): List<MetadataValue> = buildList {
        fun add(field: ComicField, value: String?) {
            if (!value.isNullOrBlank()) add(MetadataValue(field, null, listOf(value.trim()), MetadataOrigin.GCD))
        }
        add(ComicField.GCD_ID, idOf(series.apiUrl)?.toString())
        add(ComicField.SERIES_NAME, series.name)
        add(ComicField.START_YEAR, series.yearBegan?.toString())
        add(ComicField.END_YEAR, series.yearEnded?.toString())
        add(ComicField.PUBLISHER, publisher)
        add(ComicField.UNIT, unitOf(series).name)
        // A total only once the series has ended: its distinct numbers, printings and variants aside.
        if (series.yearEnded != null) {
            val numbers = issuesOf(series).map { it.number }.filter(String::isNotEmpty).distinct()
            if (numbers.isNotEmpty()) add(ComicField.TOTAL_ISSUES, numbers.size.toString())
        }
    }

    /** From the publishing format and binding: collected editions by binding, the rest are issues. */
    fun unitOf(series: GcdSeries): ComicUnit {
        val format = series.publishingFormat.lowercase()
        val binding = series.binding.lowercase()
        return when {
            "omnibus" in format || "omnibus" in binding -> ComicUnit.OMNIBUS
            "graphic novel" in format -> ComicUnit.OTHER
            "collected" in format || "trade paperback" in format || "trade paperback" in binding -> when {
                "hardcover" in binding -> ComicUnit.HARDCOVER
                else -> ComicUnit.TPB
            }
            else -> ComicUnit.ISSUE
        }
    }

    fun issueValues(issue: GcdIssue, chapterId: Long): List<MetadataValue> = buildList {
        fun add(field: ComicField, values: List<String>) {
            val kept = values.map(String::trim).filter(String::isNotEmpty).distinct()
            if (kept.isNotEmpty()) add(MetadataValue(field, chapterId, kept, MetadataOrigin.GCD))
        }
        val comics = issue.stories.filter { "comic story" in it.type.lowercase() }
        val covers = issue.stories.filter { it.type.lowercase() == "cover" }
        add(ComicField.GCD_ID, listOfNotNull(idOf(issue.apiUrl)?.toString()))
        if (issue.number.isNotBlank()) add(ComicField.ISSUE_LABEL, listOf(IssueNumbers.labelOf(issue.number)))
        add(ComicField.COVER_DATE, listOfNotNull(dateOf(issue.keyDate) ?: dateOf(issue.onSaleDate)))
        val pages = issue.pageCount?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        add(ComicField.PAGE_COUNT, listOfNotNull(pages?.toString()))
        add(ComicField.WRITER, comics.flatMap { people(it.script) })
        add(ComicField.ARTIST, comics.flatMap { people(it.pencils) + people(it.inks) })
        add(ComicField.COLORIST, comics.flatMap { people(it.colors) })
        add(ComicField.LETTERER, comics.flatMap { people(it.letters) })
        add(ComicField.COVER_ARTIST, covers.flatMap { people(it.pencils) + people(it.inks) })
        add(ComicField.EDITOR, people(issue.editing) + issue.stories.flatMap { people(it.editing) })
        val cast = comics.map { characters(it.characters) }
        add(ComicField.CHARACTER, cast.flatMap { it.first })
        add(ComicField.TEAM, cast.flatMap { it.second })
        if (issue.variantOf != null) add(ComicField.VARIANT, listOf(issue.variantName))
    }

    /** "2011-07-00" is July 2011: "2011-07". */
    fun dateOf(text: String): String? {
        val parts = text.trim().split('-').takeWhile { it.isNotEmpty() && it.any { c -> c != '0' } }
        if (parts.isEmpty() || parts[0].length != YEAR_DIGITS) return null
        return parts.joinToString("-")
    }

    /**
     * Credit text as the GCD writes it: "Andy Kubert (signed); Sandra Hope". Notes in brackets go;
     * "None", "?" and "typeset" are not people.
     */
    fun people(text: String): List<String> =
        splitTopLevel(text)
            .map { it.replace(NOTES, "").replace(ALIASES, "").trim().trimEnd('?').trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in NOT_PEOPLE }

    /**
     * Characters as the GCD writes them: "Flash [Barry Allen]; Nora Allen (in flashback);
     * Justice League [Batman [Bruce Wayne]; Superman]; Reverse-Flash [Professor Zoom; Eobard Thawne]".
     * Brackets hold a team's members when a member has brackets of its own, and otherwise the
     * character's other names. Team members are characters too. Returns (characters, teams).
     */
    fun characters(text: String): Pair<List<String>, List<String>> {
        val characters = mutableListOf<String>()
        val teams = mutableListOf<String>()
        splitTopLevel(text).forEach { raw ->
            val entry = raw.replace(ROLE_PREFIX, "").replace(NOTES, "").trim()
            val open = entry.indexOf('[')
            val inside = if (open >= 0 && entry.endsWith(']')) entry.substring(open + 1, entry.length - 1) else null
            val members = inside?.let(::splitTopLevel).orEmpty()
            if (inside != null && members.size > 1 && members.any { '[' in it }) {
                teams += entry.substring(0, open).trim()
                val (teamMembers, subTeams) = characters(inside)
                characters += teamMembers
                teams += subTeams
            } else if (entry.isNotEmpty()) {
                characters += entry
            }
        }
        return characters.distinct() to teams.filter(String::isNotEmpty).distinct()
    }

    /** Splits on ';' outside brackets and parentheses. */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        text.forEach { char ->
            when (char) {
                '[', '(' -> depth++
                ']', ')' -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (char == ';' && depth == 0) {
                parts += current.toString().trim()
                current.clear()
            } else {
                current.append(char)
            }
        }
        parts += current.toString().trim()
        return parts.filter(String::isNotEmpty)
    }

    private const val YEAR_DIGITS = 4
    private val NOTES = Regex("""\s*\([^()]*\)""")
    private val ALIASES = Regex("""\s*\[[^\[\]]*]""")

    /** "VILLAINS: Joker", "GUESTS: Robin". */
    private val ROLE_PREFIX = Regex("""^[A-Z][A-Z ]+:\s*""")
    private val NOT_PEOPLE = setOf("none", "?", "typeset", "various", "unknown", "uncredited")
}
