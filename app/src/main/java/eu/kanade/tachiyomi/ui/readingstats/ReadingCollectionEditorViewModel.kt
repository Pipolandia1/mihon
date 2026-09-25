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
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.IssueNumbers
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.domain.readingstats.plans.ReadingCollection
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.PlannableEntry
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import kotlin.time.Clock

/**
 * Creates a standalone collection, or edits the one with [collectionId]: an explicit order of whole
 * entries and single chapters, across series and sources.
 */
@AssistedInject
class ReadingCollectionEditorViewModel(
    @Assisted private val collectionId: Long?,
    private val repository: ReadingPlanRepository,
    private val metadataRepository: ComicMetadataRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(collectionId: Long?): ReadingCollectionEditorViewModel
    }

    private val mutableState = MutableStateFlow(State(loading = collectionId != null, isNew = collectionId == null))
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val events = Channel<Event>(Channel.UNLIMITED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    private var collection: ReadingCollection? = null

    init {
        if (collectionId != null) {
            viewModelScope.launchIO {
                val collection = repository.getCollection(collectionId)
                if (collection == null) {
                    events.send(Event.Closed)
                    return@launchIO
                }
                this@ReadingCollectionEditorViewModel.collection = collection
                val items = repository.getItems(collectionId)
                mutableState.update {
                    it.copy(loading = false, name = collection.name, kind = collection.kind, items = items)
                }
            }
        }
    }

    fun setName(name: String) = mutableState.update { it.copy(name = name) }

    fun setKind(kind: CollectionKind) = mutableState.update { it.copy(kind = kind) }

    fun setDialog(dialog: Dialog?) = mutableState.update { it.copy(dialog = dialog) }

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

    /** After picking an entry, its chapters: all of them adds the whole entry, later chapters included. */
    fun pickEntry(entry: PlannableEntry) {
        viewModelScope.launchIO {
            val metadata = metadataRepository.get(entry.mangaId)
            val chapters = getChaptersByMangaId.await(entry.mangaId, applyScanlatorFilter = true)
                .sortedWith(
                    compareBy(IssueNumbers.order) {
                        val label = metadata.chapter(it.id, ComicField.ISSUE_LABEL)?.value
                        IssueNumbers.parse(label ?: it.name, it.chapterNumber)
                    },
                )
            mutableState.update { it.copy(dialog = Dialog.PickChapters(entry, chapters, emptySet())) }
        }
    }

    fun setPickedChapters(selected: Set<Long>) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.PickChapters ?: return@update state
            state.copy(dialog = dialog.copy(selected = selected))
        }
    }

    fun confirmChapters() {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.PickChapters ?: return@update state
            val entry = dialog.entry
            val added = if (dialog.chapters.all { it.id in dialog.selected }) {
                listOf(CollectionItem(entry.mangaId, null, entry.sourceId, entry.url, null, entry.title))
            } else {
                dialog.chapters.filter { it.id in dialog.selected }.map { chapter ->
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
            state.withItems(state.items + added).copy(dialog = null)
        }
    }

    fun openArcPicker() {
        viewModelScope.launchIO {
            val arcs = metadataRepository.arcs()
            mutableState.update { it.copy(dialog = Dialog.PickArc(arcs)) }
        }
    }

    /** Appends the arc's chapters, by cover date, leaving out those already in the collection. */
    fun addArc(arc: String) {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launchIO {
            val chapters = metadataRepository.chaptersOfArc(arc)
            mutableState.update { state ->
                val present = state.items.mapNotNull { it.chapterId }.toSet()
                val name = state.name.ifBlank { arc }
                state.withItems(state.items + chapters.filterNot { it.chapterId in present }).copy(
                    name = name,
                    kind = if (state.isNew && state.items.isEmpty()) CollectionKind.ARC else state.kind,
                )
            }
        }
    }

    fun move(index: Int, by: Int) {
        mutableState.update { state ->
            val target = index + by
            if (index !in state.items.indices || target !in state.items.indices) return@update state
            val items = state.items.toMutableList()
            items.add(target, items.removeAt(index))
            state.withItems(items)
        }
    }

    fun remove(index: Int) {
        mutableState.update { state -> state.withItems(state.items.filterIndexed { i, _ -> i != index }) }
    }

    fun save() {
        val state = state.value
        if (!state.canSave) return
        viewModelScope.launchIO {
            val existing = collection
            if (existing == null) {
                repository.createCollection(
                    name = state.name.trim(),
                    kind = state.kind,
                    items = state.items,
                    now = Clock.System.now().toEpochMilliseconds(),
                )
            } else {
                repository.updateCollection(existing.copy(name = state.name.trim(), kind = state.kind), state.items)
            }
            events.send(Event.Closed)
        }
    }

    /** Starts a plan that reads this collection, then opens it to set its pace. */
    fun startPlan() {
        val existing = collection ?: return
        viewModelScope.launchIO {
            val now = Clock.System.now().toEpochMilliseconds()
            val planId = repository.createPlanFor(
                collection = existing,
                pacePerWeek = ReadingPlanEditorViewModel.DEFAULT_PACE,
                now = now,
                today = ReadingCalendar.dateOf(now, TimeZone.currentSystemDefault()),
            )
            events.send(Event.OpenPlan(planId))
        }
    }

    fun delete() {
        val existing = collection ?: return
        viewModelScope.launchIO {
            repository.deleteCollection(existing)
            events.send(Event.Closed)
        }
    }

    private fun State.withItems(items: List<CollectionItem>): State = copy(items = items)

    @Immutable
    data class State(
        val loading: Boolean = false,
        val isNew: Boolean = true,
        val name: String = "",
        val kind: CollectionKind = CollectionKind.READING_ORDER,
        /** In reading order. */
        val items: List<CollectionItem> = emptyList(),
        val dialog: Dialog? = null,
    ) {
        val canSave: Boolean get() = name.isNotBlank() && items.isNotEmpty()
    }

    sealed interface Dialog {
        data class PickEntry(val query: String, val results: List<PlannableEntry>) : Dialog
        data class PickChapters(
            val entry: PlannableEntry,
            /** In the series' order. */
            val chapters: List<Chapter>,
            val selected: Set<Long>,
        ) : Dialog
        data class PickArc(val arcs: List<String>) : Dialog
        data object ConfirmDelete : Dialog
    }

    sealed interface Event {
        data object Closed : Event
        data class OpenPlan(val planId: Long) : Event
    }

    companion object {
        /** The kinds a standalone collection can be; one entry alone is what a plan is for. */
        val KINDS = listOf(
            CollectionKind.READING_ORDER,
            CollectionKind.ARC,
            CollectionKind.EVENT,
            CollectionKind.RUN,
            CollectionKind.LIST,
        )
    }
}
