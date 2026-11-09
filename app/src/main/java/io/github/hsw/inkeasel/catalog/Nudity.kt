package io.github.hsw.inkeasel.catalog

/**
 * Nudity of catalog paintings, pure. [EXPLICIT] is the `explicit` part of `tools/catalog-nsfw.txt` (genitals
 * in focus or a sexual act shown), built in so it applies to the release already published: such a painting
 * never shows in the Catalog ([CatalogRank.place] hides it) and the starter set never carries it. A unit test
 * checks this set against the file.
 */
object Nudity {
    /** Wikidata ids (bare, `Q…`) of the paintings listed as explicit. */
    val EXPLICIT: Set<String> = setOf(
        "Q334138", // L'Origine du monde (Gustave Courbet)
        "Q326503", // The Sleepers (Gustave Courbet)
        "Q1652981", // Tarquin and Lucretia (Titian)
        "Q10467459", // Woman with White Stockings (La Femme aux bas blancs) (Gustave Courbet)
        "Q14855249", // Rolla (Henri Gervex)
        "Q17020127", // Leda and the Swan (Peter Paul Rubens)
        "Q834119", // Oinochoe by the Shuvalov Painter (Shuvalov Painter)
        "Q17494548", // Nymph abducted by a faun (Alexandre Cabanel)
        "Q139556301", // The Sin
        "Q47384936", // The Two Friends (Albert Marquet)
    )

    fun isExplicit(wikidataId: String): Boolean = wikidataId in EXPLICIT
}
