package tachiyomi.domain.readingstats.tracker

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.model.ChapterPages
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.ChapterRef
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.LegacyProgress
import tachiyomi.domain.readingstats.model.PageBits
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.readingstats.model.SessionWrite
import java.util.BitSet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class ReadingTrackerConfig(
    /** Without a touch, key or page for this long, the reader is taken to be set down. */
    val idleTimeout: Duration = 2.minutes,
    /** How much of the silence before [idleTimeout] still counts as reading the page. */
    val idleGrace: Duration = 1.minutes,
    /** Coming back to the same chapter within this long continues the same session. */
    val resumeWindow: Duration = 10.minutes,
    /** How often an open session's progress is saved while it is being read. */
    val flushInterval: Duration = 15.seconds,
) {
    init {
        require(idleGrace <= idleTimeout) { "idleGrace must not exceed idleTimeout" }
    }
}

/**
 * Measures reading from what the reader reports, with no clock of its own: every event carries the
 * instant it happened, so the rules below can be checked to the millisecond.
 *
 * Time is active only while all of these hold: a session is open, the reader is in the foreground,
 * its window has focus, and the page on screen has loaded. Each touch, key or page turn is a mark;
 * the stretch between two marks counts in full if it is shorter than
 * [ReadingTrackerConfig.idleTimeout], and only its first [ReadingTrackerConfig.idleGrace] if it is
 * longer. Time is split across calendar days as it is counted, in the time zone of that moment.
 *
 * The tracker never writes: it queues [SessionWrite]s, collected with [drainWrites].
 */
class ReadingSessionTracker(
    private val config: ReadingTrackerConfig,
    private val timeZone: () -> TimeZone,
    private val newUuid: () -> String,
) {
    private var foreground = false

    // Assumed until told otherwise: a missed focus event must not stop all counting.
    private var focused = true
    private var pageReady = false
    private var backgroundSince: Long? = null

    private var chapter: ChapterState? = null
    private var session: Session? = null
    private var shownPage = -1

    /** The last mark while reading; null when not reading, or silent past the idle timeout. */
    private var mark: Long? = null

    /** The instant up to which time has been counted. */
    private var countedUntil = 0L

    private val writes = ArrayList<SessionWrite>()

    /** The chapter of the open session, if any. */
    val chapterId: Long? get() = session?.ref?.chapterId

    private val reading: Boolean get() = session != null && foreground && focused && pageReady

    /**
     * Opens a session of [ref], closing any open one. [progress] is what earlier sessions left;
     * without it, [legacy] says what Mihon knew. [resume] continues a session saved earlier.
     */
    fun open(
        at: Instant,
        ref: ChapterRef,
        totalPages: Int,
        progress: ChapterProgress?,
        legacy: LegacyProgress?,
        resume: SessionSnapshot? = null,
    ) {
        val now = at.toEpochMilliseconds()
        close(at)
        chapter = ChapterState.of(ref, totalPages, progress, legacy)
        val next = resume?.let { Session.resumed(it, ref, totalPages) }
            ?: Session(newUuid(), ref, now, totalPages, timeZone().id)
        shownPage = -1
        changeReading(now) { session = next }
    }

    /** The page at [index] is on screen and loaded. */
    fun pageShown(at: Instant, index: Int) {
        val current = session ?: return
        val chapter = chapter ?: return
        if (index < 0) return
        val now = at.toEpochMilliseconds()
        markActivity(now)
        val today = dateOf(now)
        if (!current.visited[index]) {
            current.visited.set(index)
            current.pagesVisited++
            val day = current.day(today)
            day.pagesVisited++
            if (chapter.seen[index]) {
                current.pagesReread++
            } else {
                chapter.seen.set(index)
                current.pagesNew++
                day.pagesNew++
            }
        }
        if (current.lastPage in 0..<index) current.pageAdvances++
        if (current.firstPage < 0) current.firstPage = index
        current.lastPage = index
        shownPage = index
        chapter.maxPage = maxOf(chapter.maxPage, index)
        if (index == current.totalPages - 1 && current.completedAt == null) {
            current.completedAt = now
            current.completedOn = today
            if (chapter.completionOrigin == null) {
                chapter.completedAt = now
                chapter.completedOn = today
                chapter.completionOrigin = ReadingOrigin.READER
                current.firstCompletion = true
            }
        }
        current.endedAt = maxOf(current.endedAt, now)
        write(now)
    }

    /** A touch or a key. */
    fun interaction(at: Instant) {
        val now = at.toEpochMilliseconds()
        markActivity(now)
        flushIfDue(now)
    }

    fun setForeground(at: Instant, inForeground: Boolean) {
        if (inForeground == foreground) return
        val now = at.toEpochMilliseconds()
        if (inForeground) {
            val since = backgroundSince
            backgroundSince = null
            val rollOver = session != null && since != null && now - since > config.resumeWindow.inWholeMilliseconds
            if (rollOver) startAgain(now)
            changeReading(now) { foreground = true }
            if (rollOver && pageReady && shownPage >= 0) pageShown(at, shownPage)
        } else {
            changeReading(now) { foreground = false }
            backgroundSince = now
        }
    }

    fun setFocused(at: Instant, hasFocus: Boolean) {
        changeReading(at.toEpochMilliseconds()) { focused = hasFocus }
    }

    fun setPageReady(at: Instant, ready: Boolean) {
        changeReading(at.toEpochMilliseconds()) { pageReady = ready }
    }

    /** Called regularly while the reader is open: notices silence and saves progress. */
    fun tick(at: Instant) {
        val now = at.toEpochMilliseconds()
        val lastMark = mark
        if (reading && lastMark != null) {
            val grace = lastMark + config.idleGrace.inWholeMilliseconds
            if (now - lastMark > config.idleTimeout.inWholeMilliseconds) {
                count(until = grace)
                mark = null
            } else {
                count(until = minOf(now, grace))
            }
        }
        flushIfDue(now)
    }

    /** Ends the open session, if any. */
    fun close(at: Instant, state: SessionState = SessionState.CLOSED) {
        val current = session ?: return
        if (reading) closeGap(at.toEpochMilliseconds())
        mark = null
        finish(current, state)
        chapter = null
    }

    /** The writes queued since the last call, oldest first. */
    fun drainWrites(): List<SessionWrite> {
        if (writes.isEmpty()) return emptyList()
        return writes.toList().also { writes.clear() }
    }

    private inline fun changeReading(now: Long, change: () -> Unit) {
        val before = reading
        change()
        val after = reading
        if (before && !after) {
            closeGap(now)
            mark = null
            session?.let { if (it.dirty) write(now) }
        } else if (!before && after) {
            mark = now
            countedUntil = maxOf(countedUntil, now)
        }
    }

    private fun markActivity(now: Long) {
        if (!reading) return
        closeGap(now)
        mark = now
        countedUntil = maxOf(countedUntil, now)
        session?.let { it.endedAt = maxOf(it.endedAt, now) }
    }

    /** Counts the stretch since the last mark, now that something has ended it. */
    private fun closeGap(now: Long) {
        val lastMark = mark ?: return
        val silence = now - lastMark
        count(
            until = if (silence <= config.idleTimeout.inWholeMilliseconds) {
                now
            } else {
                lastMark + config.idleGrace.inWholeMilliseconds
            },
        )
    }

    private fun count(until: Long) {
        val current = session ?: return
        if (until <= countedUntil) return
        ReadingCalendar.splitByDay(countedUntil, until, timeZone()).forEach { (date, ms) ->
            current.activeMs += ms
            current.day(date).activeMs += ms
        }
        countedUntil = until
        current.endedAt = maxOf(current.endedAt, until)
        current.dirty = true
    }

    /** Closes the open session and opens a fresh one on the same chapter. */
    private fun startAgain(now: Long) {
        val current = session ?: return
        val chapter = chapter ?: return
        mark = null
        finish(current, SessionState.CLOSED)
        session = Session(newUuid(), chapter.ref, now, chapter.totalPages, timeZone().id)
    }

    private fun finish(current: Session, state: SessionState) {
        writes += SessionWrite(current.snapshot(state), chapter!!.progress())
        session = null
    }

    private fun flushIfDue(now: Long) {
        val current = session ?: return
        if (current.dirty && now - current.lastWriteAt >= config.flushInterval.inWholeMilliseconds) write(now)
    }

    private fun write(now: Long) {
        val current = session ?: return
        writes += SessionWrite(current.snapshot(SessionState.OPEN), chapter!!.progress())
        current.dirty = false
        current.lastWriteAt = now
    }

    private fun dateOf(epochMs: Long): LocalDate = ReadingCalendar.dateOf(epochMs, timeZone())

    private class ChapterState(
        val ref: ChapterRef,
        val totalPages: Int,
        val seen: BitSet,
        var maxPage: Int,
        var completedAt: Long?,
        var completedOn: LocalDate?,
        var completionOrigin: ReadingOrigin?,
    ) {
        fun progress() = ChapterProgress(
            chapterId = ref.chapterId,
            pagesSeen = PageBits.encode(seen),
            maxPageReached = maxPage,
            totalPages = totalPages,
            completedAt = completedAt,
            completedOn = completedOn,
            completionOrigin = completionOrigin,
        )

        companion object {
            fun of(
                ref: ChapterRef,
                totalPages: Int,
                progress: ChapterProgress?,
                legacy: LegacyProgress?,
            ): ChapterState {
                val seen = ChapterPages.seenBefore(progress, legacy.takeIf { progress == null }, totalPages)
                return ChapterState(
                    ref = ref,
                    totalPages = totalPages,
                    seen = seen,
                    maxPage = maxOf(progress?.maxPageReached ?: -1, seen.length() - 1),
                    completedAt = progress?.completedAt,
                    completedOn = progress?.completedOn,
                    completionOrigin = progress?.completionOrigin
                        ?: ReadingOrigin.IMPORTED.takeIf { progress == null && legacy?.read == true },
                )
            }
        }
    }

    private class Session(
        val uuid: String,
        val ref: ChapterRef,
        val startedAt: Long,
        val totalPages: Int,
        val zoneId: String,
    ) {
        var endedAt = startedAt
        var activeMs = 0L
        var firstPage = -1
        var lastPage = -1
        val visited = BitSet()
        var pagesVisited = 0
        var pagesNew = 0
        var pagesReread = 0
        var pageAdvances = 0
        var completedAt: Long? = null
        var completedOn: LocalDate? = null
        var firstCompletion = false
        val days = LinkedHashMap<LocalDate, Day>()
        var dirty = false
        var lastWriteAt = startedAt

        fun day(date: LocalDate): Day = days.getOrPut(date) { Day() }

        fun snapshot(state: SessionState) = SessionSnapshot(
            uuid = uuid,
            mangaId = ref.mangaId,
            chapterId = ref.chapterId,
            key = ref.key,
            startedAt = startedAt,
            endedAt = endedAt,
            activeMs = activeMs,
            firstPage = firstPage,
            lastPage = lastPage,
            pagesVisited = pagesVisited,
            pagesNew = pagesNew,
            pagesReread = pagesReread,
            pageAdvances = pageAdvances,
            totalPages = totalPages,
            pagesBits = PageBits.encode(visited),
            completedAt = completedAt,
            completedOn = completedOn,
            firstCompletion = firstCompletion,
            origin = ReadingOrigin.READER,
            state = state,
            zoneId = zoneId,
            days = days.map { (date, day) -> DayTotals(date, day.activeMs, day.pagesNew, day.pagesVisited) },
        )

        companion object {
            fun resumed(saved: SessionSnapshot, ref: ChapterRef, totalPages: Int) =
                Session(saved.uuid, ref, saved.startedAt, totalPages, saved.zoneId).apply {
                    endedAt = saved.endedAt
                    activeMs = saved.activeMs
                    firstPage = saved.firstPage
                    lastPage = saved.lastPage
                    visited.or(PageBits.decode(saved.pagesBits))
                    pagesVisited = saved.pagesVisited
                    pagesNew = saved.pagesNew
                    pagesReread = saved.pagesReread
                    pageAdvances = saved.pageAdvances
                    completedAt = saved.completedAt
                    completedOn = saved.completedOn
                    firstCompletion = saved.firstCompletion
                    saved.days.forEach { days[it.date] = Day(it.activeMs, it.pagesNew, it.pagesVisited) }
                    lastWriteAt = saved.endedAt
                }
        }
    }

    private class Day(var activeMs: Long = 0, var pagesNew: Int = 0, var pagesVisited: Int = 0)
}
