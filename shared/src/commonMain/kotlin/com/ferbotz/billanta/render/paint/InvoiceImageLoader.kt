package com.ferbotz.billanta.render.paint

import androidx.compose.ui.graphics.ImageBitmap
import com.ferbotz.billanta.render.layout.SizePt
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Decodes encoded image bytes (WebP/PNG/JPEG) into a Compose bitmap. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * Fetches and decodes the images a template binds — logo, signature, payment QR.
 *
 * Exports must resolve every image *before* laying out: a logo that is still downloading when the
 * user taps Share would otherwise be silently missing from the file. Results are cached for the
 * app's lifetime, so the second render of the same invoice is instant and works offline.
 *
 * A broken link — the server says there is no such image, or the bytes aren't one — is remembered
 * as missing, and the page is drawn without it. An image that couldn't be reached at all (offline,
 * a server error) is not remembered, so it is tried again next time.
 */
class InvoiceImageLoader(private val client: HttpClient) {

    private sealed interface Entry {
        class Loaded(val bitmap: ImageBitmap) : Entry
        data object Missing : Entry
    }

    private val mutex = Mutex()
    private val cache = HashMap<String, Entry>()

    /** Every image there is to have; one that is broken or out of reach is left out. */
    suspend fun load(urls: List<String>): Map<String, ImageBitmap> = fetchAll(urls).first

    /**
     * Like [load], but null while any image is out of reach rather than broken. A list thumbnail
     * is saved for good, so it waits for its logo instead of being drawn without it.
     */
    suspend fun loadComplete(urls: List<String>): Map<String, ImageBitmap>? =
        fetchAll(urls).let { (images, complete) -> if (complete) images else null }

    private suspend fun fetchAll(urls: List<String>): Pair<Map<String, ImageBitmap>, Boolean> {
        val wanted = urls.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap<String, ImageBitmap>() to true

        val missing = mutex.withLock { wanted.filterNot { cache.containsKey(it) } }
        missing.forEach { url ->
            val entry = fetch(url) ?: return@forEach
            mutex.withLock { cache[url] = entry }
        }

        return mutex.withLock {
            val images = wanted.mapNotNull { url -> (cache[url] as? Entry.Loaded)?.let { url to it.bitmap } }.toMap()
            images to wanted.all { cache.containsKey(it) }
        }
    }

    /** Null when the image couldn't be reached, which is worth another try. */
    private suspend fun fetch(url: String): Entry? = try {
        val response = client.get(url)
        when {
            response.status.isSuccess() -> decodeImageBitmap(response.readRawBytes())?.let { Entry.Loaded(it) } ?: Entry.Missing
            response.status.value in 400..499 -> Entry.Missing
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

/** Intrinsic sizes in points, for templates that give an image only one dimension or none. */
fun Map<String, ImageBitmap>.intrinsicSizes(): Map<String, SizePt> =
    mapValues { (_, bitmap) -> SizePt(bitmap.width.toFloat(), bitmap.height.toFloat()) }
