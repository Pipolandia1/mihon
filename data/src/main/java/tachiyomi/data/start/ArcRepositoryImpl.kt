package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcIssue
import tachiyomi.domain.start.ArcRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ArcRepositoryImpl(
    private val database: Database,
) : ArcRepository {

    private val queries get() = database.start_arcsQueries

    override suspend fun getArc(key: String): Pair<Arc, List<ArcIssue>>? {
        val row = queries.getArc(key).awaitAsOneOrNull() ?: return null
        val arc = Arc(row.arc_key, row.name, row.description, row.image_url, row.fetched_at)
        val issues = queries.arcIssues(key).awaitAsList().map { issue ->
            ArcIssue(
                position = issue.position.toInt(),
                key = issue.issue_key,
                seriesKey = issue.series_key,
                seriesName = issue.series_name,
                seriesYear = issue.series_year?.toInt(),
                number = issue.number,
                coverDate = issue.cover_date,
                storeDate = issue.store_date,
                coverUrl = issue.cover_url,
            )
        }
        return arc to issues
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
}
