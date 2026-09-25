package eu.kanade.presentation.readingstats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import dev.icerock.moko.resources.StringResource
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.UnitCounts
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

fun fieldName(field: ComicField): StringResource = when (field) {
    ComicField.UNIT -> MR.strings.reading_stats_field_unit
    ComicField.SERIES_NAME -> MR.strings.reading_stats_field_series
    ComicField.VOLUME -> MR.strings.reading_stats_field_volume
    ComicField.START_YEAR -> MR.strings.reading_stats_field_start_year
    ComicField.END_YEAR -> MR.strings.reading_stats_field_end_year
    ComicField.PUBLISHER -> MR.strings.reading_stats_field_publisher
    ComicField.IMPRINT -> MR.strings.reading_stats_field_imprint
    ComicField.UNIVERSE -> MR.strings.reading_stats_field_universe
    ComicField.GENRE -> MR.strings.reading_stats_field_genre
    ComicField.TOTAL_ISSUES -> MR.strings.reading_stats_field_total_issues
    ComicField.ISSUE_LABEL -> MR.strings.reading_stats_field_issue_label
    ComicField.VARIANT -> MR.strings.reading_stats_field_variant
    ComicField.CONTAINS_ISSUES -> MR.strings.reading_stats_field_contains
    ComicField.COVER_DATE -> MR.strings.reading_stats_field_cover_date
    ComicField.PAGE_COUNT -> MR.strings.reading_stats_field_page_count
    ComicField.WRITER -> MR.strings.reading_stats_field_writer
    ComicField.ARTIST -> MR.strings.reading_stats_field_artist
    ComicField.COLORIST -> MR.strings.reading_stats_field_colorist
    ComicField.LETTERER -> MR.strings.reading_stats_field_letterer
    ComicField.COVER_ARTIST -> MR.strings.reading_stats_field_cover_artist
    ComicField.EDITOR -> MR.strings.reading_stats_field_editor
    ComicField.CHARACTER -> MR.strings.reading_stats_field_character
    ComicField.TEAM -> MR.strings.reading_stats_field_team
    ComicField.ARC -> MR.strings.reading_stats_field_arc
    ComicField.METRON_ID -> MR.strings.reading_stats_origin_metron
    ComicField.GCD_ID -> MR.strings.reading_stats_origin_gcd
}

fun unitName(unit: ComicUnit?): StringResource = when (unit) {
    ComicUnit.ISSUE -> MR.strings.reading_stats_unit_issue
    ComicUnit.TPB -> MR.strings.reading_stats_unit_tpb
    ComicUnit.HARDCOVER -> MR.strings.reading_stats_unit_hardcover
    ComicUnit.OMNIBUS -> MR.strings.reading_stats_unit_omnibus
    ComicUnit.OTHER -> MR.strings.reading_stats_unit_other
    null -> MR.strings.reading_stats_unit_unknown
}

fun originName(origin: MetadataOrigin): StringResource = when (origin) {
    MetadataOrigin.USER -> MR.strings.reading_stats_origin_user
    MetadataOrigin.METRON -> MR.strings.reading_stats_origin_metron
    MetadataOrigin.GCD -> MR.strings.reading_stats_origin_gcd
    MetadataOrigin.COMIC_INFO -> MR.strings.reading_stats_origin_comic_info
    MetadataOrigin.SOURCE -> MR.strings.reading_stats_origin_source
    MetadataOrigin.INFERRED -> MR.strings.reading_stats_origin_inferred
}

fun statusName(status: LibraryStatus): StringResource = when (status) {
    LibraryStatus.NOT_STARTED -> MR.strings.reading_stats_status_not_started
    LibraryStatus.IN_PROGRESS -> MR.strings.reading_stats_status_in_progress
    LibraryStatus.CAUGHT_UP -> MR.strings.reading_stats_status_caught_up
    LibraryStatus.COMPLETED -> MR.strings.reading_stats_status_completed
}

fun collectionKindName(kind: CollectionKind): StringResource = when (kind) {
    CollectionKind.ENTRY -> MR.strings.reading_stats_kind_entry
    CollectionKind.LIST -> MR.strings.reading_stats_kind_list
    CollectionKind.RUN -> MR.strings.reading_stats_kind_run
    CollectionKind.ARC -> MR.strings.reading_stats_kind_arc
    CollectionKind.EVENT -> MR.strings.reading_stats_kind_event
    CollectionKind.READING_ORDER -> MR.strings.reading_stats_kind_reading_order
}

/** A field's values as one line; a unit by its name. */
@Composable
@ReadOnlyComposable
fun fieldValueText(field: ComicField, values: List<String>): String = when (field) {
    ComicField.UNIT -> stringResource(unitName(ComicUnit.of(values.firstOrNull())))
    else -> values.joinToString(", ")
}

/** "3 issues · 1 collected edition · 12 chapters of unknown format"; "nothing" when empty. */
@Composable
@ReadOnlyComposable
fun unitCountsText(counts: UnitCounts): String {
    val parts = buildList {
        if (counts.issues > 0) {
            add(pluralStringResource(MR.plurals.reading_stats_count_issues, counts.issues, counts.issues))
        }
        if (counts.collected > 0) {
            add(pluralStringResource(MR.plurals.reading_stats_count_collected, counts.collected, counts.collected))
        }
        if (counts.other > 0) {
            add(pluralStringResource(MR.plurals.reading_stats_count_other, counts.other, counts.other))
        }
        if (counts.unknown > 0) {
            add(pluralStringResource(MR.plurals.reading_stats_count_unknown, counts.unknown, counts.unknown))
        }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(MR.strings.reading_stats_nothing) }
}

/** "2011", "2011–2013", "2025–". */
fun yearsText(began: Int?, ended: Int?, ongoing: Boolean = ended == null): String = when {
    began == null -> ""
    ended == began -> began.toString()
    ended != null -> "$began–$ended"
    ongoing -> "$began–"
    else -> began.toString()
}
