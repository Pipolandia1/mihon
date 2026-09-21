package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import tachiyomi.core.common.preference.Preference

/**
 * Lets the fake and the real detector be compared on the same page without a rebuild.
 *
 * Read per call, not per session, so flipping the preference takes effect on the next page
 * rather than the next reader. The real detector is built lazily by its own first call, so
 * a reader that only ever uses the fake never opens a model.
 *
 * The reader sees one [BubbleDetector], as always. Which one answered is visible only in the
 * boxes it draws and the version it reports.
 */
class SwitchingBubbleDetector(
    private val useFake: Preference<Boolean>,
    private val fake: BubbleDetector,
    private val real: BubbleDetector,
) : BubbleDetector {

    private val current: BubbleDetector get() = if (useFake.get()) fake else real

    override val modelVersion: Int get() = current.modelVersion

    override val available: Boolean get() = current.available

    override suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection> = current.detect(image)

    override fun close() {
        fake.close()
        real.close()
    }
}
