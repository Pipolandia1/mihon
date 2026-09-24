package eu.kanade.presentation.readingstats

import android.content.Context
import android.text.format.DateFormat
import eu.kanade.presentation.util.toDurationString
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Reading time the way the cards show it: whole minutes, or seconds below a minute. */
fun readingTimeText(context: Context, activeMs: Long): String {
    val duration = activeMs.milliseconds
    val shown = if (duration < 1.minutes) duration else duration.inWholeMinutes.minutes
    return shown.toDurationString(context, fallback = context.stringResource(MR.strings.minute_short, 0))
}

fun readingDateText(date: LocalDate): String =
    date.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Day and month, and the year only when it is not this one. */
fun readingShortDateText(date: LocalDate, today: LocalDate): String {
    val skeleton = if (date.year == today.year) "dMMM" else "dMMMy"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    return date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

fun readingDateLongText(date: LocalDate): String =
    date.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

fun readingClockText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** The one-letter name of the day, as calendars label their columns. */
fun weekdayInitial(date: LocalDate): String =
    date.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

fun weekdayName(date: LocalDate): String =
    date.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
