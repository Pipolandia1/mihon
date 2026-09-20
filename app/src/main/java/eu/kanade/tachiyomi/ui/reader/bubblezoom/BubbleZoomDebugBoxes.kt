package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * The boxes phase P3 drew and phases P4 to P16 walk, until a detector exists in P8.
 *
 * They are the exact coordinates of the bubbles on page 1 of the synthetic test manga, so any
 * misalignment on screen is measurable rather than a matter of opinion. See
 * tools/test-pages/ground-truth.json.
 *
 * Kept in a file of their own, with no Android import, so that the JVM tests can use the same
 * values the reader does instead of copies that can drift.
 */
val DEBUG_BOXES = listOf(
    BubbleBox(0.62f, 0.08f, 0.23f, 0.14f),
    BubbleBox(0.15f, 0.31f, 0.28f, 0.16f),
    BubbleBox(0.58f, 0.67f, 0.26f, 0.15f),
)

/** [DEBUG_BOXES] as detections, which is what the reader feeds the controller until P8. */
val DEBUG_DETECTIONS = DEBUG_BOXES.map {
    BubbleDetection(box = it, confidence = 1f, type = BubbleType.TEXT)
}
