package eu.kanade.tachiyomi.releases

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.AlertRepository
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind
import tachiyomi.domain.start.ReleaseItem

/**
 * Something that can be followed of a release, whether it is, and whether it was when shown.
 * [role]: a creator's, as Metron names it ("Writer", "Artist").
 */
data class FollowOption(val follow: Follow, val role: String?, val checked: Boolean, val wasChecked: Boolean = checked)

/** The bell of a release (docs/HOME_PLAN.md §8.3, H7): what can be followed, and following it. */
@Inject
@SingleIn(AppScope::class)
class ReleaseFollowing(
    private val context: Context,
    private val alerts: AlertRepository,
    private val metron: MetronApi,
) {

    val follows: Flow<List<Follow>> get() = alerts.subscribeFollows()

    /**
     * The issue alone and every issue of its series; with Metron's details of the issue, its writers
     * and artists and its characters too. A list that gives no details (the GCD's) offers the first two.
     */
    suspend fun options(item: ReleaseItem): List<FollowOption> {
        val now = System.currentTimeMillis()
        val followed = alerts.getFollows().map { it.kind to it.key }.toSet()
        val issue = Follow(
            kind = FollowKind.ISSUE,
            key = item.key,
            label = "${item.seriesName} #${item.number}",
            seriesName = item.seriesName,
            seriesYear = item.seriesYear,
            number = item.number,
            createdAt = now,
        )
        val series =
            Follow(FollowKind.SERIES, item.seriesKey, item.seriesName, item.seriesName, item.seriesYear, null, now)
        val options = mutableListOf<Pair<Follow, String?>>(issue to null, series to null)
        val detail = item.key.removePrefix(METRON_ISSUE).takeIf { item.key.startsWith(METRON_ISSUE) }?.toLongOrNull()
            ?.let { id -> detail(id) }
        options += detail?.credits.orEmpty()
            .mapNotNull { credit ->
                val id = credit.id ?: return@mapNotNull null
                val role = credit.role.firstOrNull { it.name in CREATOR_ROLES }?.name ?: return@mapNotNull null
                Follow(FollowKind.CREATOR, "metron:creator:$id", credit.creator, createdAt = now) to role
            }
            .distinctBy { it.first.key }
            .take(MAX_CREATORS)
        options += detail?.characters.orEmpty()
            .take(MAX_CHARACTERS)
            .map { Follow(FollowKind.CHARACTER, "metron:character:${it.id}", it.name, createdAt = now) to null }
        return options.map { (follow, role) -> FollowOption(follow, role, (follow.kind to follow.key) in followed) }
    }

    /** Follows what was checked and lets go of what was unchecked; the daily check follows along. */
    suspend fun save(options: List<FollowOption>) {
        options.forEach { option ->
            when {
                option.checked && !option.wasChecked -> alerts.follow(option.follow)
                !option.checked && option.wasChecked -> alerts.unfollow(option.follow.kind, option.follow.key)
            }
        }
        keepScheduled()
    }

    suspend fun unfollow(follow: Follow) {
        alerts.unfollow(follow.kind, follow.key)
        keepScheduled()
    }

    /** The daily check while something is followed, and none when nothing is. */
    suspend fun keepScheduled() {
        val following = alerts.getFollows().size
        if (following == 0) ReleaseAlertJob.cancel(context) else ReleaseAlertJob.schedule(context)
        logcat(LogPriority.INFO) {
            "StartFeed alerts: $following follows, daily check ${if (following == 0) "cancelled" else "scheduled"}"
        }
    }

    private suspend fun detail(id: Long) = try {
        withIOContext { metron.issueDetail(id) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "StartFeed alerts: no details of issue $id from Metron" }
        null
    }

    private companion object {
        const val METRON_ISSUE = "metron:issue:"
        const val MAX_CREATORS = 3
        const val MAX_CHARACTERS = 5

        /** The roles a reader follows a creator for; covers, letters and colours are not. */
        val CREATOR_ROLES = setOf("Writer", "Artist", "Penciller", "Penciler", "Story")
    }
}
