package tachiyomi.domain.readingstats.comics

import tachiyomi.domain.manga.model.MangaCover

data class LibraryChapter(
    val id: Long,
    val read: Boolean,
    val name: String,
    val number: Double,
    /** When Mihon first saw the chapter, epoch ms. */
    val dateFetch: Long,
)

data class LibraryEntry(
    val mangaId: Long,
    val title: String,
    val cover: MangaCover?,
    /** The source says publication has ended, so the chapters it lists are all there will be. */
    val publicationFinished: Boolean,
    val dateAdded: Long,
    /** The last time anything of it was read, epoch ms; null if never. */
    val lastReadAt: Long?,
    val chapters: List<LibraryChapter>,
    val metadata: ComicMetadata,
)

enum class LibraryStatus {
    NOT_STARTED,
    IN_PROGRESS,

    /** Everything available is read, but how much more there will be is unknown. */
    CAUGHT_UP,
    COMPLETED,
}

data class EntryProgress(
    val entry: LibraryEntry,
    val status: LibraryStatus,
    val done: Int,
    /** Null when unknown: then no percentage and no "left" is shown. */
    val total: Int?,
    /** Units available to read, variants aside. */
    val available: Int,
    /** The first unit left to read, in the series' order. */
    val next: LibraryChapter?,
) {
    val remaining: Int? get() = total?.let { (it - done).coerceAtLeast(0) }
}

/** Chapters counted by what they are. Variants are never counted as issues of their own. */
data class UnitCounts(
    val issues: Int = 0,
    val collected: Int = 0,
    val other: Int = 0,
    val variants: Int = 0,
    /** Not classified: the unit is unknown. */
    val unknown: Int = 0,
) {
    val total: Int get() = issues + collected + other + variants + unknown

    operator fun plus(other: UnitCounts) = UnitCounts(
        issues + other.issues,
        collected + other.collected,
        this.other + other.other,
        variants + other.variants,
        unknown + other.unknown,
    )
}

data class PeriodChange(
    val entriesAdded: Int,
    val chaptersAdded: UnitCounts,
    val chaptersCompleted: UnitCounts,
)

/** Library state, backlog and what to read next, counted by unit and never guessing a total. */
object ComicLibrary {

    const val NEAR_FINISH_MAX = 3
    const val STALLED_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun parsed(chapter: LibraryChapter, metadata: ComicMetadata): ParsedIssue {
        val label = metadata.chapter(chapter.id, ComicField.ISSUE_LABEL)?.value
        return IssueNumbers.parse(label ?: chapter.name, chapter.number)
    }

    fun unitCounts(chapters: Collection<LibraryChapter>, metadata: ComicMetadata): UnitCounts =
        chapters.fold(UnitCounts()) { counts, chapter ->
            counts + when {
                metadata.isVariant(chapter.id) -> UnitCounts(variants = 1)
                else -> when (metadata.unitOf(chapter.id)) {
                    null -> UnitCounts(unknown = 1)
                    ComicUnit.ISSUE -> UnitCounts(issues = 1)
                    ComicUnit.OTHER -> UnitCounts(other = 1)
                    else -> UnitCounts(collected = 1)
                }
            }
        }

    /**
     * How far along an entry is. With a confirmed issue total, progress counts distinct issues read
     * against it, so a TPB or a variant is not an issue; when the source says publication ended,
     * against the chapters it lists; otherwise there is no total.
     */
    fun progress(entry: LibraryEntry): EntryProgress {
        val metadata = entry.metadata
        val units = entry.chapters.filterNot { metadata.isVariant(it.id) }
        val issueTotal = metadata.series(ComicField.TOTAL_ISSUES)?.value?.toIntOrNull()
        val counted = if (issueTotal != null) {
            units.filter { metadata.unitOf(it.id)?.collected != true && metadata.unitOf(it.id) != ComicUnit.OTHER }
        } else {
            units
        }
        val done = if (issueTotal != null) {
            counted.filter { it.read }.map { parsed(it, metadata).let { issue -> issue.kind to issue.number } }
                .distinct().size
        } else {
            counted.count { it.read }
        }
        val total = issueTotal ?: counted.size.takeIf { entry.publicationFinished }
        val next = counted.filterNot { it.read }.minWithOrNull(compareBy(IssueNumbers.order) { parsed(it, metadata) })
        val status = when {
            done == 0 -> LibraryStatus.NOT_STARTED
            total != null && done >= total -> LibraryStatus.COMPLETED
            total == null && next == null -> LibraryStatus.CAUGHT_UP
            else -> LibraryStatus.IN_PROGRESS
        }
        return EntryProgress(entry, status, done, total, counted.size, next)
    }

    /** What is available and not read yet, by unit. */
    fun backlog(entries: List<LibraryEntry>): UnitCounts =
        entries.fold(UnitCounts()) { counts, entry ->
            counts + unitCounts(entry.chapters.filterNot { it.read }, entry.metadata)
        }

    /** Entries with a known total and only a few units left, the closest first. */
    fun nearFinish(progress: List<EntryProgress>, maxRemaining: Int = NEAR_FINISH_MAX): List<EntryProgress> =
        progress
            .filter { it.done > 0 && (it.remaining ?: 0) in 1..maxRemaining }
            .sortedWith(compareBy({ it.remaining }, { it.entry.title }))

    /** Entries in progress, with something to read, untouched for [days] days; the longest first. */
    fun stalled(progress: List<EntryProgress>, nowMs: Long, days: Int = STALLED_DAYS): List<EntryProgress> =
        progress
            .filter { it.status == LibraryStatus.IN_PROGRESS && it.next != null }
            .filter { (it.entry.lastReadAt ?: return@filter false) <= nowMs - days * DAY_MS }
            .sortedBy { it.entry.lastReadAt }

    /** Days since [lastReadAt], whole. */
    fun daysSince(lastReadAt: Long, nowMs: Long): Long = (nowMs - lastReadAt) / DAY_MS

    /**
     * What came in and what was finished between [fromMs] and [toMs] (end excluded): entries added,
     * chapters Mihon first saw, and chapters [completed] (their ids), each by unit.
     */
    fun change(entries: List<LibraryEntry>, fromMs: Long, toMs: Long, completed: Set<Long>): PeriodChange {
        val range = fromMs until toMs
        return PeriodChange(
            entriesAdded = entries.count { it.dateAdded in range },
            chaptersAdded = entries.fold(UnitCounts()) { counts, entry ->
                counts + unitCounts(entry.chapters.filter { it.dateFetch in range }, entry.metadata)
            },
            chaptersCompleted = entries.fold(UnitCounts()) { counts, entry ->
                counts + unitCounts(entry.chapters.filter { it.id in completed }, entry.metadata)
            },
        )
    }
}
