package eu.kanade.tachiyomi.readingstats.comics

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import java.io.StringReader

class ComicInfoMappingTest {

    // As the app configures it.
    private val xml = XML.v1 {
        policy { ignoreUnknownChildren() }
    }

    // The app reads with Android's parser, which unit tests do not have; xmlutil's own reads the same.
    private val info = xml.decodeFromReader(
        ComicInfoMetadata.serializer(),
        KtXmlReader(StringReader(javaClass.getResource("/comics/ComicInfo.xml")!!.readText())),
    )

    private fun List<MetadataValue>.of(field: ComicField) = single { it.field == field }.values

    @Test
    fun `the fields Mihon's own model leaves out are read`() {
        val values = ComicInfoMapping.seriesValues(info)

        values.all { it.origin == MetadataOrigin.COMIC_INFO && it.chapterId == null } shouldBe true
        values.of(ComicField.SERIES_NAME) shouldBe listOf("Flashpoint")
        values.of(ComicField.PUBLISHER) shouldBe listOf("DC Comics")
        values.of(ComicField.TOTAL_ISSUES) shouldBe listOf("5")
        values.of(ComicField.VOLUME) shouldBe listOf("2011")
        values.of(ComicField.GENRE) shouldBe listOf("Superhero")
        // "Limited Series" says nothing a unit could.
        values.filter { it.field == ComicField.UNIT }.shouldBeEmpty()
    }

    @Test
    fun `a chapter's own fields`() {
        val values = ComicInfoMapping.chapterValues(info, chapterId = 3)

        values.all { it.chapterId == 3L } shouldBe true
        values.of(ComicField.ISSUE_LABEL) shouldBe listOf("#1")
        values.of(ComicField.COVER_DATE) shouldBe listOf("2011-07")
        values.of(ComicField.PAGE_COUNT) shouldBe listOf("40")
        values.of(ComicField.ARTIST) shouldBe listOf("Andy Kubert", "Sandra Hope")
        values.of(ComicField.EDITOR) shouldBe listOf("Adam Schlagman", "Rex Ogle")
        values.of(ComicField.ARC) shouldBe listOf("Flashpoint")
        values.of(ComicField.CHARACTER) shouldBe listOf("Flash", "Batman")
        values.of(ComicField.TEAM) shouldBe listOf("Justice League")
    }

    @Test
    fun `formats that name a collected edition set the unit`() {
        ComicInfoMapping.unitOf("TPB") shouldBe ComicUnit.TPB
        ComicInfoMapping.unitOf("Hardcover") shouldBe ComicUnit.HARDCOVER
        ComicInfoMapping.unitOf("Omnibus") shouldBe ComicUnit.OMNIBUS
        ComicInfoMapping.unitOf("Series") shouldBe null
        ComicInfoMapping.unitOf(null) shouldBe null
    }
}
