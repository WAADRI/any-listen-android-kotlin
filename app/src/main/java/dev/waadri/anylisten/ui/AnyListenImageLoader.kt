package dev.waadri.anylisten.ui

import android.app.Application
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Coil configuration, tuned for a server that is a personal deployment rather than a CDN.
 *
 * Three things matter here, and the first two are the reason this exists at all:
 *
 * 1. **Bounded concurrency.** Coil's defaults run on `Dispatchers.IO`, which will happily open a
 *    dozen parallel requests when a list scrolls. Every cover is a request to the user's own
 *    server (through its `/api/p_static` proxy), so a scroll could otherwise arrive as a burst
 *    that a small box notices. The fetcher dispatcher is capped well below that.
 *
 * 2. **Caching.** A URL is fetched once and then served from disk, so scrolling back up and
 *    reopening the app cost nothing. The server sends long-lived immutable cache headers for
 *    these files, so `respectCacheHeaders` keeps them for weeks.
 *
 * 3. **One HTTP stack.** Requests go through an OkHttp client of our own rather than a second
 *    pool, so connections are reused and the app has one place to reason about timeouts.
 *
 * Laziness is the other half of the story and is not configured here: the lists are
 * `LazyColumn`s, so only the rows actually on screen are composed, and only those rows ask for an
 * image. Combined with per-URL caching, that means one request per cover per app install.
 */
object AnyListenImageLoader {

    /**
     * Concurrent image requests. Small on purpose: this is a personal server, and a phone screen
     * shows only a handful of thumbnails at a time, so a lower ceiling costs nothing visible
     * while keeping a fast scroll from arriving as a burst.
     */
    private const val FETCH_CONCURRENCY = 4

    /** Covers are small; a few hundred of them is a trivial amount of disk. */
    private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

    private const val DISK_CACHE_DIRECTORY = "image_cache"

    /** Memory cache as a fraction of the memory class, matching Coil's own default. */
    private const val MEMORY_CACHE_FRACTION = 0.25

    /**
     * Builds the loader Coil should use for the whole process. Called once from
     * [dev.waadri.anylisten.AnyListenApp.onCreate] and handed to `Coil.setImageLoader`, which is
     * more direct than implementing `ImageLoaderFactory` on the manifest-declared application.
     */
    fun create(application: Application): ImageLoader = ImageLoader.Builder(application)
        .okHttpClient(sharedClient)
        .memoryCache {
            MemoryCache.Builder(application)
                .maxSizePercent(MEMORY_CACHE_FRACTION)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(application.cacheDir.resolve(DISK_CACHE_DIRECTORY))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .respectCacheHeaders(true)
        .networkCachePolicy(CachePolicy.ENABLED)
        .fetcherDispatcher(Executors.newFixedThreadPool(FETCH_CONCURRENCY).asCoroutineDispatcher())
        .build()

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
