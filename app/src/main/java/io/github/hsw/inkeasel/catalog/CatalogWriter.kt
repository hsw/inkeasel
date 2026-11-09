package io.github.hsw.inkeasel.catalog

/**
 * Where a write to the catalog's tables runs: under the store's one write lock ([CatalogStore]), so that writes
 * never overlap; [write] returns what its block returns.
 */
interface CatalogWriter {
    suspend fun <T> write(block: suspend () -> T): T

    companion object {
        /** In the caller's own context, with no lock (a caller that is the only writer). */
        val DIRECT: CatalogWriter = object : CatalogWriter {
            override suspend fun <T> write(block: suspend () -> T): T = block()
        }
    }
}
