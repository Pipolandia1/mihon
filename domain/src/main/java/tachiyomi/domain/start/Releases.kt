package tachiyomi.domain.start

import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** Where a week's list of releases came from. */
enum class ReleaseOrigin(val id: String) {
    METRON("metron"),
    GCD("gcd"),
    ;

    companion object {
        fun of(id: String) = entries.first { it.id == id }
    }
}

/**
 * One comic of a week's releases. [key]: "metron:issue:1234"; [seriesKey]: "metron:series:10".
 * [storeDate] ("2026-09-23") only when the list says the day; the GCD's says only the week.
 * [variantOf]: the regular cover's [key], for a variant the GCD lists on its own.
 */
data class ReleaseItem(
    val key: String,
    val seriesKey: String,
    val seriesName: String,
    val seriesYear: Int?,
    val number: String,
    val storeDate: String?,
    val coverUrl: String?,
    val variantOf: String? = null,
)

/** An ISO week: Monday to Sunday, numbered within its week-based year. */
data class ReleaseWeek(val year: Int, val week: Int) : Comparable<ReleaseWeek> {

    val monday: LocalDate
        get() = LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val sunday: LocalDate get() = monday.plusDays(6)

    fun plus(weeks: Long): ReleaseWeek = of(monday.plusWeeks(weeks))

    override fun compareTo(other: ReleaseWeek) = compareValuesBy(this, other, { it.year }, { it.week })

    /** "2026-W39". */
    override fun toString() = "$year-W${week.toString().padStart(2, '0')}"

    companion object {
        fun of(
            date: LocalDate,
        ) = ReleaseWeek(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }
}

/** Why a release matters to this reader. */
sealed interface ReleaseReason {
    /** A series the reader has or read: [mangaId] is the reader's entry. */
    data class YourSeries(val mangaId: Long, val title: String) : ReleaseReason

    /** A series the catalogue lists for a name the reader reads (H5). */
    data class ByName(val kind: EntityKind, val name: String) : ReleaseReason
}

data class RelevantRelease(val item: ReleaseItem, val reason: ReleaseReason)

/** A week's releases as kept on the device, and where and when they were fetched. */
data class ReleaseWeekList(
    val week: ReleaseWeek,
    val origin: ReleaseOrigin,
    val fetchedAt: Long,
    val items: List<ReleaseItem>,
)

interface ReleaseRepository {
    fun subscribeWeek(week: ReleaseWeek): Flow<ReleaseWeekList?>
    suspend fun getWeek(week: ReleaseWeek): ReleaseWeekList?
    suspend fun replaceWeek(list: ReleaseWeekList)
}

/**
 * The week's releases for the Início and the calendar (docs/HOME_PLAN.md §8, H6).
 *
 * One entry per issue: a variant cover the GCD lists on its own joins its regular cover. What
 * matters to the reader comes first — a series they have or read, matched by the catalogue's id when
 * the metadata has it and by name and year otherwise; then a series the catalogue lists for a name
 * they read. Nothing turned down comes back.
 */
object Releases {

    /** A week is fetched again at most this often while it can still change. */
    const val REFETCH_MS = 24L * 60 * 60 * 1000

    fun grouped(items: List<ReleaseItem>): List<ReleaseItem> {
        val keys = items.map { it.key }.toSet()
        val seenVariantOf = mutableSetOf<String>()
        // The same issue from two lists (the week's, and a followed creator's) is one release.
        return items.distinctBy { it.key }.filter { item ->
            val regular = item.variantOf ?: return@filter true
            // A variant whose regular cover is listed goes; the first of the rest stands for it.
            regular !in keys && seenVariantOf.add(regular)
        }
    }

    /** The GCD's "24 [Clay Mann Cardstock Variant Cover]" is issue "24". */
    fun gcdNumber(descriptor: String): String = descriptor.substringBefore('[').trim()

    /**
     * Whether a fetched week may still change, so it is worth fetching again after [REFETCH_MS]:
     * a week fetched before it ended may; one fetched after never does.
     */
    fun stale(list: ReleaseWeekList, today: LocalDate, nowMs: Long): Boolean {
        val endedWhenFetched = list.fetchedAt >= list.week.sunday.plusDays(1).toEpochDay() * DAY_MS
        if (endedWhenFetched) return false
        return nowMs - list.fetchedAt >= REFETCH_MS || today > list.week.sunday
    }

    fun relevant(
        items: List<ReleaseItem>,
        works: List<WorkFacts>,
        answers: List<CatalogAnswer>,
        feedback: List<Feedback>,
        nowMs: Long,
    ): List<RelevantRelease> {
        val hidden = Recommender.hiddenWorks(feedback, nowMs)
        val unwanted = Recommender.unwantedNames(feedback, nowMs)
        val own = works.filter { (it.inLibrary || it.chaptersRead > 0) && it.mangaId !in hidden }
        val model = Affinity.model(works, nowMs)
        val names = answers.filter { it.key !in unwanted }
            .sortedWith(compareByDescending<CatalogAnswer> { model.affinity(it.key) }.thenBy { it.key.name })
        val yours = mutableListOf<Pair<RelevantRelease, WorkFacts>>()
        val byName = mutableListOf<RelevantRelease>()
        grouped(items).forEach { item ->
            val series = TitleMatch.key(item.seriesName, item.seriesYear)
            val work = ownerOf(item, own)
            if (work != null) {
                yours += RelevantRelease(item, ReleaseReason.YourSeries(work.mangaId, work.title)) to work
                return@forEach
            }
            val answer = names.firstOrNull { answer -> answer.series.any { listed(it, item, series) } }
                ?: return@forEach
            val name = model.readWorks(answer.key).firstOrNull()?.displayName ?: return@forEach
            byName += RelevantRelease(item, ReleaseReason.ByName(answer.key.kind, name))
        }
        return yours.sortedWith(
            compareByDescending<Pair<RelevantRelease, WorkFacts>> { it.second.lastReadAt ?: 0L }
                .thenBy { it.first.item.seriesName },
        )
            .map { it.first } + byName
    }

    /**
     * The work of [own] that is [item]'s series: by the catalogue's id in its metadata first, else by
     * name and year.
     */
    fun ownerOf(item: ReleaseItem, own: List<WorkFacts>): WorkFacts? {
        val series = TitleMatch.key(item.seriesName, item.seriesYear)
        return own.firstOrNull { item.seriesKey in it.catalogIds }
            ?: own.firstOrNull { TitleMatch.quality(series, it.title) != null }
    }

    /** Whether the catalogue's [listed] series is the release's, by id or by name and year. */
    private fun listed(listed: CatalogSeriesRef, item: ReleaseItem, series: TitleKey): Boolean =
        item.seriesKey == "metron:series:${listed.id}" ||
            TitleMatch.quality(TitleMatch.key(listed.name, listed.yearBegan), displayTitle(series)) != null

    /** A key back as a title the matcher reads: "absolute batman (2024)". */
    private fun displayTitle(key: TitleKey) = key.year?.let { "${key.name} ($it)" } ?: key.name

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
