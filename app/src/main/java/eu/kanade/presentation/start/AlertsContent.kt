package eu.kanade.presentation.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.releases.FollowOption
import eu.kanade.tachiyomi.ui.start.releases.AlertsModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Notifications
import mihon.icons.materialsymbols.roundedfilled.Notifications
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** The bell of a release (docs/HOME_PLAN.md §8.3, H7): filled while something covering it is followed. */
@Composable
fun FollowBell(followed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = BELL_BACKGROUND_ALPHA),
        modifier = modifier
            .padding(MaterialTheme.padding.extraSmall)
            .size(BellSize)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = if (followed) {
                MaterialSymbols.RoundedFilled.Notifications
            } else {
                MaterialSymbols.Rounded.Notifications
            },
            contentDescription = stringResource(MR.strings.releases_follow_title),
            tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(BellPadding),
        )
    }
}

/**
 * What can be followed of [item], checked as it is followed: the issue, the series, and — with
 * Metron's details — its creators and characters.
 */
@Composable
fun FollowDialog(
    item: ReleaseItem,
    loadOptions: suspend (ReleaseItem) -> List<FollowOption>,
    onSave: (List<FollowOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    var options by remember(item.key) { mutableStateOf<List<FollowOption>?>(null) }
    LaunchedEffect(item.key) { options = loadOptions(item) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.releases_follow_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Text("${item.seriesName} #${item.number}", style = MaterialTheme.typography.bodyMedium)
                val shown = options
                if (shown == null) {
                    CircularProgressIndicator(modifier = Modifier.padding(MaterialTheme.padding.medium))
                } else {
                    shown.forEachIndexed { index, option ->
                        OptionRow(option) { checked ->
                            options = shown.toMutableList().also { it[index] = option.copy(checked = checked) }
                        }
                    }
                    if (shown.none { it.follow.kind == FollowKind.CREATOR || it.follow.kind == FollowKind.CHARACTER }) {
                        Faint(stringResource(MR.strings.releases_follow_names_need_metron))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    options?.let(onSave)
                    onDismiss()
                },
                enabled = options != null,
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun OptionRow(option: FollowOption, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!option.checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = option.checked, onCheckedChange = onChecked)
        Column {
            Text(optionTitle(option.follow), style = MaterialTheme.typography.bodyMedium)
            Faint(optionDetail(option))
        }
    }
}

@Composable
private fun optionTitle(follow: Follow) = when (follow.kind) {
    FollowKind.ISSUE -> stringResource(MR.strings.releases_follow_issue)
    FollowKind.SERIES -> stringResource(MR.strings.releases_follow_series)
    FollowKind.CREATOR, FollowKind.CHARACTER, FollowKind.ARC -> follow.label
}

@Composable
private fun optionDetail(option: FollowOption) = when (option.follow.kind) {
    FollowKind.ISSUE, FollowKind.SERIES -> option.follow.label
    FollowKind.CHARACTER -> stringResource(MR.strings.releases_follow_character)
    FollowKind.ARC -> stringResource(MR.strings.releases_follow_arc)
    FollowKind.CREATOR -> when (option.role) {
        "Writer", "Story" -> stringResource(MR.strings.releases_follow_writer)
        else -> stringResource(MR.strings.releases_follow_artist)
    }
}

/** "Alertas": what is followed, and the check by hand. */
@Composable
fun AlertsContent(
    state: AlertsModel.State,
    navigateUp: () -> Unit,
    onCheckNow: () -> Unit,
    onUnfollow: (Follow) -> Unit,
    onAllowNotifications: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.releases_alerts),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "about") {
                Faint(
                    stringResource(MR.strings.releases_alerts_about),
                    Modifier.padding(horizontal = MaterialTheme.padding.medium),
                )
            }
            if (!state.notificationsOn) {
                item(key = "notifications-off") {
                    Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
                        Text(
                            stringResource(MR.strings.releases_notifications_off),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = onAllowNotifications) {
                            Text(stringResource(MR.strings.releases_notifications_allow))
                        }
                    }
                }
            }
            item(key = "check") {
                Column(
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onCheckNow, enabled = !state.checking && state.follows.isNotEmpty()) {
                            Text(stringResource(MR.strings.releases_check_now))
                        }
                        if (state.checking) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    state.result?.let { result ->
                        val told = result.out.map { "${it.seriesName} #${it.number}" }
                        val available = result.available.map { "${it.seriesName} #${it.number}" }
                        Faint(
                            when {
                                result.notificationsOff -> stringResource(MR.strings.releases_check_off)
                                told.isEmpty() && available.isEmpty() -> stringResource(
                                    MR.strings.releases_check_nothing,
                                )
                                else -> listOfNotNull(
                                    told.takeIf { it.isNotEmpty() }
                                        ?.let { stringResource(MR.strings.releases_check_out, it.joinToString()) },
                                    available.takeIf { it.isNotEmpty() }
                                        ?.let {
                                            stringResource(MR.strings.releases_check_available, it.joinToString())
                                        },
                                ).joinToString("\n")
                            },
                        )
                    }
                }
            }
            item(key = "following") {
                SectionTitle(
                    pluralStringResource(MR.plurals.releases_following, state.follows.size, state.follows.size),
                )
            }
            if (state.follows.isEmpty()) {
                item(key = "empty") {
                    Faint(
                        stringResource(MR.strings.releases_following_none),
                        Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
            }
            items(state.follows, key = { "${it.kind}-${it.key}" }) { follow ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = MaterialTheme.padding.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            follow.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Faint(kindText(follow.kind))
                    }
                    IconButton(onClick = { onUnfollow(follow) }) {
                        Icon(
                            MaterialSymbols.Rounded.Close,
                            contentDescription = stringResource(MR.strings.releases_unfollow),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun kindText(kind: FollowKind) = stringResource(
    when (kind) {
        FollowKind.ISSUE -> MR.strings.releases_follow_issue
        FollowKind.SERIES -> MR.strings.releases_follow_series
        FollowKind.CHARACTER -> MR.strings.releases_follow_character
        FollowKind.CREATOR -> MR.strings.releases_follow_creator
        FollowKind.ARC -> MR.strings.releases_follow_arc
    },
)

@Composable
private fun Faint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        modifier = modifier,
    )
}

private const val BELL_BACKGROUND_ALPHA = 0.85f
private val BellSize = 32.dp
private val BellPadding = 6.dp
