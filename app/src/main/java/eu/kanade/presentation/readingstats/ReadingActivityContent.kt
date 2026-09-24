package eu.kanade.presentation.readingstats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.readingstats.ReadingStatsViewModel
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDate
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import tachiyomi.domain.readingstats.habits.ReadingHabits
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val DAYS_PER_WEEK = 7
private val CellMinHeight = 40.dp
private val LegendCell = 12.dp

/** Streaks, the month calendar and personal records. */
@Composable
fun ReadingActivity(
    activity: ReadingStatsViewModel.ActivityState,
    onClickDay: (LocalDate) -> Unit,
    onShowMonth: (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        val streaks = activity.streaks
        Text(
            text = stringResource(
                MR.strings.reading_stats_streak,
                pluralStringResource(MR.plurals.reading_stats_days, streaks.current, streaks.current),
                pluralStringResource(MR.plurals.reading_stats_days, streaks.record, streaks.record),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        activity.frequency?.let {
            Footnote(
                pluralStringResource(
                    MR.plurals.reading_stats_frequency,
                    it.weeks,
                    String.format(Locale.getDefault(), "%.1f", it.daysPerWeek),
                    it.weeks,
                ),
            )
        }
        MonthCalendar(activity, onClickDay, onShowMonth)
        Records(activity, context)
    }
}

@Composable
private fun MonthCalendar(
    activity: ReadingStatsViewModel.ActivityState,
    onClickDay: (LocalDate) -> Unit,
    onShowMonth: (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val month = activity.month
    val nextMonth = month.plus(1, DateTimeUnit.MONTH)
    val currentMonth = LocalDate(activity.today.year, activity.today.month, 1)
    val firstRecordedMonth = LocalDate(activity.recordingStartedOn.year, activity.recordingStartedOn.month, 1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onShowMonth(month.minus(1, DateTimeUnit.MONTH)) },
            enabled = month > firstRecordedMonth,
        ) {
            Icon(MaterialSymbols.Rounded.KeyboardArrowLeft, stringResource(MR.strings.reading_stats_previous_month))
        }
        Text(
            text = month.toJavaLocalDate().format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
                .replaceFirstChar { it.titlecase(Locale.getDefault()) },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onShowMonth(nextMonth) }, enabled = month < currentMonth) {
            Icon(MaterialSymbols.Rounded.KeyboardArrowRight, stringResource(MR.strings.reading_stats_next_month))
        }
    }
    // Weekday initials, starting on the locale's first day of the week.
    val weekStart = activity.firstDayOfWeek
    Row {
        repeat(DAYS_PER_WEEK) { offset ->
            val dayOfWeek = java.time.DayOfWeek.of((weekStart.isoDayNumber - 1 + offset) % DAYS_PER_WEEK + 1)
            Text(
                text = dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                modifier = Modifier.weight(1f),
            )
        }
    }
    val lead = (month.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + DAYS_PER_WEEK) % DAYS_PER_WEEK
    val length = (nextMonth.toEpochDays() - month.toEpochDays()).toInt()
    val cells = List(lead) { null } + List(length) { month.plus(it, DateTimeUnit.DAY) }
    cells.chunked(DAYS_PER_WEEK).forEach { week ->
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 1.dp)) {
            week.forEach { date ->
                Box(modifier = Modifier.weight(1f)) {
                    if (date != null) DayCell(date, activity, context, onClickDay)
                }
            }
            repeat(DAYS_PER_WEEK - week.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
    Legend()
}

@Composable
private fun DayCell(
    date: LocalDate,
    activity: ReadingStatsViewModel.ActivityState,
    context: android.content.Context,
    onClickDay: (LocalDate) -> Unit,
) {
    val day = activity.days[date]
    val future = date > activity.today
    val recorded = date >= activity.recordingStartedOn
    val level = if (recorded) ReadingHabits.intensity(day?.activeMs ?: 0) else 0
    val description = when {
        future -> readingDateText(date)
        !recorded -> stringResource(MR.strings.reading_stats_day_bar_not_recorded, readingDateText(date))
        else -> stringResource(
            MR.strings.reading_stats_day_bar,
            readingDateText(date),
            readingTimeText(context, day?.activeMs ?: 0),
            day?.pagesNew ?: 0,
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val fill = when {
        !recorded || future -> Color.Transparent
        level == 0 -> MaterialTheme.colorScheme.surfaceVariant
        else -> primary.copy(alpha = level / MAX_LEVEL)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellMinHeight)
            .aspectRatio(1f)
            .background(fill, RoundedCornerShape(6.dp))
            .then(
                if (date == activity.today) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !future, role = Role.Button) { onClickDay(date) }
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.day.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                future || !recorded -> MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
                level >= DARK_LEVEL -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun Legend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Footnote(stringResource(MR.strings.reading_stats_less))
        (0..MAX_LEVEL.toInt()).forEach { level ->
            Box(
                modifier = Modifier
                    .size(LegendCell)
                    .background(
                        if (level == 0) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = level / MAX_LEVEL)
                        },
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
        Footnote(stringResource(MR.strings.reading_stats_more))
    }
}

@Composable
private fun Records(activity: ReadingStatsViewModel.ActivityState, context: android.content.Context) {
    val records = activity.records
    val lines = buildList {
        records.longestDay?.let {
            add(
                stringResource(
                    MR.strings.reading_stats_record_day,
                    readingTimeText(context, it.value),
                    readingShortDateText(it.date, activity.today),
                ),
            )
        }
        records.mostPagesDay?.let {
            add(
                stringResource(
                    MR.strings.reading_stats_record_pages,
                    it.value,
                    readingShortDateText(it.date, activity.today),
                ),
            )
        }
        records.bestWeek?.let {
            add(
                stringResource(
                    MR.strings.reading_stats_record_week,
                    readingTimeText(context, it.activeMs),
                    periodText(it.week, activity.today),
                ),
            )
        }
        records.longestSession?.let {
            val chapter = it.chapterNumber.takeIf { number -> number >= 0 }?.let(::formatChapterNumber)
            add(
                stringResource(
                    MR.strings.reading_stats_record_session,
                    readingTimeText(context, it.activeMs),
                    listOfNotNull(it.title, chapter?.let { number -> "#$number" }).joinToString(" "),
                ),
            )
        }
    }
    if (lines.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        modifier = Modifier.padding(top = MaterialTheme.padding.small),
    ) {
        Text(text = stringResource(MR.strings.reading_stats_records), style = MaterialTheme.typography.labelLarge)
        lines.forEach { Footnote(it) }
    }
}

private fun periodText(period: ReadingPeriod, today: LocalDate): String =
    "${readingShortDateText(period.from, today)} – ${readingShortDateText(period.to, today)}"

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
    )
}

private const val MAX_LEVEL = 4f

/** From this level on, the day's number is drawn light on the dark fill. */
private const val DARK_LEVEL = 3
