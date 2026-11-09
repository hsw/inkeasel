package io.github.hsw.inkeasel

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache

/**
 * Coil's image loader, set once per process: over [Http.client] (its User-Agent, per-host limit and retry),
 * with a disk cache of at most [DISK_CACHE_BYTES] in `cacheDir/`[DISK_CACHE_DIR] (Coil's own default folder,
 * so an upgrade keeps what is cached; the least recently used entries go first). One loader, so one disk cache
 * on that folder: Coil allows only one per folder. Off the main thread Coil enqueues its calls (only a
 * cache-only request runs `execute()`, on the main thread), so the per-host limit counts each thumbnail and a
 * card that is disposed cancels its call.
 */
object Images {
    const val DISK_CACHE_BYTES = 150L * 1024 * 1024
    const val DISK_CACHE_DIR = "image_cache"

    private var installed = false

    /** Makes [loader] Coil's loader, built at its first use; later calls do nothing. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        Coil.setImageLoader { loader(app) }
    }

    /** A new loader with its own disk cache: only [install] calls it outside tests. */
    internal fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(Http.client)
        .crossfade(true)
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(DISK_CACHE_DIR))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .build()
}
