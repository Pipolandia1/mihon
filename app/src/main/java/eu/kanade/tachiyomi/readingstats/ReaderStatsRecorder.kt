package eu.kanade.tachiyomi.readingstats

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterRef
import tachiyomi.domain.readingstats.model.LegacyProgress
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import tachiyomi.domain.readingstats.tracker.ReadingSessionTracker
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Feeds the reader's life into a [ReadingSessionTracker] and saves what it measures. One per reader.
 *
 * Every call returns at once: events are stamped with the moment they happened and handled one at a
 * time off the main thread, in the order they arrived. Nothing is recorded in incognito mode or
 * while recording is turned off.
 */
@Inject
class ReaderStatsRecorder(
    private val repository: ReadingStatsRepository,
    private val preferences: ReadingStatsPreferences,
) {
    private val clock: Clock = Clock.System
    private val tracker = ReadingSessionTracker(
        config = ReadingStatsConfig.TRACKER,
        timeZone = TimeZone::currentSystemDefault,
        newUuid = { UUID.randomUUID().toString() },
    )

    // Not the view model's scope: the last session must still be saved after the reader is gone.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private var pageStatus: Job? = null
    private var ticker: Job? = null

    // Touched only by the event loop.
    private var selected: Event.PageSelected? = null

    /** What Mihon knew of each chapter the first time one of its pages was selected. */
    private val legacy = HashMap<Long, LegacyProgress>()

    init {
        scope.launch {
            try {
                repository.closeStaleSessions(
                    endedBefore = clock.now().toEpochMilliseconds() -
                        ReadingStatsConfig.TRACKER.resumeWindow.inWholeMilliseconds,
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
            for (event in events) {
                try {
                    handle(event)
                    save()
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "ReadingStats could not handle $event" }
                }
            }
            scope.cancel()
        }
    }

    /**
     * A page was selected. Must be called before the reader updates the chapter's read state, so
     * what Mihon knew before this reading is captured.
     */
    fun onPageSelected(manga: Manga, page: ReaderPage, incognito: Boolean) {
        val chapter = page.chapter
        val pages = chapter.pages ?: return
        val dbChapter = chapter.chapter
        val chapterId = dbChapter.id ?: return
        val event = Event.PageSelected(
            at = clock.now(),
            ref = ChapterRef(
                mangaId = manga.id,
                chapterId = chapterId,
                key = ChapterKey(
                    sourceId = manga.source,
                    mangaUrl = manga.url,
                    chapterUrl = dbChapter.url,
                    mangaTitle = manga.title,
                    chapterName = dbChapter.name,
                    chapterNumber = dbChapter.chapter_number.toDouble(),
                ),
            ),
            index = page.index,
            totalPages = pages.size,
            legacy = LegacyProgress(read = dbChapter.read, lastPageRead = dbChapter.last_page_read),
            incognito = incognito,
        )
        events.trySend(event)
        pageStatus?.cancel()
        pageStatus = scope.launch {
            page.statusFlow
                .map { it == Page.State.Ready }
                .distinctUntilChanged()
                .collect { ready -> events.trySend(Event.PageReady(clock.now(), chapterId, page.index, ready)) }
        }
    }

    /** A touch, key or scroll anywhere in the reader. */
    fun onInteraction() {
        events.trySend(Event.Interaction(clock.now()))
    }

    fun onForeground(inForeground: Boolean) {
        events.trySend(Event.Foreground(clock.now(), inForeground))
        ticker?.cancel()
        if (inForeground) {
            ticker = scope.launch {
                while (isActive) {
                    delay(ReadingStatsConfig.TICK_INTERVAL)
                    events.trySend(Event.Tick(clock.now()))
                }
            }
        }
    }

    fun onFocus(hasFocus: Boolean) {
        events.trySend(Event.Focus(clock.now(), hasFocus))
    }

    /** The reader is gone: the open session is closed and saved, then everything stops. */
    fun close() {
        ticker?.cancel()
        pageStatus?.cancel()
        events.trySend(Event.Close(clock.now()))
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.PageSelected -> {
                selected = event
                legacy.putIfAbsent(event.ref.chapterId, event.legacy)
                tracker.setPageReady(event.at, false)
            }
            is Event.PageReady -> {
                val page = selected ?: return
                if (event.chapterId != page.ref.chapterId || event.index != page.index) return
                if (!event.ready) {
                    tracker.setPageReady(event.at, false)
                    return
                }
                if (page.incognito || !preferences.recordingEnabled.get()) {
                    tracker.close(event.at)
                    return
                }
                if (tracker.chapterId != page.ref.chapterId) open(page, event.at)
                tracker.setPageReady(event.at, true)
                tracker.pageShown(event.at, page.index)
            }
            is Event.Interaction -> tracker.interaction(event.at)
            is Event.Foreground -> tracker.setForeground(event.at, event.inForeground)
            is Event.Focus -> tracker.setFocused(event.at, event.hasFocus)
            is Event.Tick -> tracker.tick(event.at)
            is Event.Close -> {
                tracker.close(event.at)
                save()
                events.close()
            }
        }
    }

    private suspend fun open(page: Event.PageSelected, at: Instant) {
        // The previous chapter's session is saved first, so it is not taken for one to resume.
        tracker.close(at)
        save()
        val chapterId = page.ref.chapterId
        val resume = repository.getResumableSession(
            chapterId = chapterId,
            endedAfter = at.toEpochMilliseconds() - ReadingStatsConfig.TRACKER.resumeWindow.inWholeMilliseconds,
        )
        tracker.open(
            at = at,
            ref = page.ref,
            totalPages = page.totalPages,
            progress = repository.getChapterProgress(chapterId),
            legacy = legacy[chapterId] ?: page.legacy,
            resume = resume,
        )
    }

    private suspend fun save() {
        tracker.drainWrites().forEach { write ->
            repository.saveSession(write)
            val session = write.session
            logcat {
                "ReadingStats session ${session.uuid.take(8)} chapter=${session.chapterId} " +
                    "state=${session.state} active=${session.activeMs}ms pages=${session.pagesVisited} " +
                    "new=${session.pagesNew} reread=${session.pagesReread} advances=${session.pageAdvances} " +
                    "completed=${session.completedAt != null} first=${session.firstCompletion} " +
                    "days=${session.days.joinToString { "${it.date}:${it.activeMs}ms/${it.pagesNew}p" }}"
            }
        }
    }

    private sealed interface Event {
        data class PageSelected(
            val at: Instant,
            val ref: ChapterRef,
            val index: Int,
            val totalPages: Int,
            val legacy: LegacyProgress,
            val incognito: Boolean,
        ) : Event

        data class PageReady(val at: Instant, val chapterId: Long, val index: Int, val ready: Boolean) : Event
        data class Interaction(val at: Instant) : Event
        data class Foreground(val at: Instant, val inForeground: Boolean) : Event
        data class Focus(val at: Instant, val hasFocus: Boolean) : Event
        data class Tick(val at: Instant) : Event
        data class Close(val at: Instant) : Event
    }
}
