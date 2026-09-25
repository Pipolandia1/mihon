package eu.kanade.tachiyomi.ui.reader.bubblezoom

import java.util.Locale

/**
 * One page's input to [BubbleOrderResolver] and the walk it made, as one line of text.
 *
 * Written so a wrong order seen on a real page can be copied from logcat into a test as it is:
 * `B`, `P` and `F` for balloons, panels and frames, each `x,y wxh` in fractions of the page, then
 * the walk as indices into that list. [parse] reads the list back. Always in [Locale.ROOT]: a
 * device in a language with decimal commas would otherwise write `0,126,0,432`.
 */
object BubbleOrderDump {

    fun of(input: List<BubbleDetection>, ordered: List<BubbleDetection>): String {
        val boxes = input.joinToString(" ; ") { "${letterOf(it.type)} ${shapeOf(it.box)}" }
        val walk = ordered.map(input::indexOf)
        return "[$boxes] walk=${walk.joinToString(",")}"
    }

    /** The boxes of a line [of] wrote, in the order it wrote them. */
    fun parse(line: String): List<BubbleDetection> {
        val inside = line.substringAfter('[').substringBefore(']')
        if (inside.isBlank()) return emptyList()
        return inside.split(';').map { part ->
            val (letter, at, size) = part.trim().split(' ')
            val (x, y) = at.split(',').map(String::toFloat)
            val (w, h) = size.split('x').map(String::toFloat)
            BubbleDetection(BubbleBox(x, y, w, h), confidence = 1f, type = typeOf(letter))
        }
    }

    private fun shapeOf(box: BubbleBox): String =
        String.format(Locale.ROOT, "%.4f,%.4f %.4fx%.4f", box.x, box.y, box.width, box.height)

    private fun letterOf(type: BubbleType): String = when (type) {
        BubbleType.PANEL -> "P"
        BubbleType.FRAME -> "F"
        else -> "B"
    }

    private fun typeOf(letter: String): BubbleType = when (letter) {
        "P" -> BubbleType.PANEL
        "F" -> BubbleType.FRAME
        else -> BubbleType.TEXT
    }
}
