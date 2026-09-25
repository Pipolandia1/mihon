package eu.kanade.tachiyomi.ui.start

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.readingstats.ContinueItem
import eu.kanade.tachiyomi.readingstats.ContinueReadingSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StartViewModel(
    continueReading: ContinueReadingSource,
) : ViewModel() {

    val state: StateFlow<State> = continueReading.subscribe(StartConfig.CONTINUE_READING_COUNT)
        .map { State(loading = false, continueReading = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StartConfig.STOP_TIMEOUT_MS), State())

    @Immutable
    data class State(
        val loading: Boolean = true,
        /** The first is the one to continue; the rest are other works in progress. */
        val continueReading: List<ContinueItem> = emptyList(),
    )
}
