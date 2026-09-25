package tachiyomi.domain.readingstats.comics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ComicSuggestionsTest {

    private fun value(field: ComicField, value: String, origin: MetadataOrigin, chapterId: Long? = null) =
        MetadataValue(field, chapterId, listOf(value), origin)

    @Test
    fun `Metron wins over the GCD, ComicInfo, the source and guesses`() {
        val stored = listOf(
            value(ComicField.PUBLISHER, "DC", MetadataOrigin.COMIC_INFO),
            value(ComicField.PUBLISHER, "DC Comics", MetadataOrigin.GCD),
            value(ComicField.PUBLISHER, "DC Comics", MetadataOrigin.METRON),
        )
        val inferred = listOf(value(ComicField.PUBLISHER, "DC Comics", MetadataOrigin.INFERRED))
        val metadata = ComicMetadata(1, emptyList(), stored)

        ComicSuggestions.best(metadata, inferred).single().origin shouldBe MetadataOrigin.METRON
        ComicSuggestions.alternatives(metadata, inferred)
            .getValue(null to ComicField.PUBLISHER)
            .map { it.origin } shouldBe listOf(MetadataOrigin.METRON, MetadataOrigin.COMIC_INFO)
    }

    @Test
    fun `without Metron the GCD is next`() {
        val stored = listOf(
            value(ComicField.START_YEAR, "2011", MetadataOrigin.COMIC_INFO),
            value(ComicField.START_YEAR, "2011", MetadataOrigin.GCD),
        )

        ComicSuggestions.best(ComicMetadata(1, emptyList(), stored), emptyList()).single().origin shouldBe
            MetadataOrigin.GCD
    }

    @Test
    fun `confirmed fields are not pending, in their own scope only`() {
        val confirmed = listOf(value(ComicField.UNIT, "ISSUE", MetadataOrigin.USER))
        val inferred = listOf(
            value(ComicField.UNIT, "ISSUE", MetadataOrigin.INFERRED),
            value(ComicField.UNIT, "TPB", MetadataOrigin.INFERRED, chapterId = 5),
        )

        val pending = ComicSuggestions.pending(ComicMetadata(1, confirmed, emptyList()), inferred)

        pending shouldBe listOf(value(ComicField.UNIT, "TPB", MetadataOrigin.INFERRED, chapterId = 5))
    }
}
