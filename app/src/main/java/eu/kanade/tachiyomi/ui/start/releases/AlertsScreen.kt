package eu.kanade.tachiyomi.ui.start.releases

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import eu.kanade.presentation.start.AlertsContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.releases.ReleaseAlerts
import eu.kanade.tachiyomi.releases.ReleaseFollowing
import eu.kanade.tachiyomi.releases.ReleaseNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.start.Follow

/** "Alertas" (docs/HOME_PLAN.md §8.3, H7): what the reader follows, and the check run by hand. */
class AlertsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = metroViewModel<AlertsModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.refreshNotifications()
        }
        // Back from the system settings, the switch may have changed.
        LifecycleResumeEffect(Unit) {
            model.refreshNotifications()
            onPauseOrDispose {}
        }

        AlertsContent(
            state = state,
            navigateUp = navigator::pop,
            onCheckNow = model::checkNow,
            onUnfollow = model::unfollow,
            onAllowNotifications = {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Allowed, and turned off in the settings: only there can it be turned on again.
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AlertsModel(
    private val following: ReleaseFollowing,
    private val alerts: ReleaseAlerts,
    private val notifier: ReleaseNotifier,
) : ViewModel() {

    private val check = MutableStateFlow(Check())
    private val notificationsOn = MutableStateFlow(notifier.enabled())

    val state: StateFlow<State> = combine(following.follows, check, notificationsOn) { follows, check, on ->
        State(follows = follows, checking = check.running, result = check.result, notificationsOn = on)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), State())

    fun refreshNotifications() {
        notificationsOn.value = notifier.enabled()
    }

    /** The same check the daily job runs, now, with the device's clock. */
    fun checkNow() {
        if (check.value.running) return
        check.update { it.copy(running = true) }
        viewModelScope.launchIO {
            val outcome = runCatching { alerts.check() }.getOrNull()
            check.value = Check(running = false, result = outcome)
        }
    }

    fun unfollow(follow: Follow) {
        viewModelScope.launchIO { following.unfollow(follow) }
    }

    private data class Check(val running: Boolean = false, val result: ReleaseAlerts.Outcome? = null)

    @Immutable
    data class State(
        val follows: List<Follow> = emptyList(),
        val checking: Boolean = false,
        /** The last check by hand: what it told. */
        val result: ReleaseAlerts.Outcome? = null,
        /** Whether the reader can be told anything (permission and channel). */
        val notificationsOn: Boolean = true,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
