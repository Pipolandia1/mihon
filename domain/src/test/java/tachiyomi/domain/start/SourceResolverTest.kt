package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Finding a catalogue series in the reader's sources, in their order (docs/HOME_PLAN.md §7.3, H4). */
class SourceResolverTest {

    private val day = 24L * 60 * 60 * 1000
    private var now = 1_000 * day

    private val primary = 1L
    private val reserve = 2L
    private val third = 3L

    private val absolute = CatalogSeries("metron:series:1", "Absolute Batman", 2024)

    /** What each source answers, and who was asked. */
    private val answers = HashMap<Long, SearchResult>()
    private val asked = mutableListOf<Long>()

    private val cache = object : SourceMatchRepository {
        val rows = HashMap<String, SourceMatch>()
        override suspend fun get(catalogKey: String) = rows[catalogKey]
        override suspend fun put(match: SourceMatch) {
            rows[match.catalogKey] = match
        }
    }

    private fun resolver(maxSearches: Int = 10) = SourceResolver(
        searcher = { sourceId, _ ->
            asked += sourceId
            answers[sourceId] ?: SearchResult.Hits(emptyList())
        },
        cache = cache,
        maxSearches = maxSearches,
        clock = { now },
    )

    private fun hits(vararg titles: String) =
        SearchResult.Hits(titles.map { SourceHit(it, "/series/${it.hashCode()}") })

    private val cloudflare = SearchResult.Failure(FailureKind.CLOUDFLARE, "Failed to bypass Cloudflare")

    @Test
    fun `the primary is asked first, and the reserves are not when it has the series`() = runTest {
        answers[primary] = hits("Absolute Batman (2024-)")
        answers[reserve] = hits("Absolute Batman (2024-)")

        val resolution = resolver().resolve(absolute, listOf(primary, reserve))

        resolution.status shouldBe Resolution.Status.FOUND
        resolution.match.shouldNotBeNull().sourceId shouldBe primary
        resolution.match!!.matchedTitle shouldBe "Absolute Batman (2024-)"
        asked shouldBe listOf(primary)
    }

    @Test
    fun `without the series in the primary, the reserves are asked in their order`() = runTest {
        answers[primary] = hits("Batman (2025-)", "Batman: The Long Halloween")
        answers[reserve] = SearchResult.Hits(emptyList())
        answers[third] = hits("Absolute Batman (2024-)")

        val resolution = resolver().resolve(absolute, listOf(primary, reserve, third))

        resolution.match.shouldNotBeNull().sourceId shouldBe third
        asked shouldBe listOf(primary, reserve, third)
        resolution.attempts[0] shouldBe Attempt.NoMatch(primary, 2, "Batman (2025-)")
        resolution.attempts[1] shouldBe Attempt.NoResults(reserve)
    }

    @Test
    fun `a primary behind Cloudflare passes to the reserve, and says why`() = runTest {
        answers[primary] = cloudflare
        answers[reserve] = hits("Absolute Batman (2024-)")

        val resolution = resolver().resolve(absolute, listOf(primary, reserve))

        resolution.match.shouldNotBeNull().sourceId shouldBe reserve
        resolution.attempts.first().shouldBeInstanceOf<Attempt.Failed>().kind shouldBe FailureKind.CLOUDFLARE
    }

    @Test
    fun `found is kept a week, and asked again after it`() = runTest {
        answers[primary] = hits("Absolute Batman (2024-)")
        val resolver = resolver()

        resolver.resolve(absolute, listOf(primary))
        now += 6 * day
        resolver.resolve(absolute, listOf(primary)).fromCache shouldBe true
        now += 2 * day
        resolver.resolve(absolute, listOf(primary)).fromCache shouldBe false

        asked shouldBe listOf(primary, primary)
    }

    @Test
    fun `not found is kept a day`() = runTest {
        val resolver = resolver()

        resolver.resolve(absolute, listOf(primary, reserve)).status shouldBe Resolution.Status.NOT_FOUND
        resolver.resolve(absolute, listOf(primary, reserve)).let {
            it.status shouldBe Resolution.Status.NOT_FOUND
            it.fromCache shouldBe true
        }
        now += day
        resolver.resolve(absolute, listOf(primary, reserve)).fromCache shouldBe false
    }

    @Test
    fun `found only after the primary failed is kept a day, so the primary is asked again soon`() = runTest {
        answers[primary] = cloudflare
        answers[reserve] = hits("Absolute Batman (2024-)")

        val match = resolver().resolve(absolute, listOf(primary, reserve)).match.shouldNotBeNull()

        match.expiresAt shouldBe now + day
    }

    @Test
    fun `a failure with nothing found keeps nothing`() = runTest {
        answers[primary] = cloudflare

        val resolution = resolver().resolve(absolute, listOf(primary, reserve))

        resolution.status shouldBe Resolution.Status.FAILED
        cache.rows.values.shouldBeEmpty()
    }

    @Test
    fun `a new order of sources asks again`() = runTest {
        answers[primary] = hits("Absolute Batman (2024-)")
        answers[reserve] = hits("Absolute Batman (2024-)")
        val resolver = resolver()
        resolver.resolve(absolute, listOf(reserve, primary))

        val again = resolver.resolve(absolute, listOf(primary, reserve))

        again.fromCache shouldBe false
        again.match.shouldNotBeNull().sourceId shouldBe primary
    }

    @Test
    fun `a reserve added after the one that had it keeps the answer`() = runTest {
        answers[primary] = hits("Absolute Batman (2024-)")
        val resolver = resolver()
        resolver.resolve(absolute, listOf(primary))

        resolver.resolve(absolute, listOf(primary, third)).fromCache shouldBe true
    }

    @Test
    fun `a source that failed is not asked again for a while`() = runTest {
        answers[primary] = cloudflare
        answers[reserve] = hits("Absolute Batman (2024-)", "Flashpoint (2011)")
        val resolver = resolver()
        resolver.resolve(absolute, listOf(primary, reserve))

        val flashpoint = resolver.resolve(
            CatalogSeries("metron:series:2", "Flashpoint", 2011),
            listOf(primary, reserve),
        )

        flashpoint.attempts.first() shouldBe Attempt.Skipped(primary, FailureKind.CLOUDFLARE)
        asked shouldBe listOf(primary, reserve, reserve)
        now += 31 * 60_000
        resolver.resolve(CatalogSeries("metron:series:3", "Batman", 2025), listOf(primary, reserve))
        asked.last() shouldBe reserve
        asked[asked.size - 2] shouldBe primary
    }

    @Test
    fun `a session reaches the network for a limited number of series, and the cache is free`() = runTest {
        answers[primary] = hits("Absolute Batman (2024-)")
        val resolver = resolver(maxSearches = 1)

        resolver.resolve(absolute, listOf(primary)).status shouldBe Resolution.Status.FOUND
        resolver.resolve(absolute, listOf(primary)).status shouldBe Resolution.Status.FOUND
        resolver.resolve(CatalogSeries("metron:series:2", "Flashpoint", 2011), listOf(primary)).status shouldBe
            Resolution.Status.LIMITED
        asked shouldBe listOf(primary)
    }

    @Test
    fun `the test by hand asks every source afresh and keeps nothing`() = runTest {
        answers[primary] = cloudflare
        answers[reserve] = hits("Absolute Batman (2024-)")
        val resolver = resolver()
        resolver.resolve(absolute, listOf(primary, reserve))
        cache.rows.clear()

        val test = resolver.test(absolute, listOf(primary, reserve))

        test.attempts.first().shouldBeInstanceOf<Attempt.Failed>()
        test.status shouldBe Resolution.Status.FOUND
        cache.rows.values.shouldBeEmpty()
    }

    @Test
    fun `no sources chosen is said, not searched`() = runTest {
        val resolution = resolver().resolve(absolute, emptyList())

        resolution.status shouldBe Resolution.Status.NO_SOURCES
        resolution.match.shouldBeNull()
        asked.shouldBeEmpty()
    }
}
