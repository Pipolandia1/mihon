package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The averaging that replaced bilinear sampling. Amendment E6.
 *
 * Colours are written as 0xAARRGGBB and checked exactly: this is arithmetic, and the whole
 * reason it exists is that the cheaper arithmetic was wrong in a way nothing threw on.
 */
class AreaResampleTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun resample(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        val target = IntArray(targetWidth * targetHeight)
        AreaResample.resample(source, sourceWidth, sourceHeight, target, targetWidth, targetHeight)
        return target
    }

    @Test
    fun `a checkerboard halved is grey, not one of its corners`() {
        // The property the old path broke. Bilinear picks a neighbour, so it returns black or
        // white; averaging returns what the page actually looks like from further away, which
        // is what the model was trained on.
        val source = intArrayOf(grey(0), grey(255), grey(255), grey(0))

        val result = resample(source, 2, 2, 1, 1)

        assertEquals(grey(128), result.single(), "expected the mean of 0 and 255, rounded")
    }

    @Test
    fun `every source pixel of a block reaches the output`() {
        val source = intArrayOf(
            grey(0), grey(40), grey(80), grey(120),
            grey(40), grey(80), grey(120), grey(160),
            grey(80), grey(120), grey(160), grey(200),
            grey(120), grey(160), grey(200), grey(240),
        )

        val result = resample(source, 4, 4, 2, 2)

        assertEquals(grey(40), result[0], "mean of 0, 40, 40, 80")
        assertEquals(grey(120), result[1], "mean of 80, 120, 120, 160")
        assertEquals(grey(120), result[2])
        assertEquals(grey(200), result[3], "mean of 160, 200, 200, 240")
    }

    @Test
    fun `a ratio that is not a whole number weights the pixel it half covers`() {
        // Three into two: the first output covers pixel 0 whole and half of pixel 1.
        val source = intArrayOf(grey(0), grey(100), grey(200))

        val result = resample(source, 3, 1, 2, 1)

        assertEquals(grey(33), result[0], "(0*1 + 100*0.5) / 1.5")
        assertEquals(grey(167), result[1], "(100*0.5 + 200*1) / 1.5")
    }

    @Test
    fun `the same size comes back unchanged`() {
        val source = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt(), 0xFF708090.toInt(), 0xFFA0B0C0.toInt())

        val result = resample(source, 2, 2, 2, 2)

        assertEquals(source.toList(), result.toList())
    }

    @Test
    fun `colours do not bleed into each other`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()

        val result = resample(intArrayOf(red, red, blue, blue), 2, 2, 1, 2)

        assertEquals(red, result[0])
        assertEquals(blue, result[1])
    }

    @Test
    fun `a page only shrinks on one axis when only one axis shrinks`() {
        val source = intArrayOf(grey(0), grey(100), grey(200), grey(60))

        val result = resample(source, 2, 2, 1, 2)

        assertEquals(grey(50), result[0])
        assertEquals(grey(130), result[1])
    }

    @Test
    fun `enlarging repeats a pixel rather than throwing`() {
        // Documented, not desired: a page smaller than the input is already being shown detail
        // it does not have.
        val result = resample(intArrayOf(grey(0), grey(255)), 2, 1, 4, 1)

        assertEquals(listOf(grey(0), grey(0), grey(255), grey(255)), result.toList())
    }

    @Test
    fun `one pixel is a valid page`() {
        assertEquals(grey(77), resample(intArrayOf(grey(77)), 1, 1, 1, 1).single())
    }

    @Test
    fun `the alpha channel is always opaque, whatever came in`() {
        val transparent = 0x00FF8040
        assertEquals(0xFFFF8040.toInt(), resample(intArrayOf(transparent), 1, 1, 1, 1).single())
    }

    @Test
    fun `a target larger than needed is filled only where it should be`() {
        val target = IntArray(9) { 0x12345678 }

        AreaResample.resample(intArrayOf(grey(10), grey(20), grey(30), grey(40)), 2, 2, target, 2, 2)

        assertEquals(0x12345678, target[4], "nothing past the target's own pixels is touched")
    }
}
