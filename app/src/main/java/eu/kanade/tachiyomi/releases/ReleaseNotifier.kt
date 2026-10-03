package eu.kanade.tachiyomi.releases

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.buildNotificationChannel
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.i18n.MR

/**
 * The releases' notifications (docs/HOME_PLAN.md §8.3, H7), on a channel of their own, "Lançamentos",
 * that the reader can turn off in the system settings. Mihon's own channels are left as they are.
 */
@Inject
class ReleaseNotifier(
    private val context: Context,
) {

    /**
     * Whether the reader can be told anything: the app may notify (on Android 13 and later, only
     * with the permission) and the "Lançamentos" channel is not turned off.
     */
    fun enabled(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(CHANNEL) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** "Absolute Batman #24 saiu esta semana": tapped, the reader's entry, or the Início. */
    fun out(release: ReleaseItem, mangaId: Long?) {
        post(
            id = idOf(release, OUT),
            title = context.stringResource(MR.strings.releases_notification_out, title(release)),
            text = null,
            intent = mangaId?.let(::mangaIntent) ?: launchIntent(),
            action = null,
        )
    }

    /** "Absolute Batman #25 saiu · arco The Zoo": a new issue of an arc followed (H10). */
    fun outInArc(release: ReleaseItem, arcName: String, mangaId: Long?) {
        post(
            id = idOf(release, OUT),
            title = context.stringResource(MR.strings.releases_notification_out_arc, title(release), arcName),
            text = null,
            intent = mangaId?.let(::mangaIntent) ?: launchIntent(),
            action = null,
        )
    }

    /** "Absolute Batman #24 já está disponível", with "Ler agora" opening the chapter. */
    fun available(release: ReleaseItem, mangaId: Long, chapterId: Long, sourceName: String) {
        val read = readerIntent(mangaId, chapterId)
        post(
            id = idOf(release, AVAILABLE),
            title = context.stringResource(MR.strings.releases_notification_available, title(release)),
            text = context.stringResource(MR.strings.start_in_source, sourceName),
            intent = read,
            action = context.stringResource(MR.strings.releases_notification_read_now) to read,
        )
    }

    private fun post(
        id: Int,
        title: String,
        text: String?,
        intent: PendingIntent,
        action: Pair<String, PendingIntent>?,
    ) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            buildNotificationChannel(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT) {
                setName(context.stringResource(MR.strings.releases_channel))
            },
        )
        context.notify(
            id,
            context.notificationBuilder(CHANNEL) {
                setSmallIcon(R.drawable.ic_mihon)
                setContentTitle(title)
                text?.let { setContentText(it) }
                setContentIntent(intent)
                setAutoCancel(true)
                action?.let { (label, pending) -> addAction(0, label, pending) }
            }.build(),
        )
    }

    private fun readerIntent(mangaId: Long, chapterId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        chapterId.hashCode(),
        ReaderActivity.newIntent(context, mangaId, chapterId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The entry's page, the way Mihon's own notifications open it. */
    private fun mangaIntent(mangaId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        mangaId.hashCode(),
        Intent(context, MainActivity::class.java)
            .setAction(Constants.SHORTCUT_MANGA)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Constants.MANGA_EXTRA, mangaId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun title(release: ReleaseItem) = "${release.seriesName} #${release.number}"

    private fun idOf(release: ReleaseItem, what: Int) = (release.key + what).hashCode()

    companion object {
        const val CHANNEL = "start_releases"
        private const val OUT = 0
        private const val AVAILABLE = 1
    }
}
