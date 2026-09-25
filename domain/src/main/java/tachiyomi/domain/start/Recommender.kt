package tachiyomi.domain.start

import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import kotlin.random.Random

/** Why a work is recommended: a sentence the Início writes with these numbers. */
sealed interface Reason {
    val score: Double
}

/**
 * "You finished 3 comics by Tom King": the candidate shares [name] with works that were read.
 * [candidateOrigin] is how sure the candidate's own value is.
 */
data class EntityReason(
    val kind: EntityKind,
    val name: String,
    val worksRead: Int,
    val worksCompleted: Int,
    val issuesRead: Int,
    /** The works behind it, the strongest first. */
    val fromTitles: List<String>,
    val candidateOrigin: MetadataOrigin,
    override val score: Double,
) : Reason

/** "You tend to finish short series: 4 of 5 with up to 12 issues." */
data class FormatReason(val finished: Int, val started: Int, val maxIssues: Int, override val score: Double) : Reason

data class Recommendation(val progress: EntryProgress, val score: Double, val reasons: List<Reason>)

/**
 * Picks what to read from the library, and says why. Amendment H3 (docs/HOME_PLAN.md §5.2, §5.3).
 *
 * A candidate's score is the affinity of everything it shares with what was read — its own reading
 * left out, so a work never recommends itself — weighted by how sure its own values are, plus the
 * fit of its length with the series this reader tends to finish. Only what can be said is counted
 * as a reason: a candidate with no reason is not recommended, whatever its score.
 */
object Recommender {

    const val MIN_SCORE = 0.3
    const val REASONS_KEPT = 5

    /** A short, closed series: the format the habit is measured on. */
    const val SHORT_SERIES_MAX = 12
    const val FORMAT_MIN_SERIES = 3
    const val FORMAT_MIN_SHARE = 0.6
    const val FORMAT_BONUS = 1.0

    fun readNow(
        candidates: List<EntryProgress>,
        works: List<WorkFacts>,
        feedback: List<Feedback>,
        exclude: Set<Long>,
        nowMs: Long,
        daySeed: Long,
    ): Recommendation? = rank(candidates, works, feedback, exclude, nowMs, daySeed).firstOrNull()

    fun rank(
        candidates: List<EntryProgress>,
        works: List<WorkFacts>,
        feedback: List<Feedback>,
        exclude: Set<Long>,
        nowMs: Long,
        daySeed: Long,
    ): List<Recommendation> {
        val model = Affinity.model(works, nowMs)
        val facts = works.associateBy { it.mangaId }
        val hidden = hiddenWorks(feedback, nowMs)
        val unwanted = unwantedNames(feedback, nowMs)
        val format = formatHabit(works, nowMs)
        return candidates
            .filter { it.status == LibraryStatus.NOT_STARTED || it.status == LibraryStatus.IN_PROGRESS }
            .filter { it.next != null && it.entry.mangaId !in exclude && it.entry.mangaId !in hidden }
            .mapNotNull { candidate ->
                val work = facts[candidate.entry.mangaId] ?: return@mapNotNull null
                val credits = Affinity.creditsOf(work)
                if (credits.keys.any { it in unwanted }) return@mapNotNull null
                score(candidate, work, credits, model, format)
            }
            .filter { it.score >= MIN_SCORE && it.reasons.isNotEmpty() }
            .sortedWith(
                compareByDescending<Recommendation> { it.score }
                    .thenBy { Random(daySeed xor it.progress.entry.mangaId).nextLong() },
            )
    }

    private fun score(
        candidate: EntryProgress,
        work: WorkFacts,
        credits: Map<EntityKey, Credit>,
        model: AffinityModel,
        format: FormatReason?,
    ): Recommendation {
        val id = candidate.entry.mangaId
        var score = 0.0
        val reasons = mutableListOf<Reason>()
        credits.forEach { (key, credit) ->
            val value = model.affinity(key, excluding = id) * Affinity.originWeight(credit.origin)
            score += value
            val read = model.readWorks(key, excluding = id)
            // Counted either way; said only when something was actually read.
            if (value > 0.0 && read.isNotEmpty()) {
                reasons += EntityReason(
                    kind = key.kind,
                    name = read.first().displayName,
                    worksRead = read.size,
                    worksCompleted = read.count { it.engagement == Engagement.COMPLETED },
                    issuesRead = read.sumOf { it.work.chaptersRead },
                    fromTitles = read.map { it.work.title },
                    candidateOrigin = credit.origin,
                    score = value,
                )
            }
        }
        val total = work.total
        if (format != null && total != null && total <= SHORT_SERIES_MAX && work.finished) {
            score += format.score
            reasons += format
        }
        return Recommendation(candidate, score, reasons.sortedByDescending { it.score }.take(REASONS_KEPT))
    }

    /**
     * Whether this reader finishes short, closed series: at least [FORMAT_MIN_SERIES] started and
     * [FORMAT_MIN_SHARE] of them finished. Measured, or nothing.
     */
    fun formatHabit(works: List<WorkFacts>, nowMs: Long): FormatReason? {
        val short = works.filter { work ->
            val total = work.total
            work.finished && total != null && total <= SHORT_SERIES_MAX && work.chaptersRead > 0
        }
        if (short.size < FORMAT_MIN_SERIES) return null
        val finished = short.count { Affinity.engagement(it, nowMs) == Engagement.COMPLETED }
        if (finished < short.size * FORMAT_MIN_SHARE) return null
        return FormatReason(finished, short.size, SHORT_SERIES_MAX, FORMAT_BONUS)
    }

    /** Works the reader turned down, still in force. */
    fun hiddenWorks(feedback: List<Feedback>, nowMs: Long): Set<Long> =
        feedback.filter { it.subject == FeedbackSubject.WORK && it.activeAt(nowMs) }
            .mapNotNull { it.key.toLongOrNull() }
            .toSet()

    private fun unwantedNames(feedback: List<Feedback>, nowMs: Long): Set<EntityKey> =
        feedback.filter { it.subject != FeedbackSubject.WORK && it.activeAt(nowMs) }
            .flatMap { entry ->
                val kinds = if (entry.subject == FeedbackSubject.CREATOR) {
                    listOf(EntityKind.WRITER, EntityKind.ARTIST)
                } else {
                    listOf(EntityKind.CHARACTER, EntityKind.TEAM)
                }
                kinds.map { EntityKey(it, EntityKey.normalize(entry.key)) }
            }
            .toSet()
}
