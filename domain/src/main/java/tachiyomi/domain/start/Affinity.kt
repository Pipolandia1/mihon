package tachiyomi.domain.start

import tachiyomi.domain.readingstats.comics.MetadataOrigin
import kotlin.math.pow

/** An entity by kind and normalized name, so "Tom King" and "tom king " are one. */
data class EntityKey(val kind: EntityKind, val name: String) {
    companion object {
        fun of(credit: Credit) = EntityKey(credit.kind, normalize(credit.name))

        fun normalize(name: String): String = name.trim().lowercase().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}

/** How a work was read: the signal it gives. */
enum class Engagement { COMPLETED, MANY_ISSUES, IN_PROGRESS, ABANDONED, OPENED_ONCE, ADDED_NEVER_READ, NONE }

/** What one work gave one entity. */
data class Contribution(val work: WorkFacts, val engagement: Engagement, val value: Double, val displayName: String)

/** What every entity is worth to this reader, from how the works it appears in were read. */
class AffinityModel(val contributions: Map<EntityKey, List<Contribution>>) {

    /** The entity's affinity from every work but [excluding], so a work never recommends itself. */
    fun affinity(key: EntityKey, excluding: Long? = null): Double =
        contributions[key].orEmpty().filter { it.work.mangaId != excluding }.sumOf { it.value }

    /** The works behind an entity that were actually read, the strongest first. */
    fun readWorks(key: EntityKey, excluding: Long? = null): List<Contribution> =
        contributions[key].orEmpty()
            .filter { it.work.mangaId != excluding && it.engagement in Affinity.READ }
            .sortedByDescending { it.value }
}

/**
 * Affinity: how much each writer, artist, character, team, publisher and genre is worth to this
 * reader. Amendment H3 (docs/HOME_PLAN.md §5.1).
 *
 * Each work's reading gives it a weight — finished, many issues, in progress, opened once, only
 * added — that fades by half every [HALF_LIFE_DAYS] days, and that weight is shared among what the
 * work is made of by role and by how sure the value is. Adding to the library is not liking:
 * [ADDED_NEVER_READ] is next to nothing. The numbers are starting points, calibrated on the
 * synthetic library and then on the reader's own.
 */
object Affinity {

    const val COMPLETED = 5.0
    const val MANY_ISSUES = 4.0
    const val MANY_ISSUES_MIN = 10
    const val IN_PROGRESS = 2.0
    const val OPENED_ONCE = 0.5
    const val OPENED_ONCE_MAX_MS = 5 * 60_000L
    const val ADDED_NEVER_READ = 0.1

    /** Started, left under a quarter read and untouched for two months: mildly against. */
    const val ABANDONED = -0.5
    const val ABANDONED_SHARE = 0.25
    const val ABANDONED_DAYS = 60

    const val HALF_LIFE_DAYS = 180

    /** The engagements that mean something of the work was read. */
    val READ = setOf(Engagement.COMPLETED, Engagement.MANY_ISSUES, Engagement.IN_PROGRESS, Engagement.ABANDONED)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Writing weighs most; the main character nearly as much; then art, team, publisher, genre. */
    fun roleWeight(kind: EntityKind): Double = when (kind) {
        EntityKind.WRITER -> 1.0
        EntityKind.CHARACTER -> 0.9
        EntityKind.ARTIST -> 0.7
        EntityKind.TEAM -> 0.6
        EntityKind.PUBLISHER, EntityKind.IMPRINT, EntityKind.UNIVERSE -> 0.3
        EntityKind.GENRE -> 0.2
    }

    /** Confirmed counts fully; a catalogue or ComicInfo nearly; the source half; a guess not at all. */
    fun originWeight(origin: MetadataOrigin): Double = when (origin) {
        MetadataOrigin.USER -> 1.0
        MetadataOrigin.METRON, MetadataOrigin.GCD, MetadataOrigin.COMIC_INFO -> 0.8
        MetadataOrigin.SOURCE -> 0.5
        MetadataOrigin.INFERRED -> 0.0
    }

    fun engagement(work: WorkFacts, nowMs: Long): Engagement {
        val total = work.total
        return when {
            total != null && total > 0 && work.chaptersRead >= total -> Engagement.COMPLETED
            work.chaptersRead >= MANY_ISSUES_MIN -> Engagement.MANY_ISSUES
            work.chaptersRead > 0 && abandoned(work, total, nowMs) -> Engagement.ABANDONED
            work.chaptersRead > 0 -> Engagement.IN_PROGRESS
            work.sessions > 0 && work.activeMs >= OPENED_ONCE_MAX_MS -> Engagement.IN_PROGRESS
            work.sessions > 0 -> Engagement.OPENED_ONCE
            work.inLibrary -> Engagement.ADDED_NEVER_READ
            else -> Engagement.NONE
        }
    }

    private fun abandoned(work: WorkFacts, total: Int?, nowMs: Long): Boolean {
        if (total == null || total <= 0) return false
        val lastRead = work.lastReadAt ?: return false
        return work.chaptersRead < total * ABANDONED_SHARE && nowMs - lastRead >= ABANDONED_DAYS * DAY_MS
    }

    fun base(engagement: Engagement): Double = when (engagement) {
        Engagement.COMPLETED -> COMPLETED
        Engagement.MANY_ISSUES -> MANY_ISSUES
        Engagement.IN_PROGRESS -> IN_PROGRESS
        Engagement.ABANDONED -> ABANDONED
        Engagement.OPENED_ONCE -> OPENED_ONCE
        Engagement.ADDED_NEVER_READ -> ADDED_NEVER_READ
        Engagement.NONE -> 0.0
    }

    /** Halves every [HALF_LIFE_DAYS]: reading from two years ago still counts, less. */
    fun recency(work: WorkFacts, nowMs: Long): Double {
        val at = work.lastReadAt ?: work.dateAdded
        val days = ((nowMs - at).coerceAtLeast(0)).toDouble() / DAY_MS
        return 0.5.pow(days / HALF_LIFE_DAYS)
    }

    /** Each work's credits, one per entity, the surest value kept. */
    fun creditsOf(work: WorkFacts): Map<EntityKey, Credit> =
        work.credits
            .filter { it.name.isNotBlank() && originWeight(it.origin) > 0.0 }
            .groupBy(EntityKey::of)
            .mapValues { (_, credits) -> credits.maxBy { originWeight(it.origin) } }

    fun model(works: List<WorkFacts>, nowMs: Long): AffinityModel {
        val contributions = HashMap<EntityKey, MutableList<Contribution>>()
        works.forEach { work ->
            val engagement = engagement(work, nowMs)
            val weight = base(engagement) * recency(work, nowMs)
            if (weight == 0.0) return@forEach
            creditsOf(work).forEach { (key, credit) ->
                val value = weight * roleWeight(key.kind) * originWeight(credit.origin)
                contributions.getOrPut(key) { mutableListOf() } +=
                    Contribution(work, engagement, value, credit.name.trim())
            }
        }
        return AffinityModel(contributions)
    }
}
