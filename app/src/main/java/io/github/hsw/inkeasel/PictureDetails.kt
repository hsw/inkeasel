package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.CatalogRank

/** One line of the details panel: a [label] and its [value], a `String` from the source or a [Txt]. */
data class DetailRow(val label: Txt, val value: Any)

/**
 * What the details panel ([DetailsPanel], opened by a long press on a card) shows about a [Picture], and
 * how its frame preview is made. Pure: texts are [Txt], the source's own strings are passed as they are.
 */
object PictureDetails {
    /** How the panel's preview is made for the selected frame. */
    sealed interface Preview {
        /** No frame is chosen: the panel says so instead of a preview. */
        data object NoFrame : Preview

        /** The chosen frame cannot be drawn on ([FormatLookup.Unsupported]); [reason] is a whole sentence. */
        data class Unsupported(val reason: Txt) : Preview

        /** Rendered as an upload to [frameName] would be: its size, its crop and its inks ([FrameFormat]). */
        data class Render(val format: FrameFormat, val frameName: String) : Preview
    }

    fun preview(selected: SelectedFrame?): Preview = when (val l = selected?.lookup) {
        null -> Preview.NoFrame
        is FormatLookup.Supported -> Preview.Render(l.format, selected.name)
        is FormatLookup.Unsupported -> Preview.Unsupported(Txt(R.string.details_preview_unsupported, selected.name, l.reason))
    }

    /** The name of a [Picture.source]; `null` for one this build does not know. */
    fun sourceName(source: String): Txt? = when (source) {
        CatalogRank.WIKIDATA -> Txt(R.string.details_source_wikidata)
        ClevelandPaintings.id -> Txt(R.string.details_source_cma)
        CommonsLandscapes.id -> Txt(R.string.details_source_commons)
        LocalImages.ID -> Txt(R.string.tab_local)
        else -> null
    }

    /**
     * The panel's lines for [p], in this order, each only when known: artist, date, collection, source,
     * licence, licence text link, image credit (only where the licence asks for one), original size, web
     * page. The title is the panel's heading, not a line.
     */
    fun rows(p: Picture): List<DetailRow> = buildList {
        fun add(label: Int, value: Any?) {
            if (value != null && value != "") add(DetailRow(Txt(label), value))
        }
        add(R.string.details_artist, p.artist)
        add(R.string.details_date, p.date)
        add(R.string.details_collection, p.collection)
        add(R.string.details_source, sourceName(p.source))
        add(R.string.details_licence, p.licence)
        add(R.string.details_licence_link, p.licenceUrl)
        add(R.string.details_attribution, p.attribution?.takeIf { it.isNotBlank() })
        if (p.width != null && p.height != null) add(R.string.details_size, Txt(R.string.details_size_px, p.width, p.height))
        add(R.string.details_page, p.pageUrl)
    }

    /**
     * Why the preview could not be made: a download failure as the catalog says it ([CatalogError]), a
     * [TxtException] (e.g. an undecodable picture) or any other failure after "No preview:".
     */
    fun previewFailure(e: Throwable): Txt = when (e) {
        is TxtException -> Txt(R.string.details_preview_failed, e.txt)
        else -> CatalogError.of(e) ?: e.userText(R.string.details_preview_failed)
    }
}
