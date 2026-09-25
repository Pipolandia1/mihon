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
import eu.kanade.tachiyomi.readingstats.comics.ComicInfoReader
import eu.kanade.tachiyomi.readingstats.comics.online.ComicDatabaseException
import eu.kanade.tachiyomi.readingstats.comics.online.OnlineMetadata
import eu.kanade.tachiyomi.readingstats.comics.online.OnlineSearch
import eu.kanade.tachiyomi.readingstats.comics.online.SeriesCandidate
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicInference
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.ComicSuggestions
import tachiyomi.domain.readingstats.comics.IssueNumbers
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import java.io.IOException

/** Edits one entry's comic metadata: confirmed values, and suggestions to accept. */
@AssistedInject
class ComicMetadataViewModel(
    @Assisted private val mangaId: Long,
    private val repository: ComicMetadataRepository,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val comicInfoReader: ComicInfoReader,
    private val online: OnlineMetadata,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): ComicMetadataViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var entryChapters: List<Chapter> = emptyList()
    private var fetchJob: Job? = null

    init {
        viewModelScope.launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO
            entryChapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
            val inferred = ComicInference.suggest(
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                genres = manga.genre.orEmpty(),
                chapters = entryChapters.map { ChapterName(it.id, it.name, it.chapterNumber) },
            )
            mutableState.update {
                it.copy(title = manga.title, canReadComicInfo = comicInfoReader.canRead(manga), inferred = inferred)
            }
            repository.subscribe(mangaId)
                .catch { logcat(LogPriority.ERROR, it) }
                .collect { metadata -> mutableState.update { it.withMetadata(metadata) } }
        }
    }

    fun openChapter(chapterId: Long?) = mutableState.update { it.copy(openChapter = chapterId) }

    fun setDialog(dialog: Dialog?) = mutableState.update { it.copy(dialog = dialog) }

    fun editField(field: ComicField, chapterId: Long?) {
        val state = state.value
        val metadata = state.metadata ?: return
        val confirmed = if (chapterId == null) metadata.series(field) else metadata.chapter(chapterId, field)
        val alternatives = state.alternatives[chapterId to field].orEmpty()
        mutableState.update {
            it.copy(dialog = Dialog.EditField(field, chapterId, confirmed, alternatives))
        }
    }

    /** Confirms [values] for a field; an edit is the user's own, picking a suggestion keeps its origin. */
    fun confirm(field: ComicField, chapterId: Long?, values: List<String>, origin: MetadataOrigin) {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launchIO {
            val kept = values.map(String::trim).filter(String::isNotEmpty)
            if (kept.isEmpty()) {
                repository.remove(mangaId, field, chapterId)
            } else {
                repository.confirm(mangaId, listOf(MetadataValue(field, chapterId, kept, origin)))
            }
        }
    }

    fun acceptAll() {
        val pending = state.value.pending
        if (pending.isEmpty()) return
        viewModelScope.launchIO { repository.confirm(mangaId, pending) }
    }

    fun readComicInfo() {
        fetchJob?.cancel()
        mutableState.update { it.copy(fetch = Fetch.Reading) }
        fetchJob = viewModelScope.launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO
            val values = comicInfoReader.read(manga, entryChapters)
            repository.replaceSuggestions(mangaId, MetadataOrigin.COMIC_INFO, values)
            val fetch = if (values.isEmpty()) Fetch.Failed(Failure.NO_COMIC_INFO) else Fetch.ComicInfoDone(values.size)
            mutableState.update { it.copy(fetch = fetch) }
        }
    }

    /** Searches Metron, or the GCD in its place, for this entry's title. Sends the title and year only. */
    fun searchOnline() {
        val title = state.value.title ?: return
        fetchJob?.cancel()
        mutableState.update { it.copy(fetch = Fetch.Searching) }
        fetchJob = viewModelScope.launchIO {
            val result = runCatching { online.search(title) }
            ensureActive()
            mutableState.update { state ->
                result.fold(
                    onSuccess = { state.copy(fetch = Fetch.Found(it)) },
                    onFailure = { state.copy(fetch = Fetch.Failed(failureOf(it))) },
                )
            }
        }
    }

    fun pick(candidate: SeriesCandidate) {
        fetchJob?.cancel()
        mutableState.update { it.copy(fetch = Fetch.Fetching(candidate, 0, 0)) }
        fetchJob = viewModelScope.launchIO {
            val labels = confirmedLabels()
            val result = runCatching {
                online.suggestions(
                    candidate = candidate,
                    chapters = entryChapters.map { ChapterName(it.id, it.name, it.chapterNumber) },
                    labels = labels,
                    onProgress = { done, total ->
                        mutableState.update { it.copy(fetch = Fetch.Fetching(candidate, done, total)) }
                    },
                )
            }
            ensureActive()
            result.onSuccess { repository.replaceSuggestions(mangaId, candidate.origin, it.values) }
            mutableState.update { state ->
                result.fold(
                    onSuccess = { state.copy(fetch = Fetch.Fetched(candidate.origin, it.matched, it.fetched)) },
                    onFailure = { state.copy(fetch = Fetch.Failed(failureOf(it))) },
                )
            }
        }
    }

    fun dismissFetch() {
        fetchJob?.cancel()
        mutableState.update { it.copy(fetch = null) }
    }

    /** Chapters' confirmed issue labels, which pair them with issues better than their names. */
    private fun confirmedLabels(): Map<Long, String> {
        val metadata = state.value.metadata ?: return emptyMap()
        return entryChapters.mapNotNull { chapter ->
            metadata.chapter(chapter.id, ComicField.ISSUE_LABEL)?.value?.let { chapter.id to it }
        }.toMap()
    }

    private fun failureOf(error: Throwable): Failure {
        logcat(LogPriority.WARN, error) { "Comic metadata lookup failed" }
        return when (error) {
            is ComicDatabaseException.LimitReached -> Failure.LIMIT_REACHED
            is ComicDatabaseException.Unauthorized, is ComicDatabaseException.NoToken -> Failure.REFUSED
            is IOException -> Failure.NETWORK
            else -> Failure.OTHER
        }
    }

    private fun State.withMetadata(metadata: ComicMetadata): State {
        val sorted = entryChapters.sortedWith(
            compareBy(IssueNumbers.order) { c ->
                IssueNumbers.parse(metadata.chapter(c.id, ComicField.ISSUE_LABEL)?.value ?: c.name, c.chapterNumber)
            },
        )
        return copy(
            loading = false,
            metadata = metadata,
            chapters = sorted.map { ChapterRow(it.id, it.name, it.read) },
            alternatives = ComicSuggestions.alternatives(metadata, inferred),
            pending = ComicSuggestions.pending(metadata, inferred),
        )
    }

    @Immutable
    data class ChapterRow(val id: Long, val name: String, val read: Boolean)

    @Immutable
    data class State(
        val loading: Boolean = true,
        val title: String? = null,
        val canReadComicInfo: Boolean = false,
        val metadata: ComicMetadata? = null,
        val inferred: List<MetadataValue> = emptyList(),
        /** In the series' order. */
        val chapters: List<ChapterRow> = emptyList(),
        val alternatives: Map<Pair<Long?, ComicField>, List<MetadataValue>> = emptyMap(),
        /** Best suggestions for fields not confirmed yet. */
        val pending: List<MetadataValue> = emptyList(),
        /** The chapter whose fields are shown, or null for the series. */
        val openChapter: Long? = null,
        val dialog: Dialog? = null,
        val fetch: Fetch? = null,
    ) {
        fun best(field: ComicField, chapterId: Long?): MetadataValue? = alternatives[chapterId to field]?.firstOrNull()
    }

    sealed interface Dialog {
        data class EditField(
            val field: ComicField,
            val chapterId: Long?,
            val confirmed: MetadataValue?,
            val alternatives: List<MetadataValue>,
        ) : Dialog
    }

    enum class Failure { NO_COMIC_INFO, LIMIT_REACHED, REFUSED, NETWORK, OTHER }

    sealed interface Fetch {
        data object Reading : Fetch
        data class ComicInfoDone(val values: Int) : Fetch
        data object Searching : Fetch
        data class Found(val search: OnlineSearch) : Fetch
        data class Fetching(val candidate: SeriesCandidate, val done: Int, val total: Int) : Fetch
        data class Fetched(val origin: MetadataOrigin, val matched: Int, val fetched: Int) : Fetch
        data class Failed(val failure: Failure) : Fetch
    }
}
