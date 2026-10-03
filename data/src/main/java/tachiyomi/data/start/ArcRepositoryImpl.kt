package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.data.Start_arc_issues
import tachiyomi.data.Start_arcs
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcIssue
import tachiyomi.domain.start.ArcRepository
import tachiyomi.domain.start.ReadArcName
import java.util.Date

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ArcRepositoryImpl(
    private val database: Database,
) : ArcRepository {

    private val queries get() = database.start_arcsQueries

    override suspend fun getArc(key: String): Pair<Arc, List<ArcIssue>>? {
        val row = queries.getArc(key).awaitAsOneOrNull() ?: return null
        return arcOf(row) to queries.arcIssues(key).awaitAsList().map(::issueOf)
    }

    override suspend fun keptArcs(): List<Pair<Arc, List<ArcIssue>>> {
        val issues = queries.keptArcIssues().awaitAsList().groupBy({ it.arc_key }, ::issueOf)
        return queries.keptArcs().awaitAsList().map { arcOf(it) to issues[it.arc_key].orEmpty() }
    }

    override suspend fun readArcNames(since: Long, limit: Int): List<ReadArcName> =
        queries.readArcNames(Date(since), limit.toLong()).awaitAsList().map {
            ReadArcName(it.name, it.manga_id, it.title, it.read_at?.time ?: 0L)
        }

    /** The arc as fetched now, its issues in place of the ones kept. */
    override suspend fun saveArc(arc: Arc, issues: List<ArcIssue>) {
        database.transaction {
            queries.upsertArc(arc.key, arc.name, arc.description, arc.imageUrl, arc.fetchedAt)
            queries.deleteArcIssues(arc.key)
            issues.forEach { issue ->
                queries.insertArcIssue(
                    arcKey = arc.key,
                    position = issue.position.toLong(),
                    issueKey = issue.key,
                    seriesKey = issue.seriesKey,
                    seriesName = issue.seriesName,
                    seriesYear = issue.seriesYear?.toLong(),
                    number = issue.number,
                    coverDate = issue.coverDate,
                    storeDate = issue.storeDate,
                    coverUrl = issue.coverUrl,
                )
            }
        }
    }

    private fun arcOf(row: Start_arcs) = Arc(row.arc_key, row.name, row.description, row.image_url, row.fetched_at)

    private fun issueOf(row: Start_arc_issues) = ArcIssue(
        position = row.position.toInt(),
        key = row.issue_key,
        seriesKey = row.series_key,
        seriesName = row.series_name,
        seriesYear = row.series_year?.toInt(),
        number = row.number,
        coverDate = row.cover_date,
        storeDate = row.store_date,
        coverUrl = row.cover_url,
    )
}
