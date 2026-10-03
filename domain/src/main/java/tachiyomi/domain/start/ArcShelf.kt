package tachiyomi.domain.start

import java.time.LocalDate

/** Why an arc is on the Início (docs/HOME_PLAN.md §13, H9). */
sealed interface ArcReason {
    /** The reader is in it: an issue read, some left. Until H10, in place of the arcs followed. */
    data object Started : ArcReason

    /** An issue of [title] the reader opened is in it, as the issue's metadata names the arc. */
    data class InYourReading(val mangaId: Long, val title: String) : ArcReason

    /** An issue of it that matters to the reader, for [reason], is out this week. */
    data class OutThisWeek(val reason: ReleaseReason) : ArcReason
}

/** An arc the Início may offer, why, and its issue out this week when there is one. */
data class ArcLead(val arcKey: String, val reason: ArcReason, val release: ReleaseItem? = null)

/** An arc on the Início: what it is, how much of it the reader has read, and why it is there. */
data class ArcPick(val arc: Arc, val progress: ArcProgress, val lead: ArcLead)

/** A kept arc the reader is in, and when they last read a work of it. */
data class StartedArc(val arcKey: String, val lastReadAt: Long)

/** An arc an issue opened at [readAt] is in. */
data class ReadArc(val arcKey: String, val mangaId: Long, val title: String, val readAt: Long)

/** An arc a release of this week that matters is in. */
data class ReleaseArc(val arcKey: String, val release: RelevantRelease)

/**
 * The Início's "Arcos e eventos" (docs/HOME_PLAN.md §13, H9): the arcs the reader is in, then the
 * arcs of what they read, the latest first, then the arcs of this week's releases that matter to
 * them. Each arc once, with the reason that put it highest; none the reader turned down, and none
 * read to the end.
 */
object ArcShelf {

    /** How many arcs the block shows. */
    const val SHOWN = 6

    fun leads(
        started: List<StartedArc>,
        reading: List<ReadArc>,
        releases: List<ReleaseArc>,
        feedback: List<Feedback>,
        nowMs: Long,
    ): List<ArcLead> {
        val turnedDown = turnedDown(feedback, nowMs)
        val hidden = Recommender.hiddenWorks(feedback, nowMs)
        // The most relevant release first, as `Releases.relevant` orders them.
        val outThisWeek = releases.groupBy { it.arcKey }.mapValues { it.value.first().release.item }
        val leads = started
            .sortedWith(compareByDescending<StartedArc> { it.lastReadAt }.thenBy { it.arcKey })
            .map { ArcLead(it.arcKey, ArcReason.Started) } +
            reading
                .filter { it.mangaId !in hidden }
                .sortedWith(compareByDescending<ReadArc> { it.readAt }.thenBy { it.arcKey })
                .map { ArcLead(it.arcKey, ArcReason.InYourReading(it.mangaId, it.title)) } +
            releases.map { ArcLead(it.arcKey, ArcReason.OutThisWeek(it.release.reason)) }
        return leads
            .filter { it.arcKey !in turnedDown }
            .distinctBy { it.arcKey }
            .map { it.copy(release = outThisWeek[it.arcKey]) }
    }

    /** The series of [issues] the reader has, in the library or read, as the works they are read in. */
    fun owners(issues: List<ArcIssue>, own: List<WorkFacts>): Map<String, WorkFacts> =
        issues.distinctBy { it.seriesKey }
            .mapNotNull { issue -> Releases.ownerOf(issue.asRelease(), own)?.let { issue.seriesKey to it } }
            .toMap()

    /**
     * How much of the arc is read in the works the reader has; a series they have not is not read.
     * Nothing is looked for in the sources here: that is the arc's page (H8).
     */
    fun progress(
        issues: List<ArcIssue>,
        owners: Map<String, WorkFacts>,
        chapters: Map<Long, List<ChapterRef>>,
        today: LocalDate,
    ): ArcProgress {
        val series = seriesOf(issues, owners)
        return Arcs.progress(Arcs.view(issues, series, chapters, today), series)
    }

    /**
     * [arcKey] as an arc the reader is in, last read when a work they read it in was; null when no
     * issue of it is read, or every one is.
     */
    fun started(
        arcKey: String,
        issues: List<ArcIssue>,
        owners: Map<String, WorkFacts>,
        chapters: Map<Long, List<ChapterRef>>,
        today: LocalDate,
    ): StartedArc? {
        val series = seriesOf(issues, owners)
        val views = Arcs.view(issues, series, chapters, today)
        val progress = Arcs.progress(views, series)
        if (progress.read == 0 || progress.left == 0) return null
        val readIn = views.filter { it.state == ArcIssueState.READ }.map { it.issue.seriesKey }.toSet()
        val lastRead = readIn.mapNotNull { owners[it]?.lastReadAt }.maxOrNull() ?: 0L
        return StartedArc(arcKey, lastRead)
    }

    /** Whether an arc with this [progress] is worth offering: it has issues, and some are left to read. */
    fun worthOffering(progress: ArcProgress) = progress.total > 0 && progress.left > 0

    /** The arcs the reader said they are not interested in. */
    fun turnedDown(feedback: List<Feedback>, nowMs: Long): Set<String> =
        feedback.filter { it.subject == FeedbackSubject.ARC && it.activeAt(nowMs) }.map { it.key }.toSet()

    private fun seriesOf(issues: List<ArcIssue>, owners: Map<String, WorkFacts>): Map<String, ArcSeries> =
        issues.map { it.seriesKey }.distinct().associateWith { key ->
            owners[key]?.let { ArcSeries.Found(it.mangaId, sourceName = "", inLibrary = it.inLibrary) }
                ?: ArcSeries.NotFound
        }
}

/** The issue as a release, for what matches a release to the reader's works. */
fun ArcIssue.asRelease() = ReleaseItem(
    key = key,
    seriesKey = seriesKey,
    seriesName = seriesName,
    seriesYear = seriesYear,
    number = number,
    storeDate = storeDate,
    coverUrl = coverUrl,
)
