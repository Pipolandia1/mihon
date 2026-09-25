package tachiyomi.domain.start

/** An issue finished in the reader, and all the active time it took. */
data class IssueTime(val chapterId: Long, val mangaId: Long?, val activeMs: Long)

/**
 * How long an issue takes this reader, measured, never assumed. Amendment H2.
 *
 * The median of the issues first finished in the reader over the last [WINDOW_DAYS] days, counting
 * every session of each up to the one that finished it. Issues marked read by hand have no time and
 * are not in it. Below [MIN_ISSUES] there is no figure, and nothing that needs one is shown.
 */
object ReadingTime {

    const val WINDOW_DAYS = 90
    const val MIN_ISSUES = 3

    private const val MS_PER_MINUTE = 60_000L
    private const val ROUND_ABOVE_MINUTES = 10
    private const val ROUND_TO_MINUTES = 5

    /** Median active time per issue, in ms; null with fewer than [MIN_ISSUES] measured. */
    fun perIssueMs(times: List<IssueTime>): Long? {
        val lengths = times.map { it.activeMs }.filter { it > 0 }.sorted()
        if (lengths.size < MIN_ISSUES) return null
        val middle = lengths.size / 2
        return if (lengths.size % 2 == 1) lengths[middle] else (lengths[middle - 1] + lengths[middle]) / 2
    }

    /** Minutes to show for [ms]: whole minutes up to 10, then to the nearest 5, and never 0. */
    fun displayMinutes(ms: Long): Int {
        val minutes = ((ms + MS_PER_MINUTE / 2) / MS_PER_MINUTE).toInt().coerceAtLeast(1)
        if (minutes <= ROUND_ABOVE_MINUTES) return minutes
        return (minutes + ROUND_TO_MINUTES / 2) / ROUND_TO_MINUTES * ROUND_TO_MINUTES
    }

    /** How many issues fit in [minutes] at [perIssueMs]; 0 when not even one does. */
    fun issuesIn(minutes: Int, perIssueMs: Long): Int = (minutes * MS_PER_MINUTE / perIssueMs).toInt()
}
