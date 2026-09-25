package eu.kanade.tachiyomi.readingstats.comics

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import logcat.LogPriority
import mihon.core.archive.archiveReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Reads the ComicInfo.xml files inside a local entry's chapters, for suggestions. */
@Inject
@SingleIn(AppScope::class)
class ComicInfoReader(
    private val context: Context,
    private val localSource: LocalSource,
    private val xml: XML,
) {

    fun canRead(manga: Manga): Boolean = manga.source == LocalSource.ID

    /**
     * Series suggestions from the first chapter that has a ComicInfo.xml, and each chapter's own.
     * Empty when the entry is not local or no chapter has one. Call off the main thread.
     */
    fun read(manga: Manga, chapters: List<Chapter>): List<MetadataValue> {
        if (!canRead(manga)) return emptyList()
        val values = mutableListOf<MetadataValue>()
        var seriesRead = false
        chapters.forEach { chapter ->
            val info = runCatching { comicInfoOf(chapter) }
                .onFailure { logcat(LogPriority.WARN, it) { "No ComicInfo for chapter ${chapter.id}" } }
                .getOrNull() ?: return@forEach
            if (!seriesRead) {
                values += ComicInfoMapping.seriesValues(info)
                seriesRead = true
            }
            values += ComicInfoMapping.chapterValues(info, chapter.id)
        }
        return values
    }

    private fun comicInfoOf(chapter: Chapter): ComicInfoMetadata? =
        when (val format = localSource.getFormat(chapter.toSChapter())) {
            is Format.Directory -> format.file.findFile(COMIC_INFO_FILE)?.openInputStream()?.use(::parse)
            is Format.Archive -> format.file.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use(::parse)
            }
            is Format.Epub -> null
        }

    private fun parse(stream: InputStream): ComicInfoMetadata =
        AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader(ComicInfoMetadata.serializer(), it)
        }
}
