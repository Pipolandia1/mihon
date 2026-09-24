package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * What a detected region is.
 *
 * [TEXT] and [UNKNOWN] are navigable: the reader steps through them. [PANEL] and [FRAME] are
 * structure, used to group bubbles into reading order, and are never a navigation target.
 */
enum class BubbleType {
    TEXT,

    /** A panel the page's gutters divide it into. Amendment E8. */
    PANEL,
    UNKNOWN,

    /**
     * A panel drawn as a frame over a page no gutter divides — an inset on a splash. Kept apart
     * from [PANEL] because it is read differently: it does not tile the page. Amendment E15.
     */
    FRAME,
    ;

    /** Structure rather than something to read: never a stop, never hit by a tap. */
    val isStructure: Boolean get() = this == PANEL || this == FRAME
}
