package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class BubbleOrderDumpTest {

    private val balloon =
        BubbleDetection(BubbleBox(0.125f, 0.5f, 0.25f, 0.0625f), confidence = 0.9f, type = BubbleType.TEXT)
    private val panel = BubbleDetection(BubbleBox(0f, 0.25f, 1f, 0.5f), confidence = 1f, type = BubbleType.PANEL)
    private val frame = BubbleDetection(BubbleBox(0.5f, 0f, 0.5f, 0.25f), confidence = 1f, type = BubbleType.FRAME)

    @Test
    fun `a line reads back as the boxes it was written from`() {
        val input = listOf(balloon, panel, frame)
        val line = BubbleOrderDump.of(input, listOf(balloon))

        val read = BubbleOrderDump.parse(line)

        assertEquals(input.map { it.box }, read.map { it.box })
        assertEquals(listOf(BubbleType.TEXT, BubbleType.PANEL, BubbleType.FRAME), read.map { it.type })
        assertTrue(line.endsWith("walk=0"), line)
    }

    @Test
    fun `a device with decimal commas writes the same line`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            val line = BubbleOrderDump.of(listOf(balloon), listOf(balloon))

            assertEquals("[B 0.1250,0.5000 0.2500x0.0625] walk=0", line)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a page with nothing on it is an empty line`() {
        assertEquals("[] walk=", BubbleOrderDump.of(emptyList(), emptyList()))
        assertEquals(emptyList<BubbleDetection>(), BubbleOrderDump.parse("[] walk="))
    }
}
