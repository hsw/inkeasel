package io.github.hsw.inkeasel.catalog

import io.github.hsw.inkeasel.CommonsMapping
import io.github.hsw.inkeasel.Picture

/**
 * A catalog row as the app's [Picture]: what the grid, the details panel, the viewer and an upload take. Pure:
 * the texts are the row's own (labels, the source's strings), never translated here.
 */
object CatalogPicture {
    /** The card's Commons thumbnail width when the caller does not choose one (a standard width). */
    const val CARD_WIDTH = 500

    /** The smaller card thumbnail, for columns up to this many pixels wide (a standard Commons width). */
    const val SMALL_CARD_WIDTH = 330

    /**
     * The thumbnail width for cards [columnPx] pixels wide: [SMALL_CARD_WIDTH] when that covers them, else
     * [CARD_WIDTH] (a little soft on the widest columns, but a card is a preview).
     */
    fun cardWidth(columnPx: Int): Int = if (columnPx <= SMALL_CARD_WIDTH) SMALL_CARD_WIDTH else CARD_WIDTH

    /** The item's page on Wikidata, e.g. `https://www.wikidata.org/wiki/Q12418`. */
    fun wikidataPage(qid: String): String = "https://www.wikidata.org/wiki/$qid"

    /**
     * [row] as a [Picture]. [separator] joins names (the artists; the card's artist and date) and comes from
     * the edge, as a string resource. A Wikidata row: the title, artists and first collection from the labels
     * (the file name when there is no title label), the inception year as the date, its Wikidata page, the
     * card's thumbnail [cardWidth] px and the frame's 1920 px rendition built from the Commons file
     * ([CommonsUrls]), the licence's URL from the release or else [Licences.deedUrl], and the credit as
     * [Picture.attribution] only where the licence asks for it. A live row keeps the texts and URLs its source
     * gave, the same rules filling only what it lacks.
     */
    fun of(row: CatalogRow, separator: String, cardWidth: Int = CARD_WIDTH): Picture {
        val file = row.commonsFile?.takeIf { it.isNotBlank() }
        val artists = row.artists.filter { it.isNotBlank() }
        val date = row.date ?: row.inception?.toString()
        return Picture(
            id = row.id,
            title = row.shownTitle,
            credit = listOfNotNull(artists.firstOrNull(), date).joinToString(separator),
            thumbUrl = row.thumbUrl ?: file?.let { CommonsUrls.url(it, cardWidth, row.width) }.orEmpty(),
            fullUrl = row.fullUrl ?: file?.let { CommonsUrls.url(it, CommonsMapping.FULL_WIDTH, row.width) }.orEmpty(),
            artist = artists.joinToString(separator).ifEmpty { null },
            date = date,
            collection = row.collection,
            pageUrl = row.pageUrl ?: row.wikidataId?.let(::wikidataPage),
            licence = row.licence,
            licenceUrl = row.licenceUrl ?: Licences.deedUrl(row.licence),
            width = row.width,
            height = row.height,
            commonsFile = file,
            attribution = row.attribution?.takeIf { row.attr && it.isNotBlank() },
        )
    }
}

/** Licence URLs the app knows without the source telling them. Pure. */
object Licences {
    private val CC = Regex("^CC (BY|BY-SA) (1\\.0|2\\.0|2\\.5|3\\.0|4\\.0)(?: ([a-z]{2,3}))?$", RegexOption.IGNORE_CASE)
    private val CC0 = Regex("^CC0(?: 1\\.0)?$", RegexOption.IGNORE_CASE)

    /**
     * The Creative Commons deed of a Commons licence short name: `CC0` (`CC0 1.0`), `CC BY <v>`,
     * `CC BY-SA <v>`, with a jurisdiction port (`CC BY-SA 3.0 igo`, `CC BY-SA 2.0 fr`) before 4.0; `null` for
     * any other (public domain, `PDM-owner`, `FAL`, …), never a guess.
     */
    fun deedUrl(shortName: String?): String? {
        val s = shortName?.trim() ?: return null
        if (CC0.matches(s)) return "https://creativecommons.org/publicdomain/zero/1.0/"
        val m = CC.matchEntire(s) ?: return null
        val (kind, version, port) = m.destructured
        if (port.isNotEmpty() && version == "4.0") return null
        val path = listOf(kind.lowercase(), version, port.lowercase()).filter { it.isNotEmpty() }.joinToString("/")
        return "https://creativecommons.org/licenses/$path/"
    }
}
