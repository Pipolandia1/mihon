package eu.kanade.tachiyomi.readingstats.comics.online

import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.IssueKind
import tachiyomi.domain.readingstats.comics.IssueNumbers
import tachiyomi.domain.readingstats.comics.ParsedIssue

/** An issue of an online database: its id there and its number as the database writes it. */
data class IssueRef(val id: Long, val number: String)

object IssueMatcher {

    /**
     * Which issue each chapter is, by number: the chapter's confirmed label, or its name, against
     * the database's number. Only regular numbered chapters pair (annuals, specials and collected
     * editions are separate series there); a variant pairs with its issue; where the database lists
     * one number several times (printings, variants) the first is taken. A chapter that pairs with
     * nothing gets no suggestion.
     */
    fun match(chapters: List<ChapterName>, labels: Map<Long, String>, issues: List<IssueRef>): Map<Long, IssueRef> {
        val byKey = issues
            .filter { it.number.isNotBlank() }
            .mapNotNull { issue -> keyOf(IssueNumbers.parse(IssueNumbers.labelOf(issue.number)))?.let { it to issue } }
            .distinctBy { it.first }
            .toMap()
        return chapters.mapNotNull { chapter ->
            val key = keyOf(IssueNumbers.parse(labels[chapter.id] ?: chapter.name, chapter.number))
            key?.let { byKey[it] }?.let { chapter.id to it }
        }.toMap()
    }

    private fun keyOf(issue: ParsedIssue): Pair<Double, String>? {
        if (issue.kind != IssueKind.REGULAR) return null
        val number = issue.number ?: return null
        return number to issue.suffix.orEmpty()
    }
}
