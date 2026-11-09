package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException

/**
 * What a tab that loads its whole list shows ([loadCatalog]): "My pictures". The Catalog tab pages from the
 * catalog database instead. Pure, like the load rules.
 */
sealed interface CatalogState {
    data object Loading : CatalogState

    /** The list to show. */
    data class Loaded(val items: List<Picture>) : CatalogState

    /** [reason] is a whole sentence, shown as is with **Retry**. */
    data class Failed(val reason: Txt) : CatalogState
}

/**
 * One load of a tab from [src] (the user's pictures, read from disk: nothing is saved). A failure is about the
 * user's pictures, not a catalog. Pure: [log] is injected.
 */
suspend fun loadCatalog(src: Source, log: (String) -> Unit = {}): CatalogState = try {
    CatalogState.Loaded(src.load().items)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log("${src.id} failed: $e")
    CatalogState.Failed(e.userText(R.string.local_failed))
}

/** A tab that loads again when it gets focus: never loaded, or failed. */
fun CatalogState?.needsLoad(): Boolean = this == null || this is CatalogState.Failed
