package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.readingstats.comics.AutoStatus
import tachiyomi.domain.readingstats.repository.AutoMetadataRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where comic metadata may be looked up online: the user's Metron token and optional GCD login. */
class ComicOnlineSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ComicOnlineSettingsViewModel>()
        var token by remember { mutableStateOf(viewModel.preferences.metronToken.get()) }
        var username by remember { mutableStateOf(viewModel.preferences.gcdUsername.get()) }
        var password by remember { mutableStateOf(viewModel.preferences.gcdPassword.get()) }
        var auto by remember { mutableStateOf(viewModel.preferences.autoMetadata.get()) }
        val autoDone by viewModel.autoDone.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_online_settings),
                    navigateUp = navigator::pop,
                    actions = {
                        TextButton(
                            onClick = {
                                viewModel.save(token, username, password, auto)
                                navigator.pop()
                            },
                        ) {
                            Text(stringResource(MR.strings.action_save))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                Text(stringResource(MR.strings.reading_stats_online_intro), style = MaterialTheme.typography.bodyMedium)

                Text("Metron", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text(stringResource(MR.strings.reading_stats_metron_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Help(stringResource(MR.strings.reading_stats_metron_token_help))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(MR.strings.reading_stats_auto_metadata),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                Help(stringResource(MR.strings.reading_stats_auto_metadata_help))
                Help(autoText(viewModel.preferences.autoMetadataLastRunAt.get(), autoDone))

                Text(
                    "Grand Comics Database",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Help(stringResource(MR.strings.reading_stats_gcd_help))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text(stringResource(MR.strings.reading_stats_gcd_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.reading_stats_gcd_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Help(stringResource(MR.strings.reading_stats_online_privacy))
            }
        }
    }
}

/** What automatic metadata has done so far, by status, and when it last ran. */
@Composable
private fun autoText(lastRunAt: Long, done: Map<AutoStatus, Int>): String {
    if (lastRunAt <= 0L) return stringResource(MR.strings.reading_stats_auto_metadata_never)
    val at = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .format(Instant.ofEpochMilli(lastRunAt).atZone(ZoneId.systemDefault()))
    return stringResource(
        MR.strings.reading_stats_auto_metadata_last,
        done[AutoStatus.CONFIRMED] ?: 0,
        done[AutoStatus.SUGGESTED] ?: 0,
        done[AutoStatus.NOT_FOUND] ?: 0,
        at,
    )
}

@Composable
private fun Help(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
    )
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ComicOnlineSettingsViewModel(
    val preferences: ReadingStatsPreferences,
    autoStates: AutoMetadataRepository,
) : ViewModel() {

    /** How many works automatic metadata left in each status (H13). */
    val autoDone: StateFlow<Map<AutoStatus, Int>> = flow { emit(autoStates.all().groupingBy { it.status }.eachCount()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun save(token: String, username: String, password: String, auto: Boolean) {
        preferences.metronToken.set(token.trim())
        preferences.autoMetadata.set(auto)
        preferences.gcdUsername.set(username.trim())
        preferences.gcdPassword.set(password)
    }
}
