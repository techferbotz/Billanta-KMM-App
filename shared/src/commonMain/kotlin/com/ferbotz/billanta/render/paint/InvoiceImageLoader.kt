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
 */
class InvoiceImageLoader(private val client: HttpClient) {

    private val mutex = Mutex()
    private val cache = HashMap<String, ImageBitmap?>()

    suspend fun load(urls: List<String>): Map<String, ImageBitmap> {
        val wanted = urls.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()

        val missing = mutex.withLock { wanted.filterNot { cache.containsKey(it) } }
        missing.forEach { url ->
            val bitmap = fetch(url)
            mutex.withLock { cache[url] = bitmap }
        }

        return mutex.withLock {
            wanted.mapNotNull { url -> cache[url]?.let { url to it } }.toMap()
        }
    }

    private suspend fun fetch(url: String): ImageBitmap? = try {
        val response = client.get(url)
        if (response.status.isSuccess()) decodeImageBitmap(response.readRawBytes()) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

/** Intrinsic sizes in points, for templates that give an image only one dimension or none. */
fun Map<String, ImageBitmap>.intrinsicSizes(): Map<String, SizePt> =
    mapValues { (_, bitmap) -> SizePt(bitmap.width.toFloat(), bitmap.height.toFloat()) }
