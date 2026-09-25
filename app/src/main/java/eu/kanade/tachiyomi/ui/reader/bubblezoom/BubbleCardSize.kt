package eu.kanade.tachiyomi.ui.reader.bubblezoom

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import kotlin.math.min

/**
 * How large the lifted card is, which the reader chooses. [DEFAULT] is the size amendments E11
 * and E14 calibrated against Google Play Books; the others scale it.
 *
 * The factor scales both terms of [BubbleCardGeometry.cardScale] together: the magnification
 * over what the page already shows, and the share of the screen a balloon too large for that is
 * held to. Scaling only the first would change nothing for wide balloons, which the ceiling
 * binds; scaling only the second would change nothing for small ones.
 */
enum class BubbleCardSize(val percent: Int, val label: StringResource) {
    SMALL(75, MR.strings.pref_bubble_zoom_card_size_small),
    DEFAULT(100, MR.strings.pref_bubble_zoom_card_size_default),
    LARGE(125, MR.strings.pref_bubble_zoom_card_size_large),
    EXTRA_LARGE(150, MR.strings.pref_bubble_zoom_card_size_extra_large),
    ;

    private val factor: Float get() = percent / PERCENT

    val magnification: Float get() = BubbleZoomConfig.CARD_MAGNIFICATION * factor

    val widthFraction: Float
        get() = min(BubbleZoomConfig.TARGET_WIDTH_FRACTION * factor, BubbleZoomConfig.CARD_MAX_SCREEN_FRACTION)

    val heightFraction: Float
        get() = min(BubbleZoomConfig.TARGET_HEIGHT_FRACTION * factor, BubbleZoomConfig.CARD_MAX_SCREEN_FRACTION)

    companion object {
        private const val PERCENT = 100f

        /** The size stored as [percent]; anything unknown is the default. */
        fun of(percent: Int): BubbleCardSize = entries.firstOrNull { it.percent == percent } ?: DEFAULT
    }
}
