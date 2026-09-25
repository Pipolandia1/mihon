package tachiyomi.domain.readingstats.comics

/** How a chapter sits in its series' numbering. */
enum class IssueKind(val rank: Int) {
    REGULAR(0),
    SPECIAL(1),
    ANNUAL(2),
    COLLECTED(3),
}

/** What a chapter's name says about it as a comic. */
data class ParsedIssue(
    val kind: IssueKind,
    /** The issue number; null when the name has none. 0.5 for "#1/2". */
    val number: Double?,
    /** What follows the number without being part of it: ".MU", ".NOW". */
    val suffix: String?,
    /** What makes this a variant of the numbered issue: "Noir Edition"; a story title is not one. */
    val variant: String?,
    val part: Int?,
    /** The unit the name implies, or null when it does not say. */
    val unit: ComicUnit?,
    /** How to show it: "#1", "#1/2", "#1.MU", "Annual 2025", "TPB 1", "Vol. 2". */
    val label: String,
)

/**
 * Reads issue numbering from chapter names the way Western comic sources write them. Mihon keeps a
 * single number per chapter, so "Annual 2025" becomes chapter 2025 and "TPB (Part 1)", "Issue #1"
 * and "#1 Noir Edition" all become 1; this keeps what the name actually says.
 */
object IssueNumbers {

    private val OMNIBUS = Regex("""\b(omnibus|compendium)\b""", RegexOption.IGNORE_CASE)
    private val HARDCOVER = Regex("""\b(hardcover|hc|deluxe(\s+edition)?)\b""", RegexOption.IGNORE_CASE)
    private val TPB = Regex("""\b(tpb|trade\s+paperback)\b""", RegexOption.IGNORE_CASE)
    private val VOLUME = Regex("""\b(?:vol(?:ume)?\.?|book)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val ANNUAL = Regex("""\bannual\b\s*#?\s*(\d+)?""", RegexOption.IGNORE_CASE)
    private val SPECIAL = Regex(
        """\b(special|one[\s-]?shot|free\s+comic\s+book\s+day|fcbd|preview|sketchbook)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PART = Regex("""\bpart\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** "TPB 2", "HC #3", "Omnibus 1": the number right after a collected edition's name. */
    private val COLLECTED_NUMBER = Regex(
        """\b(?:tpb|trade\s+paperback|hc|hardcover|omnibus|compendium|deluxe(?:\s+edition)?)\s*#?\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    /** "#12", "Issue #12", "Issue 12", "Chapter 12", "#1/2", "#½", "#1.MU", "#-1". */
    private val NUMBER = Regex(
        """(?:#|\bissue\s+#?|\bchapter\s+|^)\s*(-?\d+(?:\.\d+)?|½)(?:\s*/\s*(\d+))?(\.[A-Za-z][A-Za-z0-9]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ISSUE_MARK = Regex("""(#|\bissue\b)""", RegexOption.IGNORE_CASE)

    /** Words that make the text after a number a variant rather than a story title. */
    private val VARIANT = Regex(
        """\b(variant|edition|cover|printing|facsimile|reprint|director'?s\s+cut|virgin|incentive|foil|b&w)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(name: String, chapterNumber: Double = -1.0): ParsedIssue {
        val text = name.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        val collectedUnit = when {
            OMNIBUS.containsMatchIn(text) -> ComicUnit.OMNIBUS
            HARDCOVER.containsMatchIn(text) -> ComicUnit.HARDCOVER
            TPB.containsMatchIn(text) || VOLUME.containsMatchIn(text) -> ComicUnit.TPB
            else -> null
        }
        val part = PART.find(text)?.groupValues?.get(1)?.toIntOrNull()

        if (collectedUnit != null) {
            val volume = VOLUME.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val number = volume?.toDouble()
                ?: part?.toDouble()
                ?: COLLECTED_NUMBER.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: numberIn(text)?.first
            val label = when {
                volume != null -> "Vol. $volume"
                else -> listOfNotNull(unitLabel(collectedUnit), number?.let(::format)).joinToString(" ")
            }
            return ParsedIssue(IssueKind.COLLECTED, number, null, null, part, collectedUnit, label)
        }

        ANNUAL.find(text)?.let { annual ->
            val number = annual.groupValues[1].toDoubleOrNull()
            val label = listOfNotNull("Annual", number?.let(::format)).joinToString(" ")
            return ParsedIssue(IssueKind.ANNUAL, number, null, null, part, ComicUnit.ISSUE, label)
        }

        val found = numberIn(text)
        val number = found?.first ?: chapterNumber.takeIf { it >= 0 }
        val suffix = found?.second
        val special = SPECIAL.containsMatchIn(text)
        val variant = found?.third?.takeIf { !special && VARIANT.containsMatchIn(it) }
        val kind = if (special) IssueKind.SPECIAL else IssueKind.REGULAR
        val unit = if (found != null && ISSUE_MARK.containsMatchIn(text)) ComicUnit.ISSUE else null
        val label = when {
            special -> text
            number != null -> "#" + format(number) + suffix.orEmpty()
            else -> text
        }
        return ParsedIssue(kind, number, suffix, variant, part, unit, label)
    }

    /** How a database writes an issue number, as a label: "12" is "#12"; "Annual 1" stays as it is. */
    fun labelOf(number: String): String {
        val text = number.trim()
        val numeric = text.firstOrNull()?.let { it.isDigit() || it == '-' || it == '½' } == true
        return if (numeric) "#$text" else text
    }

    /** Orders chapters of one series: regular issues, specials, annuals, then collected editions. */
    val order: Comparator<ParsedIssue> = compareBy<ParsedIssue>(
        { it.kind.rank },
        { it.number ?: Double.MAX_VALUE },
        { it.suffix.orEmpty() },
        { it.variant != null },
        { it.variant.orEmpty() },
        { it.part ?: 0 },
    )

    /** The number, what follows it (".MU"), and the words left over after it. */
    private fun numberIn(text: String): Triple<Double, String?, String?>? {
        val match = NUMBER.find(text) ?: return null
        val whole = match.groupValues[1]
        val number = if (whole == "½") 0.5 else whole.toDoubleOrNull() ?: return null
        val denominator = match.groupValues[2].toDoubleOrNull()
        val value = if (denominator != null && denominator != 0.0) number / denominator else number
        val suffix = match.groupValues[3].takeIf { it.isNotEmpty() }?.uppercase()
        val rest = text.substring(match.range.last + 1)
            .trim()
            .trim('-', ':', '(', ')', '.', ',')
            .trim()
            .takeIf { it.any(Char::isLetter) }
        return Triple(value, suffix, rest)
    }

    private fun unitLabel(unit: ComicUnit): String = when (unit) {
        ComicUnit.TPB -> "TPB"
        ComicUnit.HARDCOVER -> "HC"
        ComicUnit.OMNIBUS -> "Omnibus"
        else -> ""
    }

    private fun format(number: Double): String = when {
        number == 0.5 -> "1/2"
        number % 1.0 == 0.0 -> number.toLong().toString()
        else -> number.toString()
    }
}
