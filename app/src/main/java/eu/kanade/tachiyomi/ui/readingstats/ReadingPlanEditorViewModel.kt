package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.domain.readingstats.plans.ReadingPlan
import tachiyomi.domain.readingstats.repository.PlannableEntry
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import kotlin.time.Clock

/** Creates a plan, or edits the one with [planId]. */
@AssistedInject
class ReadingPlanEditorViewModel(
    @Assisted private val planId: Long?,
    private val repository: ReadingPlanRepository,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(planId: Long?): ReadingPlanEditorViewModel
    }

    private val mutableState = MutableStateFlow(State(loading = planId != null, isNew = planId == null))
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val events = Channel<Event>(Channel.UNLIMITED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    private var plan: ReadingPlan? = null

    init {
        if (planId != null) viewModelScope.launchIO { load(planId) }
    }

    private suspend fun load(planId: Long) {
        val plan = repository.getPlan(planId)
        if (plan == null) {
            events.send(Event.Closed)
            return
        }
        this.plan = plan
        // Items of one entry become one row: the whole entry, or the chapters picked from it.
        val entries = repository.getItems(plan.collectionId)
            .mapNotNull { item -> item.mangaId?.let { it to item } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (mangaId, items) ->
                val manga = getManga.await(mangaId) ?: return@mapNotNull null
                Entry(
                    mangaId = mangaId,
                    sourceId = manga.source,
                    url = manga.url,
                    title = manga.title,
                    cover = manga.asMangaCover(),
                    chapters = chaptersOf(mangaId),
                    selected = if (items.any { it.wholeEntry }) null else items.mapNotNull { it.chapterId }.toSet(),
                )
            }
        mutableState.update {
            it.copy(
                loading = false,
                name = plan.name,
                nameEdited = true,
                pace = plan.pacePerWeek.toString(),
                paused = plan.paused,
                entries = entries,
            )
        }
    }

    fun setName(name: String) = mutableState.update { it.copy(name = name, nameEdited = true) }

    fun setPace(pace: String) = mutableState.update { it.copy(pace = pace.filter(Char::isDigit).take(MAX_PACE_DIGITS)) }

    fun setPaused(paused: Boolean) = mutableState.update { it.copy(paused = paused) }

    fun openEntryPicker() {
        mutableState.update { it.copy(dialog = Dialog.PickEntry("", emptyList())) }
        search("")
    }

    fun search(query: String) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.PickEntry ?: return@update state
            state.copy(dialog = dialog.copy(query = query))
        }
        viewModelScope.launchIO {
            val results = repository.searchLibrary(query)
            mutableState.update { state ->
                val dialog = state.dialog as? Dialog.PickEntry ?: return@update state
                if (dialog.query != query) state else state.copy(dialog = dialog.copy(results = results))
            }
        }
    }

    fun addEntry(entry: PlannableEntry) {
        mutableState.update { it.copy(dialog = null) }
        if (state.value.entries.any { it.mangaId == entry.mangaId }) return
        viewModelScope.launchIO {
            val added = Entry(
                mangaId = entry.mangaId,
                sourceId = entry.sourceId,
                url = entry.url,
                title = entry.title,
                cover = entry.cover,
                chapters = chaptersOf(entry.mangaId),
                selected = null,
            )
            mutableState.update { it.withEntries(it.entries + added) }
        }
    }

    fun removeEntry(mangaId: Long) {
        mutableState.update { state -> state.withEntries(state.entries.filterNot { it.mangaId == mangaId }) }
    }

    fun openChapterPicker(mangaId: Long) {
        val entry = state.value.entries.firstOrNull { it.mangaId == mangaId } ?: return
        mutableState.update {
            it.copy(dialog = Dialog.PickChapters(mangaId, entry.selected ?: entry.chapters.map(Chapter::id).toSet()))
        }
    }

    fun setPickedChapters(selected: Set<Long>) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.PickChapters ?: return@update state
            state.copy(dialog = dialog.copy(selected = selected))
        }
    }

    /** Applies the picked chapters; picking every chapter means the whole entry, later ones included. */
    fun confirmChapters() {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.PickChapters ?: return@update state
            val entries = state.entries.map { entry ->
                if (entry.mangaId != dialog.mangaId) return@map entry
                val all = entry.chapters.all { it.id in dialog.selected }
                entry.copy(selected = if (all) null else dialog.selected)
            }
            state.withEntries(entries).copy(dialog = null)
        }
    }

    fun setDialog(dialog: Dialog?) = mutableState.update { it.copy(dialog = dialog) }

    fun save() {
        val state = state.value
        val pace = state.pace.toIntOrNull()?.takeIf { it > 0 } ?: return
        val items = state.entries.flatMap(::itemsOf)
        if (items.isEmpty()) return
        val name = state.name.trim().ifEmpty { autoName(state.entries) }
        viewModelScope.launchIO {
            val existing = plan
            if (existing == null) {
                val now = Clock.System.now().toEpochMilliseconds()
                val single = state.entries.size == 1 && state.entries[0].selected == null
                repository.createPlan(
                    name = name,
                    kind = if (single) CollectionKind.ENTRY else CollectionKind.LIST,
                    items = items,
                    pacePerWeek = pace,
                    now = now,
                    today = ReadingCalendar.dateOf(now, TimeZone.currentSystemDefault()),
                )
            } else {
                repository.updatePlan(existing.copy(name = name, pacePerWeek = pace, paused = state.paused), items)
            }
            events.send(Event.Closed)
        }
    }

    fun delete() {
        val existing = plan ?: return
        viewModelScope.launchIO {
            repository.deletePlan(existing)
            events.send(Event.Closed)
        }
    }

    private suspend fun chaptersOf(mangaId: Long): List<Chapter> =
        getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
            .sortedWith(compareBy({ it.chapterNumber }, { -it.sourceOrder }))

    private fun itemsOf(entry: Entry): List<CollectionItem> {
        val selected = entry.selected
            ?: return listOf(CollectionItem(entry.mangaId, null, entry.sourceId, entry.url, null, entry.title))
        return entry.chapters.filter { it.id in selected }.map { chapter ->
            CollectionItem(
                entry.mangaId,
                chapter.id,
                entry.sourceId,
                entry.url,
                chapter.url,
                "${entry.title} · ${chapter.name}",
            )
        }
    }

    private fun State.withEntries(entries: List<Entry>): State =
        copy(entries = entries, name = if (nameEdited) name else autoName(entries))

    private fun autoName(entries: List<Entry>): String = entries.joinToString(" + ") { it.title }

    @Immutable
    data class Entry(
        val mangaId: Long,
        val sourceId: Long,
        val url: String,
        val title: String,
        val cover: MangaCover?,
        /** In reading order. */
        val chapters: List<Chapter>,
        /** The chapters picked; null for the whole entry, chapters added later included. */
        val selected: Set<Long>?,
    )

    @Immutable
    data class State(
        val loading: Boolean = false,
        val isNew: Boolean = true,
        val name: String = "",
        val nameEdited: Boolean = false,
        val pace: String = DEFAULT_PACE.toString(),
        val paused: Boolean = false,
        val entries: List<Entry> = emptyList(),
        val dialog: Dialog? = null,
    ) {
        val canSave: Boolean
            get() = (pace.toIntOrNull() ?: 0) > 0 && entries.any { it.selected == null || it.selected.isNotEmpty() }
    }

    sealed interface Dialog {
        data class PickEntry(val query: String, val results: List<PlannableEntry>) : Dialog
        data class PickChapters(val mangaId: Long, val selected: Set<Long>) : Dialog
        data object ConfirmDelete : Dialog
    }

    sealed interface Event {
        data object Closed : Event
    }

    companion object {
        /** Five a week, the example the plan's brief gives. */
        const val DEFAULT_PACE = 5
        private const val MAX_PACE_DIGITS = 3
    }
}
