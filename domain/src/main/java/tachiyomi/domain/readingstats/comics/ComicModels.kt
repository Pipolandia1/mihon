package tachiyomi.domain.readingstats.comics

/** What one chapter of an entry is, as a physical comic. Each entry's [id] is what the database stores. */
enum class ComicUnit(val id: Long) {
    ISSUE(0),

    /** Trade paperback, or a numbered volume collecting issues. */
    TPB(1),
    HARDCOVER(2),
    OMNIBUS(3),
    OTHER(4),
    ;

    /** Whether this collects issues rather than being one. */
    val collected: Boolean get() = this == TPB || this == HARDCOVER || this == OMNIBUS

    companion object {
        fun of(id: Long?): ComicUnit? = entries.firstOrNull { it.id == id }
        fun of(value: String?): ComicUnit? = entries.firstOrNull { it.name == value }
    }
}

/** A piece of comic metadata. Some describe the series, some a chapter; list fields hold several values. */
enum class ComicField(val id: Long, val list: Boolean = false) {
    /** The series default unit on a series; an exception on a chapter. */
    UNIT(0),
    SERIES_NAME(1),
    VOLUME(2),
    START_YEAR(3),
    END_YEAR(4),
    PUBLISHER(5),
    IMPRINT(6),
    UNIVERSE(7, list = true),
    GENRE(8, list = true),

    /** How many issues the series has in all, once that is known. */
    TOTAL_ISSUES(9),

    /** How a chapter is numbered, when its name does not say it well: "Annual 2025", "#1/2". */
    ISSUE_LABEL(10),

    /** Present on a variant of an issue, holding what makes it one ("Noir Edition"). */
    VARIANT(11),

    /** The issues a collected edition reprints. */
    CONTAINS_ISSUES(12, list = true),

    /** ISO date, "2025-01-15", or "2011-07" when only the month is known. */
    COVER_DATE(13),
    PAGE_COUNT(14),
    WRITER(15, list = true),
    ARTIST(16, list = true),
    COLORIST(17, list = true),
    LETTERER(18, list = true),
    COVER_ARTIST(19, list = true),
    EDITOR(20, list = true),
    CHARACTER(21, list = true),
    TEAM(22, list = true),
    ARC(23, list = true),

    /** The Metron id of the series, or of the issue a chapter was matched to. */
    METRON_ID(24),

    /** The Grand Comics Database id of the series, or of the issue a chapter was matched to. */
    GCD_ID(25),
    ;

    companion object {
        fun of(id: Long): ComicField? = entries.firstOrNull { it.id == id }
    }
}

/** Where a value came from. Each entry's [id] is what the database stores. */
enum class MetadataOrigin(val id: Long) {
    /** Typed by the user. */
    USER(0),
    METRON(1),
    COMIC_INFO(2),

    /** The entry's source: author, artist, genres. */
    SOURCE(3),

    /** Guessed from titles and chapter names. */
    INFERRED(4),

    /** The Grand Comics Database, asked when Metron cannot be. */
    GCD(5),
    ;

    companion object {
        fun of(id: Long): MetadataOrigin = entries.firstOrNull { it.id == id } ?: USER
    }
}

/** The values of one field, on the series ([chapterId] null) or on one chapter. */
data class MetadataValue(
    val field: ComicField,
    val chapterId: Long?,
    val values: List<String>,
    val origin: MetadataOrigin,
) {
    val value: String? get() = values.firstOrNull()
}

/**
 * An entry's comic metadata: what the user confirmed, which alone counts in statistics, and what
 * the sources suggest.
 */
data class ComicMetadata(
    val mangaId: Long,
    val confirmed: List<MetadataValue>,
    val suggested: List<MetadataValue>,
) {
    private val series = confirmed.filter { it.chapterId == null }.associateBy { it.field }
    private val chapters = confirmed.filter { it.chapterId != null }.groupBy { it.chapterId!! }
        .mapValues { (_, values) -> values.associateBy { it.field } }

    fun series(field: ComicField): MetadataValue? = series[field]

    fun chapter(chapterId: Long, field: ComicField): MetadataValue? = chapters[chapterId]?.get(field)

    /** The chapter's own value, else the series'. */
    fun effective(chapterId: Long, field: ComicField): MetadataValue? = chapter(chapterId, field) ?: series(field)

    /** Null when unknown: neither the chapter nor the series has a confirmed unit. */
    fun unitOf(chapterId: Long): ComicUnit? = ComicUnit.of(effective(chapterId, ComicField.UNIT)?.value)

    fun isVariant(chapterId: Long): Boolean = chapter(chapterId, ComicField.VARIANT) != null

    companion object {
        fun empty(mangaId: Long) = ComicMetadata(mangaId, emptyList(), emptyList())
    }
}
