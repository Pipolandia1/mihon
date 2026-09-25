package tachiyomi.domain.readingstats.plans

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CollectionRulesTest {

    private val collection = ReadingCollection(1, "c", CollectionKind.ARC, "Court of Owls", 0)

    @Test
    fun `a chapter held twice counts once`() {
        val chapters = listOf(
            PlanChapter(1, 1, true, null),
            PlanChapter(2, 1, false, null),
            PlanChapter(1, 1, true, null),
            PlanChapter(3, 2, false, null),
        )

        val progress = CollectionRules.progress(collection, chapters)

        progress.total shouldBe 3
        progress.done shouldBe 1
        progress.remaining shouldBe 2
        progress.next?.chapterId shouldBe 2
    }

    @Test
    fun `an empty collection has nothing next`() {
        val progress = CollectionRules.progress(collection, emptyList())

        progress.total shouldBe 0
        progress.next.shouldBeNull()
    }
}
