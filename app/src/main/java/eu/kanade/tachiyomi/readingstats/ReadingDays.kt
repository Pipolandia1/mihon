package eu.kanade.tachiyomi.readingstats

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** The calendar the reading screens share: today, the first day of the week, when recording began. */
@Inject
class ReadingDays(
    private val repository: ReadingStatsRepository,
) {

    /** The locale's first day of the week. */
    val firstDayOfWeek: DayOfWeek = DayOfWeek(WeekFields.of(Locale.getDefault()).firstDayOfWeek.value)

    /** Today, again whenever the date changes while it is collected. */
    val today: Flow<LocalDate> = flow {
        while (true) {
            emit(ReadingCalendar.dateOf(Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault()))
            delay(1.minutes)
        }
    }.distinctUntilChanged()

    /** The day recording began, read again whenever [resets] emits. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recordingStartedOn(resets: Flow<Any> = flowOf(Unit)): Flow<LocalDate> = resets.mapLatest {
        ReadingCalendar.dateOf(repository.getRecordingStartedAt(), TimeZone.currentSystemDefault())
    }
}
