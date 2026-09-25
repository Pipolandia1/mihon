package tachiyomi.domain.readingstats.comics

/** A series title as comic sites write it: "Batman (2025-)", "Flashpoint (2011)". */
data class ParsedTitle(
    val name: String,
    val startYear: Int?,
    val endYear: Int?,
    /** "(2025-)": begun and not ended. Null when the title does not say. */
    val ongoing: Boolean?,
)

object SeriesTitles {

    private val YEARS = Regex("""^(.*?)\s*\((\d{4})(?:\s*(-)\s*(\d{4})?)?\)\s*$""")

    fun parse(title: String): ParsedTitle {
        val match = YEARS.find(title.trim()) ?: return ParsedTitle(title.trim(), null, null, null)
        val (name, start, dash, end) = match.destructured
        return ParsedTitle(
            name = name.trim(),
            startYear = start.toInt(),
            endYear = end.toIntOrNull() ?: start.toInt().takeIf { dash.isEmpty() },
            ongoing = if (dash.isEmpty()) false else end.isEmpty(),
        )
    }
}

/** Publishers and imprints as sources tend to list them among genres. */
object Publishers {

    /** Lower-case tag to (publisher, imprint). */
    private val KNOWN: Map<String, Pair<String, String?>> = buildMap {
        fun add(publisher: String, vararg tags: String, imprint: String? = null) =
            tags.forEach { put(it, publisher to imprint) }
        add("DC Comics", "dc comics", "dc")
        add("DC Comics", "vertigo", imprint = "Vertigo")
        add("DC Comics", "dc black label", "black label", imprint = "DC Black Label")
        add("DC Comics", "wildstorm", imprint = "WildStorm")
        add("Marvel", "marvel", "marvel comics")
        add("Image Comics", "image", "image comics")
        add("Image Comics", "skybound", imprint = "Skybound")
        add("Image Comics", "top cow", imprint = "Top Cow")
        add("IDW Publishing", "idw", "idw publishing")
        add("Dark Horse Comics", "dark horse", "dark horse comics")
        add("BOOM! Studios", "boom! studios", "boom studios", "boom!")
        add("Dynamite Entertainment", "dynamite", "dynamite entertainment")
        add("Titan Comics", "titan comics")
        add("Oni Press", "oni press")
        add("Vault Comics", "vault comics")
        add("Mad Cave Studios", "mad cave studios")
        add("AfterShock Comics", "aftershock comics")
        add("Archie Comics", "archie comics")
        add("Valiant Entertainment", "valiant", "valiant entertainment")
        add("AHOY Comics", "ahoy comics")
        add("Zenescope Entertainment", "zenescope", "zenescope entertainment")
        add("Scout Comics", "scout comics")
        add("Humanoids", "humanoids")
        add("Ablaze", "ablaze")
        add("Fantagraphics", "fantagraphics")
        add("Drawn & Quarterly", "drawn & quarterly", "drawn and quarterly")
        add("Rebellion", "rebellion", "2000 ad")
    }

    /** The publisher, and imprint, the first matching genre names; null if none does. */
    fun match(genres: List<String>): Pair<String, String?>? =
        genres.firstNotNullOfOrNull { KNOWN[it.trim().lowercase()] }

    fun isPublisherTag(genre: String): Boolean = genre.trim().lowercase() in KNOWN
}

/** A chapter as inference sees it. */
data class ChapterName(val id: Long, val name: String, val number: Double)

/** Suggestions from what the entry already has: its title, genres, credits and chapter names. */
object ComicInference {

    fun suggest(
        title: String,
        author: String?,
        artist: String?,
        genres: List<String>,
        chapters: List<ChapterName>,
    ): List<MetadataValue> = buildList {
        val parsed = SeriesTitles.parse(title)
        if (parsed.startYear != null) {
            add(series(ComicField.SERIES_NAME, parsed.name, MetadataOrigin.INFERRED))
            add(series(ComicField.START_YEAR, parsed.startYear.toString(), MetadataOrigin.INFERRED))
            parsed.endYear?.let { add(series(ComicField.END_YEAR, it.toString(), MetadataOrigin.INFERRED)) }
        }
        Publishers.match(genres)?.let { (publisher, imprint) ->
            add(series(ComicField.PUBLISHER, publisher, MetadataOrigin.INFERRED))
            imprint?.let { add(series(ComicField.IMPRINT, it, MetadataOrigin.INFERRED)) }
        }
        genres.map(String::trim).filter { it.isNotEmpty() && !Publishers.isPublisherTag(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { add(MetadataValue(ComicField.GENRE, null, it, MetadataOrigin.SOURCE)) }
        people(author)?.let { add(MetadataValue(ComicField.WRITER, null, it, MetadataOrigin.SOURCE)) }
        people(artist)?.let { add(MetadataValue(ComicField.ARTIST, null, it, MetadataOrigin.SOURCE)) }

        val issues = chapters.associateWith { IssueNumbers.parse(it.name, it.number) }
        // The series unit is the one most chapters name; chapters that name another are exceptions.
        val seriesUnit = issues.values.mapNotNull { it.unit }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        seriesUnit?.let { add(series(ComicField.UNIT, it.name, MetadataOrigin.INFERRED)) }
        issues.forEach { (chapter, issue) ->
            if (issue.unit != null && issue.unit != seriesUnit) {
                add(MetadataValue(ComicField.UNIT, chapter.id, listOf(issue.unit.name), MetadataOrigin.INFERRED))
            }
            if (issue.kind != IssueKind.REGULAR || issue.suffix != null) {
                add(MetadataValue(ComicField.ISSUE_LABEL, chapter.id, listOf(issue.label), MetadataOrigin.INFERRED))
            }
            issue.variant?.let {
                add(MetadataValue(ComicField.VARIANT, chapter.id, listOf(it), MetadataOrigin.INFERRED))
            }
        }
    }

    private fun series(field: ComicField, value: String, origin: MetadataOrigin) =
        MetadataValue(field, null, listOf(value), origin)

    private fun people(text: String?): List<String>? = text
        ?.split(',', ';', '&')
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        ?.takeIf { it.isNotEmpty() }
}
