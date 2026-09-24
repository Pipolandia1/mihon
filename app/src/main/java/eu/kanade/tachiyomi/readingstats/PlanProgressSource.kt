package eu.kanade.tachiyomi.readingstats

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.plans.PlanProgress
import tachiyomi.domain.readingstats.plans.PlanRules
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository

/** Every plan's progress, kept current as chapters are read, marked or added. */
@Inject
class PlanProgressSource(
    private val planRepository: ReadingPlanRepository,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(
        today: Flow<LocalDate>,
        recordingStartedOn: Flow<LocalDate>,
        firstDayOfWeek: DayOfWeek,
    ): Flow<List<PlanProgress>> {
        return combine(today, recordingStartedOn, planRepository.subscribePlans(), ::Triple)
            .flatMapLatest { (today, startedOn, plans) ->
                if (plans.isEmpty()) return@flatMapLatest flowOf(emptyList())
                val week = ReadingCalendar.weekOf(today, firstDayOfWeek)
                combine(
                    plans.map { plan ->
                        planRepository.subscribeCollectionChapters(plan.collectionId).map { chapters ->
                            PlanRules.progress(plan, chapters, today, week, startedOn)
                        }
                    },
                ) { it.toList() }
            }
    }
}
