package eu.kanade.tachiyomi.readingstats.comics

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.comics.PeriodChange
import tachiyomi.domain.readingstats.comics.UnitCounts
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import kotlin.time.Clock

@Immutable
data class LibrarySummary(
    val entries: Int,
    val statusCounts: Map<LibraryStatus, Int>,
    val backlog: UnitCounts,
    val week: PeriodChange,
    val month: PeriodChange,
    val stalled: List<EntryProgress>,
    val hideStalled: Boolean,
    val nowMs: Long,
)

/** The library's state, backlog and reminders, kept current as chapters are read or added. */
@Inject
class LibrarySummarySource(
    private val repository: ComicMetadataRepository,
    private val preferences: ReadingStatsPreferences,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(today: Flow<LocalDate>, firstDayOfWeek: DayOfWeek): Flow<LibrarySummary> =
        combine(today, repository.subscribeLibrary(), preferences.hideStalledReminders.changes(), ::Triple)
            .mapLatest { (today, entries, hideStalled) ->
                val zone = TimeZone.currentSystemDefault()
                val week = ReadingCalendar.weekOf(today, firstDayOfWeek)
                val monthStart = LocalDate(today.year, today.month, 1)
                val monthEnd = monthStart.plus(DatePeriod(months = 1)).minus(1, DateTimeUnit.DAY)
                suspend fun change(from: LocalDate, to: LocalDate): PeriodChange = ComicLibrary.change(
                    entries = entries,
                    fromMs = ReadingCalendar.startOf(from, zone),
                    toMs = ReadingCalendar.startOf(to.plus(1, DateTimeUnit.DAY), zone),
                    completed = repository.completedChapterIds(from, to),
                )
                val progress = entries.map(ComicLibrary::progress)
                val now = Clock.System.now().toEpochMilliseconds()
                LibrarySummary(
                    entries = entries.size,
                    statusCounts = progress.groupingBy { it.status }.eachCount(),
                    backlog = ComicLibrary.backlog(entries),
                    week = change(week.from, week.to),
                    month = change(monthStart, monthEnd),
                    stalled = ComicLibrary.stalled(progress, now),
                    hideStalled = hideStalled,
                    nowMs = now,
                )
            }
}
