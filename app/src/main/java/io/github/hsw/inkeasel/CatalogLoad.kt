package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException

/** What a catalog tab shows ([loadCatalog]); pure, like the load rules. */
sealed interface CatalogState {
    data object Loading : CatalogState

    /**
     * A list to show. [staleSince] is set when the source failed and this is its saved list ([CatalogCache],
     * saved then), with [staleReason] why the fresh one did not come; [retrying] while a retry runs (the
     * stale list stays on screen meanwhile).
     */
    data class Loaded(
        val items: List<Picture>,
        val staleSince: Long? = null,
        val staleReason: Txt? = null,
        val retrying: Boolean = false,
    ) : CatalogState

    /** [reason] is a whole sentence ([CatalogError]), shown as is. */
    data class Failed(val reason: Txt) : CatalogState

    companion object {
        /** The text of [Loading]: the [Source.local] tab reads the user's pictures, not a catalog. */
        fun loadingText(local: Boolean): Txt = Txt(if (local) R.string.local_loading else R.string.catalog_loading)

        /** What a tab shows while it (re)loads after [previous]: a stale list stays, marked as retrying. */
        fun reloading(previous: CatalogState?): CatalogState =
            if (previous is Loaded && previous.staleSince != null) previous.copy(retrying = true) else Loading

        /** After a failed load: the saved list with the [reason] as a note, or the failure itself. */
        fun failed(reason: Txt, cached: CachedCatalog?): CatalogState =
            if (cached != null) Loaded(cached.items, staleSince = cached.savedAt, staleReason = reason) else Failed(reason)
    }
}

/**
 * One load of a catalog tab from [src]. With a [cache] (a remote source): a complete list is saved; a list cut
 * short ([Listing.complete] false) only when nothing is saved yet or it is not smaller than the saved one, so
 * it never shrinks the fallback; an empty answer is shown as is but never saved (see [CatalogCache.write]); on
 * a failure the saved list is shown with the reason ([CatalogState.failed]). Without one (the local folder,
 * read from disk anyway) nothing is saved or read. A [Source.local] failure is about the user's pictures, not
 * the catalog ([CatalogError] speaks of a source). Pure: [now] and [log] are injected.
 */
suspend fun loadCatalog(src: Source, cache: CatalogCache?, now: () -> Long, log: (String) -> Unit = {}): CatalogState = try {
    val got = src.load()
    if (cache != null && got.items.isNotEmpty()) {
        val saved = if (got.complete) null else cache.read(src.id)
        if (saved == null || got.items.size >= saved.items.size) cache.write(src.id, got.items, now())
        else log("catalog ${src.id}: a partial list of ${got.items.size} kept out of the saved ${saved.items.size}")
    }
    CatalogState.Loaded(got.items)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log("catalog ${src.id} failed: $e")
    CatalogState.failed(if (src.local) e.userText(R.string.local_failed) else CatalogError.of(e), cache?.read(src.id))
}

/** A tab that loads again when it gets focus: never loaded, failed, or showing a saved list (not while it retries). */
fun CatalogState?.needsLoad(): Boolean =
    this == null || this is CatalogState.Failed || (this is CatalogState.Loaded && staleSince != null && !retrying)
