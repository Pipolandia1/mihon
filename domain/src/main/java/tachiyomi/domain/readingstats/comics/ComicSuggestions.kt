package tachiyomi.domain.readingstats.comics

/** Picks, among what every source suggests, the one suggestion to show for each field. */
object ComicSuggestions {

    /** Which origin wins when several suggest the same field. */
    val PRIORITY = listOf(
        MetadataOrigin.METRON,
        MetadataOrigin.GCD,
        MetadataOrigin.COMIC_INFO,
        MetadataOrigin.SOURCE,
        MetadataOrigin.INFERRED,
    )

    /** The best suggestion for each field and scope, from the stored ones and those worked out now. */
    fun best(metadata: ComicMetadata, inferred: List<MetadataValue>): List<MetadataValue> =
        alternatives(metadata, inferred).values.map { it.first() }

    /** Every suggestion for each field and scope, best first; the same values twice are shown once. */
    fun alternatives(
        metadata: ComicMetadata,
        inferred: List<MetadataValue>,
    ): Map<Pair<Long?, ComicField>, List<MetadataValue>> =
        (metadata.suggested + inferred)
            .filter { it.values.isNotEmpty() }
            .groupBy { it.chapterId to it.field }
            .mapValues { (_, values) -> values.sortedBy(::rank).distinctBy { it.values } }

    /** Suggestions for fields the user has not confirmed yet: what "accept all" takes. */
    fun pending(metadata: ComicMetadata, inferred: List<MetadataValue>): List<MetadataValue> =
        best(metadata, inferred).filter { suggestion ->
            val confirmed = suggestion.chapterId
                ?.let { metadata.chapter(it, suggestion.field) }
                ?: metadata.series(suggestion.field).takeIf { suggestion.chapterId == null }
            confirmed == null
        }

    private fun rank(value: MetadataValue): Int =
        PRIORITY.indexOf(value.origin).takeIf { it >= 0 } ?: PRIORITY.size
}
