package tachiyomi.domain.readingstats.analysis

import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicMetadata

/** What a ranking groups reading by. Series needs no metadata; the others use confirmed values. */
enum class RankingDimension(val field: ComicField?) {
    SERIES(null),
    PUBLISHER(ComicField.PUBLISHER),
    IMPRINT(ComicField.IMPRINT),
    WRITER(ComicField.WRITER),
    ARTIST(ComicField.ARTIST),
    GENRE(ComicField.GENRE),
    CHARACTER(ComicField.CHARACTER),
    UNIVERSE(ComicField.UNIVERSE),

    /** From the chapter's cover date, else the series' start year: "1990" stands for the 1990s. */
    DECADE(null),
}

/** Active time spent on one chapter (or, once it is gone, one entry) in a period. */
data class ChapterReading(
    val mangaId: Long?,
    val chapterId: Long?,
    /** The entry's title as it was read. */
    val title: String,
    val activeMs: Long,
)

data class RankingEntry(
    val value: String,
    val activeMs: Long,
    /** Of all the period's reading time, not only the classified part. */
    val share: Double,
)

data class Ranking(
    val dimension: RankingDimension,
    /** Most time first; unknown is never an entry. */
    val entries: List<RankingEntry>,
    val totalMs: Long,
    /** Time whose chapters have a confirmed value for the dimension. */
    val classifiedMs: Long,
) {
    val coverage: Double get() = if (totalMs == 0L) 0.0 else classifiedMs.toDouble() / totalMs

    /** Shown only when enough of the time is classified to say something about all of it. */
    val shown: Boolean get() = entries.isNotEmpty() && coverage >= Rankings.MIN_COVERAGE
}

object Rankings {

    /** Share of the period's reading time that must be classified before a ranking is shown. */
    const val MIN_COVERAGE = 0.6

    const val TOP = 5

    private const val DECADE_YEARS = 10
    private const val YEAR_DIGITS = 4

    /**
     * Reading time by value of [dimension]. A chapter with several values (two writers) counts in
     * full for each; a chapter with none counts as unclassified and is never ranked.
     */
    fun of(
        dimension: RankingDimension,
        readings: List<ChapterReading>,
        metadata: Map<Long, ComicMetadata>,
        top: Int = TOP,
    ): Ranking {
        val totals = HashMap<String, Long>()
        var classified = 0L
        readings.forEach { reading ->
            val values = valuesOf(dimension, reading, reading.mangaId?.let(metadata::get))
            if (values.isEmpty()) return@forEach
            classified += reading.activeMs
            values.forEach { totals[it] = (totals[it] ?: 0) + reading.activeMs }
        }
        val total = readings.sumOf { it.activeMs }
        val entries = totals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(top)
            .map { RankingEntry(it.key, it.value, if (total == 0L) 0.0 else it.value.toDouble() / total) }
        return Ranking(dimension, entries, total, classified)
    }

    private fun valuesOf(dimension: RankingDimension, reading: ChapterReading, metadata: ComicMetadata?): List<String> {
        if (dimension == RankingDimension.SERIES) return listOf(reading.title)
        if (metadata == null) return emptyList()
        val chapterId = reading.chapterId
        if (dimension == RankingDimension.DECADE) {
            val year = chapterId?.let { metadata.chapter(it, ComicField.COVER_DATE)?.value?.take(YEAR_DIGITS) }
                ?.toIntOrNull()
                ?: metadata.series(ComicField.START_YEAR)?.value?.toIntOrNull()
            return listOfNotNull(year?.let { (it / DECADE_YEARS * DECADE_YEARS).toString() })
        }
        val field = dimension.field ?: return emptyList()
        val value = if (chapterId != null) metadata.effective(chapterId, field) else metadata.series(field)
        return value?.values?.map(String::trim)?.filter(String::isNotEmpty)?.distinct().orEmpty()
    }
}
