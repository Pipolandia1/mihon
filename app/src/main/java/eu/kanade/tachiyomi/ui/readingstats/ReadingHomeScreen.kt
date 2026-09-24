package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.readingstats.ReadingDayContent
import eu.kanade.presentation.readingstats.ReadingStatsContent
import eu.kanade.presentation.readingstats.readingTimeText
import eu.kanade.tachiyomi.readingstats.ReadingStatsConfig
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CalendarMonth
import mihon.icons.materialsymbols.rounded.DeleteSweep
import mihon.icons.materialsymbols.rounded.QueryStats
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.plans.PlanRules
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

private const val HISTORY_PAGE = 1

/**
 * The History tab: reading stats first, then Mihon's history list, which [historyContent] draws
 * unchanged. Picking a day shows that day's sessions in place of the list.
 */
@Composable
fun ReadingHomeScreen(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onClearHistory: () -> Unit,
    historySnackbarHostState: SnackbarHostState,
    historyContent: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val viewModel = metroViewModel<ReadingStatsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    val statsTab = TabContent(
        titleRes = MR.strings.reading_stats_tab,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.reading_stats_library_stats),
                icon = MaterialSymbols.Rounded.QueryStats,
                onClick = { navigator.push(StatsScreen()) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.reading_stats_how_counted),
                onClick = { viewModel.setDialog(ReadingStatsViewModel.Dialog.Definitions) },
            ),
            AppBar.OverflowAction(
                title = stringResource(
                    if (state.recordingEnabled) MR.strings.reading_stats_pause else MR.strings.reading_stats_resume,
                ),
                onClick = { viewModel.setRecordingEnabled(!state.recordingEnabled) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.reading_stats_delete_all),
                onClick = { viewModel.setDialog(ReadingStatsViewModel.Dialog.DeleteAll) },
            ),
        ),
        content = { contentPadding, _ ->
            ReadingStatsContent(
                state = state,
                contentPadding = contentPadding,
                onClickDay = { date ->
                    viewModel.selectDay(date)
                    scope.launch { pagerState.animateScrollToPage(HISTORY_PAGE) }
                },
                onClickContinue = viewModel::openChapter,
                onOpenPlan = { navigator.push(ReadingPlanEditorScreen(it)) },
                onEditGoals = { navigator.push(ReadingGoalsScreen()) },
                onEditPlans = { navigator.push(ReadingPlansScreen()) },
                onShowMonth = viewModel::showMonth,
            )
        },
    )
    val historyTab = TabContent(
        titleRes = MR.strings.history,
        searchEnabled = state.selectedDay == null,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.reading_stats_pick_day),
                icon = MaterialSymbols.Rounded.CalendarMonth,
                onClick = { viewModel.setDialog(ReadingStatsViewModel.Dialog.PickDay) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.pref_clear_history),
                icon = MaterialSymbols.Rounded.DeleteSweep,
                onClick = onClearHistory,
            ),
        ),
        content = { contentPadding, _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                val day = state.selectedDay
                if (day == null) {
                    historyContent(contentPadding)
                } else {
                    ReadingDayContent(
                        date = day,
                        entries = state.dayEntries,
                        recordingStartedOn = state.week?.recordingStartedOn,
                        contentPadding = contentPadding,
                        onClearDay = { viewModel.selectDay(null) },
                        onClickEntry = { navigator.push(MangaScreen(it)) },
                        onDeleteSession = { viewModel.setDialog(ReadingStatsViewModel.Dialog.DeleteSession(it)) },
                    )
                }
                SnackbarHost(
                    hostState = historySnackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(contentPadding),
                )
            }
        },
    )

    TabbedScreen(
        titleRes = MR.strings.reading_stats_home_title,
        tabs = listOf(statsTab, historyTab),
        state = pagerState,
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
    )

    val onDismissRequest = { viewModel.setDialog(null) }
    when (val dialog = state.dialog) {
        is ReadingStatsViewModel.Dialog.DeleteSession -> ConfirmDialog(
            title = stringResource(MR.strings.reading_stats_delete_session),
            text = stringResource(MR.strings.reading_stats_delete_session_confirm),
            onConfirm = { viewModel.deleteSession(dialog.sessionId) },
            onDismissRequest = onDismissRequest,
        )
        ReadingStatsViewModel.Dialog.DeleteAll -> ConfirmDialog(
            title = stringResource(MR.strings.reading_stats_delete_all),
            text = stringResource(MR.strings.reading_stats_delete_all_confirm),
            onConfirm = viewModel::deleteAll,
            onDismissRequest = onDismissRequest,
        )
        ReadingStatsViewModel.Dialog.Definitions -> AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_ok)) }
            },
            title = { Text(stringResource(MR.strings.reading_stats_how_counted)) },
            text = {
                val tracker = ReadingStatsConfig.TRACKER
                Text(
                    text = stringResource(
                        MR.strings.reading_stats_definitions,
                        readingTimeText(context, tracker.idleTimeout.inWholeMilliseconds),
                        readingTimeText(context, tracker.idleGrace.inWholeMilliseconds),
                        readingTimeText(context, ReadingMetrics.READING_DAY_MIN_ACTIVE.inWholeMilliseconds),
                    ) + "\n\n" + stringResource(
                        MR.strings.reading_stats_definitions_habits,
                        PlanRules.FORECAST_WINDOW_DAYS,
                        PlanRules.FORECAST_MIN_DAYS,
                        PlanRules.FORECAST_MIN_FINISHED,
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
        ReadingStatsViewModel.Dialog.PickDay -> PickDayDialog(
            initial = state.selectedDay ?: state.week?.today,
            onPick = { date ->
                viewModel.selectDay(date)
                onDismissRequest()
            },
            onDismissRequest = onDismissRequest,
        )
        null -> {}
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collectLatest { event ->
            when (event) {
                is ReadingStatsViewModel.Event.OpenChapter ->
                    context.startActivity(ReaderActivity.newIntent(context, event.mangaId, event.chapterId))
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
        title = { Text(title) },
        text = { Text(text) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDayDialog(
    initial: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // The date picker speaks in UTC midnights.
    val now = Clock.System.now()
    val todayUtc = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds() ?: todayUtc,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis ?: return@TextButton
                    onPick(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                },
            ) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}
