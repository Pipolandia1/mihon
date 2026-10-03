package eu.kanade.tachiyomi.arcs

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.releases.ReleaseFollowing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import tachiyomi.domain.start.AlertRepository
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcContinue
import tachiyomi.domain.start.ArcIssue
import tachiyomi.domain.start.ArcNext
import tachiyomi.domain.start.ArcRepository
import tachiyomi.domain.start.ArcSeries
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind
import tachiyomi.domain.start.StartRepository
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Following an arc (docs/HOME_PLAN.md §13, H10): the bell of its page and its card, the alerts' check
 * for its new issues ([eu.kanade.tachiyomi.releases.ReleaseAlerts]), and the arc in "Continuar lendo"
 * at its next issue.
 */
@Inject
@SingleIn(AppScope::class)
class ArcFollowing(
    private val alerts: AlertRepository,
    private val following: ReleaseFollowing,
    private val repository: ArcRepository,
    private val catalog: ArcCatalog,
    private val reading: ArcReading,
    private val startRepository: StartRepository,
) {

    /** The arcs followed, by key. */
    val followed: Flow<Map<String, Follow>> = alerts.subscribeFollows()
        .map { follows -> follows.filter { it.kind == FollowKind.ARC }.associateBy { it.key } }
        .distinctUntilChanged()

    /** Where each followed arc's series are read from, worked out once a session: it may search the sources. */
    private val series = ConcurrentHashMap<String, Map<String, ArcSeries>>()

    /** Follows [arc], or lets go of it; the alerts' daily check follows along. */
    suspend fun toggle(arc: Arc) {
        val follow = alerts.getFollows().firstOrNull { it.kind == FollowKind.ARC && it.key == arc.key }
        if (follow != null) {
            following.unfollow(follow)
        } else {
            alerts.follow(Follow(FollowKind.ARC, arc.key, arc.name, createdAt = System.currentTimeMillis()))
            following.keepScheduled()
        }
    }

    /** The followed arcs with an issue to open next, again as reading moves them along. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continuing(): Flow<List<ArcNext>> = followed.flatMapLatest { follows ->
        if (follows.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(follows.values.map(::next)) { arcs -> arcs.filterNotNull() }
    }

    /** One followed arc at its next issue; nothing until its series are known, so the others never wait. */
    private fun next(follow: Follow): Flow<ArcNext?> = flow {
        emit(null)
        val (arc, issues) = repository.getArc(follow.key)
            ?: catalog.arc(follow.key)?.let { it.arc to it.issues }
            ?: return@flow
        val found = seriesOf(follow.key, issues)
        val ids = found.values.filterIsInstance<ArcSeries.Found>().map { it.mangaId }.toSet()
        emitAll(
            combine(reading.chapters(ids), startRepository.subscribeWorks()) { chapters, works ->
                ArcContinue.of(arc, issues, found, chapters, works, follow.createdAt, LocalDate.now())
            },
        )
    }

    private suspend fun seriesOf(key: String, issues: List<ArcIssue>): Map<String, ArcSeries> =
        series[key] ?: reading.series(issues).last().also { series[key] = it }
}
