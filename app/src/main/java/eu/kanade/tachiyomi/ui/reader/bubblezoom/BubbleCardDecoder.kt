package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.BubbleDetectionImage
import java.io.InputStream

/**
 * The balloon's own pixels, at the resolution the page was delivered in.
 *
 * A card replayed from the image view's tiles is only as sharp as the tiles loaded for the fit
 * scale, which the reader degrades on purpose (`setMinimumTileDpi(180)`); at the 2.8× the card
 * asks for, that is blur. The page's encoded bytes are already in hand — the session keeps the
 * snapshot the holder took for detection, which is the displayed image after any split or
 * rotation — so the balloon is decoded from those, and only the balloon: a region decoder
 * touches the rectangle it is asked for and nothing else, for baseline JPEG and WebP at least.
 * PNG and progressive JPEG decode more than they return, which is why this is never called on
 * the main thread and why the caller cancels it when the card it was for is gone.
 *
 * The frame is checked, not assumed. Detection boxes are fractions of the image the bytes
 * describe; if the region decoder reports a different size — it should not, both read the same
 * header, but a mismatch here would be a card of the wrong part of the page — the decode is
 * refused and the placeholder stays.
 */
internal object BubbleCardDecoder {

    /** Blocking. Returns [region] of [image] at [sampleSize] as an ARGB bitmap, or null. */
    fun decode(image: BubbleDetectionImage, region: SourceRegion, sampleSize: Int): Bitmap? {
        val decoder = open(image) ?: return null
        try {
            return decode(decoder, region, sampleSize)
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Blocking. A region decoder over [image]'s bytes, or null when there is none or it reads a
     * different frame from the one [image] declares. The caller recycles it.
     *
     * Opened apart from [decode] for a caller with several regions of one page to read: the
     * detector's join test reads one per candidate pair, and opening the page again for each
     * cost it tens of milliseconds a pair on a JPEG the decoder cannot address by region.
     */
    fun open(image: BubbleDetectionImage): BitmapRegionDecoder? {
        val decoder = runCatching { image.open().use { newRegionDecoder(it) } }.getOrNull() ?: return null
        if (decoder.width != image.width || decoder.height != image.height) {
            decoder.recycle()
            return null
        }
        return decoder
    }

    /** Blocking. Returns [region] from [decoder] at [sampleSize] as an ARGB bitmap, or null. */
    fun decode(decoder: BitmapRegionDecoder, region: SourceRegion, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val rect = Rect(region.left, region.top, region.right, region.bottom)
        return runCatching { decoder.decodeRegion(rect, options) }.getOrNull()
    }

    private fun newRegionDecoder(stream: InputStream): BitmapRegionDecoder? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(stream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(stream, false)
        }
    }
}
