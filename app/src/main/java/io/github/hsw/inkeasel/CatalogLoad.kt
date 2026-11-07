package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException

/**
 * One load of a catalog tab from [src]. With a [cache] (a remote source): a complete list is saved; a list cut
 * short ([Listing.complete] false) only when nothing is saved yet or it is not smaller than the saved one, so
 * it never shrinks the fallback; an empty answer is shown as is but never saved (see [CatalogCache.write]); on
 * a failure the saved list is shown with the reason ([CatalogState.failed]). Without one (the local folder,
 * read from disk anyway) nothing is saved or read. Pure: [now] and [log] are injected.
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
    CatalogState.failed(CatalogError.of(e), cache?.read(src.id))
}

/** A tab that loads again when it gets focus: never loaded, failed, or showing a saved list (not while it retries). */
fun CatalogState?.needsLoad(): Boolean =
    this == null || this is CatalogState.Failed || (this is CatalogState.Loaded && staleSince != null && !retrying)
