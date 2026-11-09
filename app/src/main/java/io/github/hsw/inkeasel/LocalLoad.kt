package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException

/**
 * What "My pictures", the tab that loads its whole list, shows ([readLocal]). The Catalog tab pages from the
 * catalog database instead ([CatalogView]). Pure, like the load rules.
 */
sealed interface LocalState {
    data object Loading : LocalState

    /** The list to show. */
    data class Loaded(val items: List<Picture>) : LocalState

    /** [reason] is a whole sentence, shown as is with **Retry**. */
    data class Failed(val reason: Txt) : LocalState
}

/**
 * One load of a tab from [src] (the user's pictures, read from disk: nothing is saved). A failure is about the
 * user's pictures, not a catalog. Pure: [log] is injected.
 */
suspend fun readLocal(src: Source, log: (String) -> Unit = {}): LocalState = try {
    LocalState.Loaded(src.load().items)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log("${src.id} failed: $e")
    LocalState.Failed(e.userText(R.string.local_failed))
}

/** A tab that loads again when it gets focus: never loaded, or failed. */
fun LocalState?.needsLoad(): Boolean = this == null || this is LocalState.Failed
