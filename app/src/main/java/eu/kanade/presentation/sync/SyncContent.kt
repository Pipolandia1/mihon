package eu.kanade.presentation.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.sync.SyncModel
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncError
import tachiyomi.domain.sync.SyncLogEntry
import tachiyomi.domain.sync.SyncReason
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

/** The Sync screen (docs/DESKTOP_PLAN.md §3.4): connect, the first sync's summary, then the history. */
@Composable
fun SyncContent(
    state: SyncModel.State,
    navigateUp: () -> Unit,
    onConnect: () -> Unit,
    onConfirm: () -> Unit,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.sync_title),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        LazyColumn(contentPadding = contentPadding) {
            item(key = "about") { Paragraph(stringResource(MR.strings.sync_about)) }
            state.failure?.let { failure ->
                item(key = "failure") { Paragraph(failure, error = true) }
            }
            val preview = state.preview
            when {
                state.syncing -> {
                    item(key = "account") { Paragraph(stringResource(MR.strings.sync_account, state.account)) }
                    item(key = "last") {
                        Paragraph(
                            if (state.lastSyncAt == 0L) {
                                stringResource(MR.strings.sync_never)
                            } else {
                                stringResource(MR.strings.sync_last, formatDate(state.lastSyncAt))
                            },
                        )
                    }
                    item(key = "when") { Paragraph(stringResource(MR.strings.sync_when), secondary = true) }
                    item(key = "now") {
                        Actions {
                            Button(onClick = onSyncNow, enabled = !state.running) {
                                if (state.running) Spinner()
                                Text(
                                    stringResource(if (state.running) MR.strings.sync_running else MR.strings.sync_now),
                                )
                            }
                            TextButton(onClick = onDisconnect, enabled = !state.running) {
                                Text(stringResource(MR.strings.sync_disconnect))
                            }
                        }
                    }
                    item(key = "disconnect-summary") {
                        Paragraph(stringResource(MR.strings.sync_disconnect_summary), secondary = true)
                    }
                    if (state.log.isNotEmpty()) {
                        item(key = "log-title") { Header(stringResource(MR.strings.sync_log)) }
                        items(state.log, key = { "log-${it.at}-${it.reason}" }) { LogRow(it) }
                    }
                }
                preview != null && state.account.isNotEmpty() -> {
                    item(key = "account") { Paragraph(stringResource(MR.strings.sync_account, state.account)) }
                    item(key = "preview-title") { Header(stringResource(MR.strings.sync_preview_title)) }
                    item(key = "here") { Changes(stringResource(MR.strings.sync_preview_here), preview.here) }
                    item(key = "there") { Changes(stringResource(MR.strings.sync_preview_there), preview.there) }
                    if (!preview.fileExists) {
                        item(key = "new-file") {
                            Paragraph(stringResource(MR.strings.sync_preview_new_file), secondary = true)
                        }
                    }
                    item(key = "backup") { Paragraph(stringResource(MR.strings.sync_preview_backup), secondary = true) }
                    item(key = "confirm") {
                        Actions {
                            Button(onClick = onConfirm, enabled = !state.running) {
                                if (state.running) Spinner()
                                Text(
                                    stringResource(
                                        if (state.running) MR.strings.sync_running else MR.strings.sync_confirm,
                                    ),
                                )
                            }
                            OutlinedButton(onClick = onDisconnect, enabled = !state.running) {
                                Text(stringResource(MR.strings.action_cancel))
                            }
                        }
                    }
                    state.log.firstOrNull()?.takeIf { it.error != null }?.let { last ->
                        item(key = "first-failure") { LogRow(last) }
                    }
                }
                else -> item(key = "connect") {
                    Actions {
                        Button(onClick = onConnect, enabled = !state.busy) {
                            if (state.busy) Spinner()
                            Text(stringResource(MR.strings.sync_connect))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Changes(title: String, counts: List<Pair<SyncEntity, Int>>) {
    Column(Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (counts.isEmpty()) {
            Text(stringResource(MR.strings.sync_preview_nothing), style = MaterialTheme.typography.bodyMedium)
        }
        counts.forEach { (entity, count) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(entity.label), style = MaterialTheme.typography.bodyMedium)
                Text(count.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LogRow(entry: SyncLogEntry) {
    val reason = stringResource(
        when (entry.reason) {
            SyncReason.FIRST -> MR.strings.sync_reason_first
            SyncReason.MANUAL -> MR.strings.sync_reason_manual
            SyncReason.APP_OPEN -> MR.strings.sync_reason_app_open
            SyncReason.READING -> MR.strings.sync_reason_reading
        },
    )
    val result = when (entry.error) {
        SyncError.CONSENT -> stringResource(MR.strings.sync_error_consent)
        SyncError.NETWORK -> stringResource(MR.strings.sync_error_network)
        SyncError.DRIVE -> stringResource(MR.strings.sync_error_drive, entry.detail.orEmpty())
        SyncError.NEWER_FORMAT -> stringResource(MR.strings.sync_error_newer)
        SyncError.CONFLICT -> stringResource(MR.strings.sync_error_conflict)
        SyncError.BACKUP -> stringResource(MR.strings.sync_error_backup, entry.detail.orEmpty())
        null -> buildList {
            add(
                if (entry.here == 0 && entry.sent == 0) {
                    stringResource(MR.strings.sync_result_nothing)
                } else {
                    stringResource(MR.strings.sync_result_changes, entry.here, entry.sent)
                },
            )
            if (entry.pending > 0) add(stringResource(MR.strings.sync_result_pending, entry.pending))
        }.joinToString(" · ")
    }
    Column(Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall)) {
        Text("${formatDate(entry.at)} · $reason", style = MaterialTheme.typography.bodySmall)
        Text(
            result,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Paragraph(text: String, secondary: Boolean = false, error: Boolean = false) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            error -> MaterialTheme.colorScheme.error
            secondary -> MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.large,
            bottom = MaterialTheme.padding.small,
        ),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.padding(end = MaterialTheme.padding.small).size(16.dp),
        strokeWidth = 2.dp,
    )
}

private fun formatDate(at: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
).format(Date(at))

private val SyncEntity.label: StringResource
    get() = when (this) {
        SyncEntity.MANGA -> MR.strings.sync_entity_manga
        SyncEntity.CHAPTER -> MR.strings.sync_entity_chapter
        SyncEntity.HISTORY -> MR.strings.sync_entity_history
        SyncEntity.CATEGORY -> MR.strings.sync_entity_category
        SyncEntity.MANGA_CATEGORY -> MR.strings.sync_entity_manga_category
        SyncEntity.TRACK -> MR.strings.sync_entity_track
        SyncEntity.EXTENSION_STORE -> MR.strings.sync_entity_extension_store
        SyncEntity.SESSION -> MR.strings.sync_entity_session
        SyncEntity.CHAPTER_PROGRESS -> MR.strings.sync_entity_chapter_progress
        SyncEntity.STATS_META -> MR.strings.sync_entity_stats_meta
        SyncEntity.GOAL -> MR.strings.sync_entity_goal
        SyncEntity.COLLECTION -> MR.strings.sync_entity_collection
        SyncEntity.PLAN -> MR.strings.sync_entity_plan
        SyncEntity.METADATA -> MR.strings.sync_entity_metadata
        SyncEntity.FOLLOW -> MR.strings.sync_entity_follow
        SyncEntity.NOTIFIED -> MR.strings.sync_entity_notified
        SyncEntity.FEEDBACK -> MR.strings.sync_entity_feedback
    }
