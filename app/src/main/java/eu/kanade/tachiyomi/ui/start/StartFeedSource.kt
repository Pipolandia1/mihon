package eu.kanade.tachiyomi.ui.start

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.readingstats.ContinueItem
import eu.kanade.tachiyomi.readingstats.ReadingDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toJavaLocalDate
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.plans.PlanChapter
import tachiyomi.domain.readingstats.plans.ReadingCollection
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import tachiyomi.domain.start.CatalogAnswer
import tachiyomi.domain.start.CollectionState
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.OutsideWork
import tachiyomi.domain.start.ReadingTime
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseRepository
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.StartFeed
import tachiyomi.domain.start.StartFeedResult
import tachiyomi.domain.start.StartInput
import tachiyomi.domain.start.StartRepository
import tachiyomi.domain.start.WorkFacts
import kotlin.time.Clock

private data class Extras(
    val today: LocalDate,
    val hideStalled: Boolean,
    val works: List<WorkFacts>,
    val feedback: List<Feedback>,
    val network: Network,
)

/** What arrives from the catalogue and the sources, later than the rest (H5, H6). */
private data class Network(
    val outside: List<OutsideWork>,
    val answers: List<CatalogAnswer>,
    val releases: List<ReleaseItem>,
)

/** Everything the Início's blocks are built from, kept current as reading and the library change. */
@Inject
class StartFeedSource(
    private val metadata: ComicMetadataRepository,
    private val stats: ReadingStatsRepository,
    private val plans: ReadingPlanRepository,
    private val readingDays: ReadingDays,
    private val statsPreferences: ReadingStatsPreferences,
    private val startRepository: StartRepository,
    private val releases: ReleaseRepository,
) {

    /**
     * The blocks for [continueReading], the works "Continuar lendo" shows; [outside], the
     * catalogue's series found in the sources, and [answers], what it listed for the names the reader
     * reads (H5), arrive later, as the week's releases (H6) do.
     */
    fun subscribe(
        continueReading: Flow<List<ContinueItem>>,
        outside: Flow<List<OutsideWork>>,
        answers: Flow<List<CatalogAnswer>>,
    ): Flow<StartFeedResult> =
        combine(
            continueReading,
            metadata.subscribeLibrary().map { entries -> entries.map(ComicLibrary::progress) },
            collections(),
            perIssueMs(),
            combine(
                readingDays.today,
                statsPreferences.hideStalledReminders.changes(),
                startRepository.subscribeWorks(),
                startRepository.subscribeFeedback(),
                combine(outside, answers, thisWeek(), ::Network),
                ::Extras,
            ),
        ) { continueItems, progress, collections, perIssue, (today, hideStalled, works, feedback, network) ->
            StartFeed.build(
                StartInput(
                    continueIds = continueItems.map { it.mangaId },
                    progress = progress,
                    collections = collections,
                    perIssueMs = perIssue,
                    nowMs = Clock.System.now().toEpochMilliseconds(),
                    daySeed = today.toEpochDays(),
                    hideStalled = hideStalled,
                    works = works,
                    feedback = feedback,
                    outside = network.outside,
                    releases = network.releases,
                    catalogAnswers = network.answers,
                ),
            )
        }
            .catch { logcat(LogPriority.ERROR, it) }
            .flowOn(Dispatchers.IO)

    /** This week's releases as kept on the device; a new week follows the day. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun thisWeek(): Flow<List<ReleaseItem>> = readingDays.today
        .map { ReleaseWeek.of(it.toJavaLocalDate()) }
        .distinctUntilChanged()
        .flatMapLatest { week -> releases.subscribeWeek(week) }
        .map { it?.items.orEmpty() }

    /** The measured time per issue, recomputed when a day's reading changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun perIssueMs(): Flow<Long?> = combine(readingDays.today, stats.subscribeAllDays(), ::Pair)
        .mapLatest { (today, _) ->
            val from = today.minus(ReadingTime.WINDOW_DAYS - 1, DateTimeUnit.DAY)
            ReadingTime.perIssueMs(stats.getReaderCompletionTimes(from, today))
        }
        .distinctUntilChanged()

    /** The Stats collections with what is left of each. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun collections(): Flow<List<CollectionState>> = plans.subscribeCollections()
        .flatMapLatest { collections ->
            if (collections.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                collections.map { collection ->
                    plans.subscribeCollectionChapters(collection.id).map { stateOf(collection, it) }
                },
            ) { it.toList() }
        }

    private fun stateOf(collection: ReadingCollection, chapters: List<PlanChapter>): CollectionState {
        val firstUnread = chapters.firstOrNull { !it.read }
        return CollectionState(
            id = collection.id,
            name = collection.name,
            kind = collection.kind,
            total = chapters.size,
            unread = chapters.count { !it.read },
            firstUnread = firstUnread?.let { it.mangaId to it.chapterId },
        )
    }
}
