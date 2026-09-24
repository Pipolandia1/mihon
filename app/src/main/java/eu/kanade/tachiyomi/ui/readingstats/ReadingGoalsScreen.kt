package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.ReadingGoal
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val TargetFieldWidth = 112.dp

/** Daily and weekly targets for new pages, chapters and minutes, and which one is primary. */
class ReadingGoalsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ReadingGoalsViewModel>()
        val goals by viewModel.goals.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_goals),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                item {
                    Text(
                        text = stringResource(MR.strings.reading_stats_goals_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.padding.extraLarge,
                            vertical = MaterialTheme.padding.small,
                        ),
                    )
                }
                GoalMetric.entries.forEach { metric ->
                    item(key = metric.name) {
                        SectionCard(
                            when (metric) {
                                GoalMetric.PAGES -> MR.strings.reading_stats_pages_new
                                GoalMetric.CHAPTERS -> MR.strings.reading_stats_chapters_finished
                                GoalMetric.MINUTES -> MR.strings.reading_stats_minutes_reading
                            },
                        ) {
                            GoalPeriod.entries.forEach { period ->
                                GoalSlot(
                                    period = period,
                                    goal = goals[metric to period],
                                    onTarget = { viewModel.setTarget(metric, period, it) },
                                    onPrimary = { goal -> viewModel.makePrimary(goal) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalSlot(
    period: GoalPeriod,
    goal: ReadingGoal?,
    onTarget: (Int?) -> Unit,
    onPrimary: (ReadingGoal) -> Unit,
) {
    // Once edited, the field shows what was typed: a late write coming back must not undo a keystroke.
    var edited by remember { mutableStateOf<String?>(null) }
    val text = edited ?: goal?.target?.toString().orEmpty()
    val primaryLabel = stringResource(MR.strings.reading_stats_primary_goal)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(
                if (period == GoalPeriod.DAY) MR.strings.reading_stats_per_day else MR.strings.reading_stats_per_week,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit).take(MAX_TARGET_DIGITS)
                edited = digits
                onTarget(digits.toIntOrNull()?.takeIf { it > 0 })
            },
            placeholder = { Text(stringResource(MR.strings.reading_stats_no_goal)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(TargetFieldWidth),
        )
        RadioButton(
            selected = goal?.isPrimary == true,
            onClick = { goal?.let(onPrimary) },
            enabled = goal != null,
            modifier = Modifier.semantics { contentDescription = primaryLabel },
        )
    }
}

private const val MAX_TARGET_DIGITS = 5

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingGoalsViewModel(
    private val repository: ReadingPlanRepository,
) : ViewModel() {

    val goals: StateFlow<Map<Pair<GoalMetric, GoalPeriod>, ReadingGoal>> = repository.subscribeGoals()
        .map { goals -> goals.associateBy { it.metric to it.period } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), emptyMap())

    /** Edits are written one at a time, in the order they were typed. */
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (edit in edits) {
                try {
                    edit()
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }
    }

    /** Sets a target, or removes the goal when [target] is null. */
    fun setTarget(metric: GoalMetric, period: GoalPeriod, target: Int?) {
        edits.trySend {
            if (target == null) {
                repository.removeGoal(metric, period)
            } else {
                val primary = goals.value[metric to period]?.isPrimary == true
                repository.setGoal(
                    ReadingGoal(metric, period, target, primary),
                    Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
    }

    fun makePrimary(goal: ReadingGoal) {
        edits.trySend { repository.setGoal(goal.copy(isPrimary = true), Clock.System.now().toEpochMilliseconds()) }
    }
}
