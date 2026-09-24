package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import eu.kanade.presentation.readingstats.PlanSummary
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.readingstats.PlanProgressSource
import eu.kanade.tachiyomi.readingstats.ReadingDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.plans.PlanProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

/** Every reading plan, with a way to start another. */
class ReadingPlansScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ReadingPlansViewModel>()
        val plans by viewModel.plans.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_plans),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(MR.strings.reading_stats_new_plan)) },
                    icon = { Icon(MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { navigator.push(ReadingPlanEditorScreen(null)) },
                )
            },
        ) { contentPadding ->
            val current = plans
            LazyColumn(contentPadding = contentPadding) {
                if (current != null && current.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.reading_stats_plans_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(MaterialTheme.padding.extraLarge),
                        )
                    }
                }
                items(current.orEmpty(), key = { it.plan.id }) { plan ->
                    SectionCard {
                        PlanSummary(plan, onClick = { navigator.push(ReadingPlanEditorScreen(plan.plan.id)) })
                    }
                }
            }
        }
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingPlansViewModel(
    readingDays: ReadingDays,
    planProgress: PlanProgressSource,
) : ViewModel() {

    /** Null while loading. */
    val plans: StateFlow<List<PlanProgress>?> = planProgress
        .subscribe(readingDays.today, readingDays.recordingStartedOn(), readingDays.firstDayOfWeek)
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)
}
