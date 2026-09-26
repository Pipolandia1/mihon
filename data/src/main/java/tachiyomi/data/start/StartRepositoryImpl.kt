package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.Start_feedback
import tachiyomi.data.subscribeToList
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicInference
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.start.Credit
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.ExportedFeedback
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.FeedbackSubject
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.StartRepository
import tachiyomi.domain.start.WorkFacts

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class StartRepositoryImpl(
    private val database: Database,
) : StartRepository {

    private val queries get() = database.startQueries
    private val metadataQueries get() = database.comic_metadataQueries

    override fun subscribeWorks(): Flow<List<WorkFacts>> = combine(
        queries.works().subscribeToList(),
        queries.sessionsByManga().subscribeToList(),
        queries.worksMetadata().subscribeToList(),
        metadataQueries.lastReadByManga().subscribeToList(),
        metadataQueries.lastOpenedByManga().subscribeToList(),
    ) { works, sessions, metadata, lastRead, lastOpened ->
        val sessionsBy = sessions.associateBy { it.manga_id }
        val metadataBy = metadata.groupBy { it.manga_id }
        val read = lastRead.associate { it.manga_id!! to it.last_read }
        val opened = lastOpened.associate { it.manga_id to it.last_read?.time }
        works.map { work ->
            val rows = metadataBy[work._id].orEmpty()
            val finished = work.status.toInt() in FINISHED
            val confirmedTotal = rows.firstOrNull { it.field_ == ComicField.TOTAL_ISSUES.id && it.confirmed }
                ?.value_?.toIntOrNull()
            WorkFacts(
                mangaId = work._id,
                title = work.title,
                inLibrary = work.favorite,
                dateAdded = work.date_added,
                chapters = work.chapter_count.toInt(),
                chaptersRead = work.read_count.toInt(),
                total = confirmedTotal ?: work.chapter_count.toInt().takeIf { finished && it > 0 },
                finished = finished,
                sessions = sessionsBy[work._id]?.sessions?.toInt() ?: 0,
                activeMs = sessionsBy[work._id]?.active_ms ?: 0,
                lastReadAt = listOfNotNull(read[work._id], opened[work._id]).maxOrNull()?.takeIf { it > 0 },
                credits = storedCredits(rows) + sourceCredits(work.title, work.author, work.artist, work.genre),
                catalogIds = catalogIds(rows),
            )
        }
    }

    /** The series' own ids in the catalogues; the ones stored per issue are the issues', not the series'. */
    private fun catalogIds(rows: List<tachiyomi.data.WorksMetadata>): Set<String> = rows
        .filter { it.chapter_id == null }
        .mapNotNull { row ->
            when (row.field_) {
                ComicField.METRON_ID.id -> "metron:series:${row.value_}"
                ComicField.GCD_ID.id -> "gcd:series:${row.value_}"
                else -> null
            }
        }
        .toSet()

    /** Confirmed values count as the reader's own; suggestions keep where they came from. */
    private fun storedCredits(rows: List<tachiyomi.data.WorksMetadata>): List<Credit> = rows.mapNotNull { row ->
        val kind = KINDS[row.field_] ?: return@mapNotNull null
        Credit(kind, row.value_, if (row.confirmed) MetadataOrigin.USER else MetadataOrigin.of(row.origin))
    }

    /** The author, artist and genres the source gives, read the way the metadata editor reads them. */
    private fun sourceCredits(title: String, author: String?, artist: String?, genres: List<String>?): List<Credit> =
        ComicInference.suggest(title, author, artist, genres.orEmpty(), emptyList())
            .filter { it.origin == MetadataOrigin.SOURCE }
            .flatMap { value ->
                val kind = KINDS[value.field.id] ?: return@flatMap emptyList()
                value.values.map { Credit(kind, it, MetadataOrigin.SOURCE) }
            }

    override fun subscribeFeedback(): Flow<List<Feedback>> =
        queries.allFeedback().subscribeToList().map { rows -> rows.map(::feedbackOf) }

    override suspend fun setFeedback(feedback: Feedback) {
        queries.upsertFeedback(
            subjectKind = feedback.subject.id,
            subjectKey = feedback.key,
            verdict = feedback.verdict.id,
            untilAt = feedback.untilAt,
            createdAt = feedback.createdAt,
        )
    }

    override suspend fun removeFeedback(subject: FeedbackSubject, key: String) {
        queries.deleteFeedback(subject.id, key)
    }

    override suspend fun exportFeedback(): Pair<List<ExportedFeedback>, List<Feedback>> {
        val works = queries.workFeedbackWithUrls().awaitAsList().map { row ->
            ExportedFeedback(
                sourceId = row.source,
                mangaUrl = row.url,
                feedback = Feedback(
                    subject = FeedbackSubject.WORK,
                    key = row.subject_key,
                    verdict = FeedbackVerdict.of(row.verdict),
                    untilAt = row.until_at,
                    createdAt = row.created_at,
                ),
            )
        }
        return works to queries.nameFeedback().awaitAsList().map(::feedbackOf)
    }

    override suspend fun importFeedback(works: List<ExportedFeedback>, names: List<Feedback>) {
        val resolved = works.mapNotNull { exported ->
            val mangaId = queries.mangaIdByUrl(exported.sourceId, exported.mangaUrl).awaitAsOneOrNull()
                ?: return@mapNotNull null
            exported.feedback.copy(key = mangaId.toString())
        }
        database.transaction {
            (resolved + names).forEach { feedback ->
                queries.upsertFeedback(
                    feedback.subject.id,
                    feedback.key,
                    feedback.verdict.id,
                    feedback.untilAt,
                    feedback.createdAt,
                )
            }
        }
    }

    private fun feedbackOf(row: Start_feedback) = Feedback(
        subject = FeedbackSubject.of(row.subject_kind),
        key = row.subject_key,
        verdict = FeedbackVerdict.of(row.verdict),
        untilAt = row.until_at,
        createdAt = row.created_at,
    )

    private companion object {
        val FINISHED = setOf(SManga.COMPLETED, SManga.PUBLISHING_FINISHED, SManga.CANCELLED)

        val KINDS = mapOf(
            ComicField.PUBLISHER.id to EntityKind.PUBLISHER,
            ComicField.IMPRINT.id to EntityKind.IMPRINT,
            ComicField.UNIVERSE.id to EntityKind.UNIVERSE,
            ComicField.GENRE.id to EntityKind.GENRE,
            ComicField.WRITER.id to EntityKind.WRITER,
            ComicField.ARTIST.id to EntityKind.ARTIST,
            ComicField.CHARACTER.id to EntityKind.CHARACTER,
            ComicField.TEAM.id to EntityKind.TEAM,
        )
    }
}
