package eu.kanade.presentation.readingstats

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import dev.icerock.moko.resources.StringResource
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toJavaLocalDate
import tachiyomi.domain.readingstats.analysis.BucketSize
import tachiyomi.domain.readingstats.analysis.ChartBucket
import tachiyomi.domain.readingstats.analysis.DayPart
import tachiyomi.domain.readingstats.analysis.PeriodChoice
import tachiyomi.domain.readingstats.analysis.RankingDimension
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun periodChoiceName(choice: PeriodChoice): StringResource = when (choice) {
    PeriodChoice.LAST_7_DAYS -> MR.strings.reading_stats_period_7_days
    PeriodChoice.LAST_30_DAYS -> MR.strings.reading_stats_period_30_days
    PeriodChoice.LAST_12_MONTHS -> MR.strings.reading_stats_period_12_months
    PeriodChoice.ALL -> MR.strings.reading_stats_period_all
    PeriodChoice.CUSTOM -> MR.strings.reading_stats_period_custom
}

fun rankingDimensionName(dimension: RankingDimension): StringResource = when (dimension) {
    RankingDimension.SERIES -> MR.strings.reading_stats_rank_series
    RankingDimension.PUBLISHER -> MR.strings.reading_stats_field_publisher
    RankingDimension.IMPRINT -> MR.strings.reading_stats_field_imprint
    RankingDimension.WRITER -> MR.strings.reading_stats_field_writer
    RankingDimension.ARTIST -> MR.strings.reading_stats_field_artist
    RankingDimension.GENRE -> MR.strings.reading_stats_field_genre
    RankingDimension.CHARACTER -> MR.strings.reading_stats_field_character
    RankingDimension.UNIVERSE -> MR.strings.reading_stats_field_universe
    RankingDimension.DECADE -> MR.strings.reading_stats_rank_decade
}

/** "In the morning", to finish "You read most …". */
fun dayPartPhrase(part: DayPart): StringResource = when (part) {
    DayPart.MORNING -> MR.strings.reading_stats_day_part_morning
    DayPart.AFTERNOON -> MR.strings.reading_stats_day_part_afternoon
    DayPart.EVENING -> MR.strings.reading_stats_day_part_evening
    DayPart.NIGHT -> MR.strings.reading_stats_day_part_night
}

@Composable
@ReadOnlyComposable
fun rankingValueText(dimension: RankingDimension, value: String): String = when (dimension) {
    RankingDimension.DECADE -> stringResource(MR.strings.reading_stats_decade, value)
    else -> value
}

fun weekdayLongName(day: DayOfWeek): String =
    java.time.DayOfWeek.of(day.isoDayNumber).getDisplayName(TextStyle.FULL, Locale.getDefault())

fun weekdayNarrowName(day: DayOfWeek): String =
    java.time.DayOfWeek.of(day.isoDayNumber).getDisplayName(TextStyle.NARROW, Locale.getDefault())

/** "12 – 18 Sep", with years only when not this one. */
fun periodText(period: ReadingPeriod, today: LocalDate): String =
    if (period.from == period.to) {
        readingShortDateText(period.from, today)
    } else {
        "${readingShortDateText(period.from, today)} – ${readingShortDateText(period.to, today)}"
    }

/** The name of a chart bar for screen readers and summaries: a day, a week's first day, or a month. */
fun bucketName(bucket: ChartBucket, size: BucketSize, today: LocalDate): String = when (size) {
    BucketSize.DAY -> readingShortDateText(bucket.period.from, today)
    BucketSize.WEEK -> periodText(bucket.period, today)
    BucketSize.MONTH -> monthText(bucket.period.from, withYear = bucket.period.from.year != today.year)
}

/**
 * The label under a chart bar, or null to leave it bare: every day of a week, every fifth day
 * of a month, the first week of each month, and every month or every third of a long run.
 */
fun bucketLabel(buckets: List<ChartBucket>, index: Int, size: BucketSize): String? {
    val from = buckets[index].period.from
    return when (size) {
        BucketSize.DAY -> when {
            buckets.size <= 7 -> weekdayNarrowName(from.dayOfWeek)
            index == 0 || from.day % 5 == 0 -> from.day.toString()
            else -> null
        }
        BucketSize.WEEK -> if (index == 0 || from.day <= 7) shortMonth(from) else null
        BucketSize.MONTH -> if (buckets.size <= 13 || index % 3 == 0) shortMonth(from) else null
    }
}

fun monthText(date: LocalDate, withYear: Boolean): String {
    val skeleton = if (withYear) "MMMMy" else "MMMM"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    return date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private fun shortMonth(date: LocalDate): String =
    date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())).trimEnd('.')
