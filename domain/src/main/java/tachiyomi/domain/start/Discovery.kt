package tachiyomi.domain.start

import kotlinx.serialization.Serializable
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.comics.MetadataOrigin

/** A name the catalogue knows, as its search lists it. */
data class CatalogName(val id: Long, val name: String)

/** One issue of the catalogue, as an entity's issue list gives it: what matters is its series. */
data class CatalogIssue(val seriesId: Long, val seriesName: String, val volume: Int?, val yearBegan: Int?)

/** A series of the catalogue, and how many of the issues asked about are in it. */
@Serializable
data class CatalogSeriesRef(
    val id: Long,
    val name: String,
    val volume: Int? = null,
    val yearBegan: Int? = null,
    val issues: Int = 0,
)

/** What the catalogue says of a whole series, when asked: its length, whether it ended, its format. */
@Serializable
data class CatalogSeriesDetail(
    val id: Long,
    val issueCount: Int? = null,
    val finished: Boolean = false,
    val seriesType: String? = null,
)

/** An entity worth asking the catalogue about. [name] as the reader's works write it. */
data class EntityAsk(val key: EntityKey, val name: String, val affinity: Double)

/** What the catalogue answered for one entity. */
data class CatalogAnswer(val key: EntityKey, val series: List<CatalogSeriesRef>)

/** A catalogue series worth looking for in the sources: the entities that led to it, and why. */
data class CatalogPick(
    val series: CatalogSeriesRef,
    val keys: List<EntityKey>,
    val score: Double,
    val reasons: List<EntityReason>,
)

/**
 * A catalogue series found in one of the reader's sources (H4): an entry outside the library that
 * the Início opens, "em [sourceName]". [total] and [finished] as the catalogue knows them.
 */
data class OutsideWork(
    val mangaId: Long,
    val title: String,
    val cover: MangaCover?,
    val sourceName: String,
    val pick: CatalogPick,
    val total: Int?,
    val finished: Boolean,
)

/**
 * Discovery beyond the library (docs/HOME_PLAN.md §7.5, H5): which of the reader's writers,
 * artists, characters and teams to ask the catalogue about, and which of its series to look for.
 *
 * Only entities with something actually read and a strong affinity are asked, never one the reader
 * turned down. A series already in the library or read, by its name and year, is not recommended
 * again; nor is a collected edition of issues the catalogue lists anyway. No entity takes more than
 * [PER_ENTITY] of the searches, so one prolific writer does not fill the Início alone.
 */
object Discovery {

    /** How many entities are asked about at a time. */
    const val ENTITIES_ASKED = 4

    /** An entity's affinity from which it counts as liked enough to look beyond the library. */
    const val MIN_AFFINITY = 1.5

    /** How many series of one entity may be looked for in the sources. */
    const val PER_ENTITY = 4

    /** The kinds the catalogue can list issues by. */
    val ASKED_KINDS = setOf(EntityKind.WRITER, EntityKind.ARTIST, EntityKind.CHARACTER, EntityKind.TEAM)

    /** How sure a value the catalogue gives is. */
    val CATALOG_ORIGIN = MetadataOrigin.METRON

    /** Formats that collect issues the catalogue also lists one by one. */
    private val COLLECTED = listOf("trade paperback", "hardcover", "hard cover", "omnibus")

    fun entitiesToAsk(model: AffinityModel, feedback: List<Feedback>, nowMs: Long): List<EntityAsk> {
        val unwanted = Recommender.unwantedNames(feedback, nowMs)
        return model.contributions.keys
            .filter { it.kind in ASKED_KINDS && it !in unwanted }
            .mapNotNull { key ->
                val read = model.readWorks(key).ifEmpty { return@mapNotNull null }
                EntityAsk(key, read.first().displayName, model.affinity(key))
            }
            .filter { it.affinity >= MIN_AFFINITY }
            .sortedWith(compareByDescending<EntityAsk> { it.affinity }.thenBy { it.key.name })
            .take(ENTITIES_ASKED)
    }

    /**
     * The catalogue's id for [name] among what its search found: the one named exactly so, once
     * case, accents and spacing are set aside; null when none is, or two different ones are.
     */
    fun exactId(results: List<CatalogName>, name: String): Long? {
        val wanted = TitleMatch.key(name).name
        val exact = results.filter { TitleMatch.key(it.name).name == wanted }.map { it.id }.distinct()
        return exact.singleOrNull()
    }

    /** An entity's issues as the series they belong to, the ones with most of its issues first. */
    fun seriesOf(issues: List<CatalogIssue>): List<CatalogSeriesRef> =
        issues.groupBy { it.seriesId }
            .map { (id, inSeries) ->
                val first = inSeries.first()
                CatalogSeriesRef(id, first.seriesName, first.volume, first.yearBegan, inSeries.size)
            }
            .sortedWith(compareByDescending<CatalogSeriesRef> { it.issues }.thenBy { it.name })

    /** Whether [detail] is a collection of issues rather than a series of its own. */
    fun isCollected(detail: CatalogSeriesDetail): Boolean {
        val type = detail.seriesType?.lowercase() ?: return false
        return COLLECTED.any { it in type }
    }

    /**
     * The series worth looking for, the strongest first: each scored by the affinity of every asked
     * entity it has, with those entities' reasons; none the reader owns or read, none without a
     * reason, and at most [PER_ENTITY] led by the same entity.
     */
    fun picks(answers: List<CatalogAnswer>, model: AffinityModel, works: List<WorkFacts>): List<CatalogPick> {
        val own = works.filter { it.inLibrary || it.chaptersRead > 0 || it.sessions > 0 }
        val keysBySeries = LinkedHashMap<Long, Pair<CatalogSeriesRef, MutableSet<EntityKey>>>()
        answers.forEach { answer ->
            answer.series.forEach { series ->
                keysBySeries.getOrPut(series.id) { series to mutableSetOf() }.second += answer.key
            }
        }
        val ranked = keysBySeries.values
            .filterNot { (series, _) -> owned(series, own) }
            .mapNotNull { (series, keys) ->
                val reasons = keys.mapNotNull { Recommender.reasonFor(model, it, CATALOG_ORIGIN) }
                    .sortedByDescending { it.score }
                if (reasons.isEmpty()) return@mapNotNull null
                CatalogPick(series, keys.sortedBy { it.name }, reasons.sumOf { it.score }, reasons)
            }
            .sortedWith(
                compareByDescending<CatalogPick> { it.score }
                    .thenByDescending { it.series.issues }
                    .thenBy { it.series.name },
            )
        val led = HashMap<EntityKey, Int>()
        return ranked.filter { pick ->
            val leader = EntityKey(pick.reasons.first().kind, EntityKey.normalize(pick.reasons.first().name))
            val count = led.getOrDefault(leader, 0)
            if (count >= PER_ENTITY) return@filter false
            led[leader] = count + 1
            true
        }
    }

    /** Whether a work the reader has or read is this series, by name and year. */
    private fun owned(series: CatalogSeriesRef, own: List<WorkFacts>): Boolean {
        val key = TitleMatch.key(series.name, series.yearBegan)
        return own.any { TitleMatch.quality(key, it.title) != null }
    }
}

/** The catalogue's answers kept on the device, so the Início asks again only when they expire. */
interface CatalogCache {
    /** What was kept under [key], unless it has expired by [nowMs]. */
    suspend fun get(key: String, nowMs: Long): String?

    suspend fun put(key: String, body: String, nowMs: Long, keepMs: Long)
}
