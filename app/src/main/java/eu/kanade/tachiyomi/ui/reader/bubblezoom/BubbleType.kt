package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * What a detected region is.
 *
 * [TEXT] and [UNKNOWN] are navigable: the reader steps through them. [PANEL] is structure,
 * used to group bubbles into reading order, and is never a navigation target itself.
 */
enum class BubbleType {
    TEXT,
    PANEL,
    UNKNOWN,
}
