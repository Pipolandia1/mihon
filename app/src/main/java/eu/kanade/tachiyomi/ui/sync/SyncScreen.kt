package eu.kanade.tachiyomi.ui.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import eu.kanade.presentation.sync.SyncContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.sync.GoogleDriveAuth
import eu.kanade.tachiyomi.sync.SyncRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.sync.SyncChanges
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncLog
import tachiyomi.domain.sync.SyncLogEntry
import tachiyomi.domain.sync.SyncPreferences
import tachiyomi.domain.sync.SyncReason

/**
 * "Sincronização" (docs/DESKTOP_PLAN.md §3.4, S2): connecting the Google account, the summary before
 * the first sync, and afterwards the last syncs and a sync by hand.
 */
class SyncScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<SyncModel>()
        val state by model.state.collectAsStateWithLifecycle()

        // Google's consent screen, the first time and after access was revoked.
        val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            model.onConsentResult(it.resultCode == Activity.RESULT_OK, it.data)
        }
        LaunchedEffect(state.consent) {
            val intent = state.consent ?: return@LaunchedEffect
            model.consentShown()
            consent.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        }

        SyncContent(
            state = state,
            navigateUp = navigator::pop,
            onConnect = model::connect,
            onConfirm = model::confirm,
            onSyncNow = model::syncNow,
            onDisconnect = model::disconnect,
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SyncModel(
    private val runner: SyncRunner,
    private val auth: GoogleDriveAuth,
    preferences: SyncPreferences,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    init {
        viewModelScope.launchIO {
            combine(
                preferences.account.changes(),
                preferences.firstSyncDone.changes(),
                preferences.lastSyncAt.changes(),
                preferences.log.changes(),
                runner.running,
            ) { account, done, lastSyncAt, log, running ->
                state.update {
                    it.copy(
                        account = account,
                        firstSyncDone = done,
                        lastSyncAt = lastSyncAt,
                        log = SyncLog.decode(log),
                        running = running,
                    )
                }
            }.collect {}
        }
    }

    fun connect() {
        if (state.value.busy) return
        state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launchIO {
            try {
                when (val grant = auth.authorize()) {
                    is GoogleDriveAuth.Grant.Token -> connected(grant.value)
                    is GoogleDriveAuth.Grant.NeedsConsent -> state.update { it.copy(consent = grant.intent) }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun consentShown() {
        state.update { it.copy(consent = null) }
    }

    fun onConsentResult(granted: Boolean, data: Intent?) {
        viewModelScope.launchIO {
            try {
                val token = if (granted) auth.tokenFrom(data) else null
                if (token == null) state.update { it.copy(busy = false) } else connected(token)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun confirm() {
        viewModelScope.launchIO {
            runner.firstSync()
            state.update { it.copy(preview = null) }
        }
    }

    fun syncNow() {
        viewModelScope.launchIO { runner.sync(SyncReason.MANUAL) }
    }

    fun disconnect() {
        viewModelScope.launchIO {
            runner.disconnect()
            state.update { it.copy(preview = null) }
        }
    }

    private suspend fun connected(token: String) {
        runner.connect(token)
        val preview = runner.preview()
        state.update {
            it.copy(
                busy = false,
                preview = preview?.let { found ->
                    Preview(
                        here = counts(found.result.here),
                        there = counts(found.result.there),
                        fileExists = found.fileExists,
                    )
                },
            )
        }
    }

    private fun fail(e: Exception) {
        logcat(LogPriority.ERROR, e) { "Sync could not connect" }
        state.update { it.copy(busy = false, failure = e.message ?: e.javaClass.simpleName) }
    }

    private fun counts(changes: SyncChanges): List<Pair<SyncEntity, Int>> =
        SyncEntity.entries.mapNotNull { entity ->
            changes.counts[entity]?.total?.takeIf { it > 0 }?.let { entity to it }
        }

    @Immutable
    data class State(
        val account: String = "",
        val firstSyncDone: Boolean = false,
        val lastSyncAt: Long = 0,
        val log: List<SyncLogEntry> = emptyList(),
        val running: Boolean = false,
        val busy: Boolean = false,
        val preview: Preview? = null,
        val consent: PendingIntent? = null,
        val failure: String? = null,
    ) {
        /** Connected and confirmed: syncing by itself. */
        val syncing: Boolean get() = account.isNotEmpty() && firstSyncDone
    }

    @Immutable
    data class Preview(
        val here: List<Pair<SyncEntity, Int>>,
        val there: List<Pair<SyncEntity, Int>>,
        val fileExists: Boolean,
    )
}
