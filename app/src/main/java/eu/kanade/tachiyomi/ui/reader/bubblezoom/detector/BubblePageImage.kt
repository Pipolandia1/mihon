package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import android.graphics.BitmapFactory
import okio.BufferedSource
import java.io.ByteArrayInputStream

/**
 * Takes a detector's-eye view of the page the reader is about to be shown.
 *
 * This is the one place that touches Android in the detection path, which is why it is a free
 * function here rather than a method on the session: everything above it stays JVM-testable.
 *
 * It must be called with the source the viewer is about to display, and before the viewer
 * displays it. Two reasons, and both have already bitten this feature.
 *
 * The bytes on disk are not the page on screen. `PagerPageHolder.process` may rotate a wide
 * page ninety degrees or cut a double spread in half before handing it over, and `InsertPage`
 * copies its parent's stream verbatim — so the right half of a split spread, asked for its own
 * bytes, hands back the whole spread. A detector fed from there returns boxes that are valid
 * fractions of the wrong rectangle, which is the worst kind of wrong because nothing throws.
 *
 * And okio invalidates a peek once the original source is read, which the image view does the
 * moment it is given the source. After that there is nothing left to peek at.
 *
 * The dimensions come from the bytes rather than from the image view. They agree — the view
 * reports the same post-transform size — but the view is not ready yet at this point, and
 * waiting for it would mean reconciling two facts that arrive in different places at different
 * times. `inJustDecodeBounds` reads a header and allocates no pixels.
 */
fun bubbleDetectionImage(source: BufferedSource): BubbleDetectionImage? {
    // Snapshot rather than hold the peek: the caller is about to hand `source` to the view,
    // which drains it. These are the encoded bytes, not a decoded page — the detector decodes
    // at whatever size it wants, which is what keeps a second full-resolution copy from ever
    // existing.
    val encoded = runCatching { source.peek().readByteArray() }.getOrNull() ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    return BubbleDetectionImage(bounds.outWidth, bounds.outHeight) {
        ByteArrayInputStream(encoded)
    }
}
