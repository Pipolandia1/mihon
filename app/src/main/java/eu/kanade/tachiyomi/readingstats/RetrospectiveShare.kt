package eu.kanade.tachiyomi.readingstats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

/**
 * Shares a retrospective the user asked to share: as text, or as an image drawn on the device.
 * Nothing is uploaded; the system share sheet decides where it goes.
 */
object RetrospectiveShare {

    private const val WIDTH = 1080
    private const val MARGIN = 72f
    private const val TITLE_SIZE = 64f
    private const val HEADING_SIZE = 36f
    private const val BODY_SIZE = 40f
    private const val FOOTER_SIZE = 28f
    private const val GAP = 28f
    private const val BACKGROUND = 0xFF15171C.toInt()
    private const val ACCENT = 0xFFB4C5FF.toInt()
    private const val TEXT = 0xFFE3E2E9.toInt()
    private const val MUTED = 0xFF9A9AA5.toInt()
    private const val FILE_NAME = "reading_retrospective.png"

    fun shareText(context: Context, heading: String, title: String, lines: List<String>) {
        val text = (listOf("$heading — $title", "") + lines).joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, context.stringResource(MR.strings.action_share)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }

    fun shareImage(context: Context, heading: String, title: String, lines: List<String>) {
        val file = File(context.cacheImageDir, FILE_NAME)
        file.parentFile?.mkdirs()
        val bitmap = render(heading, title, lines, context.stringResource(MR.strings.reading_stats_share_footer))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        context.startActivity(file.getUriCompat(context).toShareIntent(context, "image/png"))
    }

    private fun render(heading: String, title: String, lines: List<String>, footer: String): Bitmap {
        val width = (WIDTH - 2 * MARGIN).toInt()
        fun layout(text: String, size: Float, color: Int, bold: Boolean = false): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        }
        val blocks = listOf(
            layout(heading, HEADING_SIZE, ACCENT),
            layout(title, TITLE_SIZE, TEXT, bold = true),
        ) + lines.map { layout(it, BODY_SIZE, TEXT) } + layout(footer, FOOTER_SIZE, MUTED)
        val height = (MARGIN * 2 + blocks.sumOf { it.height.toDouble() } + GAP * (blocks.size - 1)).toInt()
        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        var y = MARGIN
        blocks.forEach { block ->
            canvas.save()
            canvas.translate(MARGIN, y)
            block.draw(canvas)
            canvas.restore()
            y += block.height + GAP
        }
        return bitmap
    }
}
