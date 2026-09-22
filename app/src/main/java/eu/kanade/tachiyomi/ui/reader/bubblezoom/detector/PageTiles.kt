package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig

/**
 * Which rectangles of a page one inference each is run on, and how their answers are put back
 * together. Amendment E5.
 *
 * A page normally gets one look: it goes into the model's square input whole. A **double-page
 * spread does not survive that**, and the measurement is not close. The same weights on the
 * same spread, a landscape 2000x1537 from a real comic:
 *
 * | what the model was shown | balloons found, of six |
 * |---|---|
 * | the whole spread at 640 | **0** |
 * | the whole spread at 1024 | 2 |
 * | two overlapping halves, 640 each | **5** |
 *
 * The reason is not the letterbox — a portrait page wastes more of the square than a landscape
 * one does — it is that a spread carries two pages' worth of drawing, so every balloon on it is
 * half the size the model expects. Splitting it puts one page's worth in front of the model at
 * a time, which is the size it was trained on.
 *
 * Splitting is therefore for spreads only, and "spread" means wider than tall: in a comic read
 * as portrait pages, a landscape image is two pages printed across the fold. Portrait pages are
 * deliberately left whole even though they are further from square, because the same experiment
 * on a dense portrait page moved 13 balloons to 14 for twice the inference — not worth a second
 * pass on every page of every chapter.
 *
 * Pure Kotlin. Everything here is fractions of the page, so it can be checked on the JVM.
 */
object PageTiles {

    /**
     * A rectangle of the page, as fractions of it, that one inference looks at.
     *
     * The same shape as [BubbleBox] and deliberately not the same type: a box is something
     * found on a page, and this is a way of looking at one.
     */
    data class Tile(val x: Float, val y: Float, val width: Float, val height: Float) {

        /** Whether this is the whole page, which is the case that must cost nothing extra. */
        val isWholePage: Boolean get() = x == 0f && y == 0f && width == 1f && height == 1f

        /**
         * A box found inside this tile, expressed as a fraction of the whole page.
         *
         * Null when nothing survives the page's edges, which [BubbleBox.clampedOrNull] decides
         * — the same door every other outside number comes through.
         */
        fun place(box: BubbleBox): BubbleBox? = BubbleBox.clampedOrNull(
            x = x + box.x * width,
            y = y + box.y * height,
            width = box.width * width,
            height = box.height * height,
        )
    }

    private val WHOLE_PAGE = Tile(0f, 0f, 1f, 1f)

    /** The looks a page of these dimensions gets. Never empty; one entry for an ordinary page. */
    fun of(width: Int, height: Int): List<Tile> {
        if (width <= 0 || height <= 0) return listOf(WHOLE_PAGE)
        if (width.toFloat() / height.toFloat() < BubbleDetectionConfig.SPREAD_ASPECT) {
            return listOf(WHOLE_PAGE)
        }
        // Overlapping, so a balloon sitting on the fold is whole in at least one of the two.
        val span = (0.5f + BubbleDetectionConfig.SPREAD_TILE_OVERLAP).coerceAtMost(1f)
        return listOf(
            Tile(0f, 0f, span, 1f),
            Tile(1f - span, 0f, span, 1f),
        )
    }

    /**
     * One list from several tiles' answers, with each balloon kept once.
     *
     * Only for use across tiles: within one the model's own suppression has already run, and
     * this is looser than that on purpose. Two tiles that share a strip of page see the
     * balloons in it twice, and not always the same way — a balloon the seam cuts comes back
     * whole from one tile and clipped from the other, and two such boxes can overlap far too
     * little for an IoU test to call them the same thing. So containment decides that case:
     * a box that is almost entirely inside another is that other one, seen worse.
     *
     * Ties go to confidence, which for the clipped-versus-whole pair is also what picks the
     * whole one.
     */
    fun merge(found: List<BubbleDetection>): List<BubbleDetection> {
        if (found.size < 2) return found
        val kept = ArrayList<BubbleDetection>(found.size)
        for (candidate in found.sortedByDescending { it.confidence }) {
            if (kept.none { isSameBalloon(it.box, candidate.box) }) kept += candidate
        }
        return kept
    }

    private fun isSameBalloon(a: BubbleBox, b: BubbleBox): Boolean {
        if (a.intersectionOverUnion(b) >= BubbleDetectionConfig.TILE_MERGE_IOU) return true
        val overlapX = minOf(a.right, b.right) - maxOf(a.x, b.x)
        if (overlapX <= 0f) return false
        val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.y, b.y)
        if (overlapY <= 0f) return false
        val smaller = minOf(a.area, b.area)
        if (smaller <= 0f) return false
        return overlapX * overlapY / smaller >= BubbleDetectionConfig.TILE_MERGE_CONTAINMENT
    }
}
