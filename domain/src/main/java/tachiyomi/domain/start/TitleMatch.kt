package tachiyomi.domain.start

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** A series title reduced to what identifies it: the name without decoration, and its year. */
data class TitleKey(val name: String, val year: Int?)

/** How well a search result names a catalogue series, the best first. */
enum class MatchQuality { EXACT_YEAR, EXACT, NEAR_YEAR }

/**
 * Whether a title in a source is the series the catalogue names (docs/HOME_PLAN.md §7.3, H4).
 *
 * Strict on purpose: nothing checks the choice before the Início shows it, and a recommendation of
 * the wrong series is worse than none. The names must be equal once the year, "Vol.", punctuation,
 * accents and a leading article are set aside, and the years equal when both sides have one. Nearly
 * equal names ([NEAR]) count only with equal years. Two different series tied for best — "Batman
 * (2016)" and "Batman (2025-)" for a catalogue "Batman" of unknown year — are no answer.
 */
object TitleMatch {

    /** Normalized similarity from which two names count as nearly equal, with equal years. */
    const val NEAR = 0.9

    fun key(title: String, year: Int? = null): TitleKey {
        val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        val name = plain
            .replace(BRACKETED, " ")
            .replace(VOLUME, " ")
            .replace("&", " and ")
            .replace(NOT_ALNUM, " ")
            .trim()
            .replace(SPACES, " ")
            .replace(ARTICLE, "")
        return TitleKey(name, year ?: YEAR.find(plain)?.groupValues?.get(1)?.toInt())
    }

    fun quality(series: TitleKey, title: String): MatchQuality? {
        val found = key(title)
        if (series.name.isEmpty() || found.name.isEmpty()) return null
        val bothYears = series.year != null && found.year != null
        if (bothYears && series.year != found.year) return null
        return when {
            series.name == found.name -> if (bothYears) MatchQuality.EXACT_YEAR else MatchQuality.EXACT
            bothYears && similarity(series.name, found.name) >= NEAR -> MatchQuality.NEAR_YEAR
            else -> null
        }
    }

    /** The result that is this series, or null when none is, or two different ones tie. */
    fun <T> best(name: String, year: Int?, results: List<T>, title: (T) -> String): T? {
        val series = key(name, year)
        val scored = results.mapNotNull { result -> quality(series, title(result))?.let { result to it } }
        val top = scored.minOfOrNull { it.second } ?: return null
        val tied = scored.filter { it.second == top }
        if (tied.map { key(title(it.first)) }.distinct().size > 1) return null
        return tied.first().first
    }

    /** 1 minus the edit distance over the longer length: 1.0 for equal names. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            previous = current.also { current = previous }
        }
        return 1.0 - previous[b.length].toDouble() / longest
    }

    private val MARKS = Regex("\\p{M}+")
    private val BRACKETED = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val VOLUME = Regex("\\bvol(?:ume)?\\.?\\s*\\d+\\b")
    private val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    private val ARTICLE = Regex("^(?:the|a|an) ")

    /** The start year in brackets: "(2024-)", "(2016-2020)", "[2011]", "(2024 series)". */
    private val YEAR = Regex("[(\\[{]\\s*((?:19|20)\\d{2})")
}
