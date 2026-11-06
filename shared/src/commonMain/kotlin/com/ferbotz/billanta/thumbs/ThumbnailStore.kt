package com.ferbotz.billanta.thumbs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.FileSystem
import okio.Path

/**
 * The list thumbnails on disk: one JPEG per invoice, named `<invoiceId>.<fingerprint>.jpg`, in the
 * app's cache directory (FIELDS_APP_GUIDE.md §12.2).
 *
 * The folder belongs to the invoices on this phone, the way the local database does: it is kept
 * through sign-out and through re-owning, and cleared by the same wipe that clears the database
 * when a different account signs in. It is only a cache: the OS may empty it at any time, and the
 * pictures are drawn again.
 *
 * Not thread-safe: the thumbnail service confines every call to its one worker. [files] is the
 * exception, and is what the cards read.
 */
class ThumbnailStore(private val fs: FileSystem, private val dir: Path) {

    private val _files = MutableStateFlow<Map<String, Path>>(emptyMap())

    /** Invoice id → its picture. Empty until [load]. */
    val files: StateFlow<Map<String, Path>> = _files.asStateFlow()

    /** The fingerprint [invoiceId]'s picture was drawn for, or null when it has none. */
    fun fingerprintOf(invoiceId: String): String? = _files.value[invoiceId]?.let { parse(it.name)?.second }

    /**
     * Reads the folder once. A temp file left by a crash is deleted, and so is the older of two
     * pictures for one invoice — the app died between writing the new one and deleting the old.
     */
    fun load() {
        fs.createDirectories(dir)
        val found = HashMap<String, Path>()
        fs.list(dir).forEach { path ->
            val id = parse(path.name)?.first
            if (id == null) {
                fs.delete(path, mustExist = false)
                return@forEach
            }
            val other = found[id]
            if (other == null || modified(path) > modified(other)) {
                other?.let { fs.delete(it, mustExist = false) }
                found[id] = path
            } else {
                fs.delete(path, mustExist = false)
            }
        }
        _files.value = found
    }

    /** Saves [jpeg] as [invoiceId]'s picture, replacing the one it had. Null for an unusable id. */
    fun write(invoiceId: String, fingerprint: String, jpeg: ByteArray): Path? {
        if (!SAFE_ID.matches(invoiceId) || !FINGERPRINT.matches(fingerprint)) return null
        fs.createDirectories(dir)
        val target = dir / "$invoiceId.$fingerprint$EXTENSION"
        // Written aside and moved into place, so a half-written file never has a picture's name.
        val temp = dir / "$invoiceId.$fingerprint$EXTENSION.tmp"
        fs.write(temp) { write(jpeg) }
        fs.atomicMove(temp, target)
        _files.value[invoiceId]?.takeIf { it != target }?.let { fs.delete(it, mustExist = false) }
        _files.value = _files.value + (invoiceId to target)
        return target
    }

    fun delete(invoiceId: String) {
        val path = _files.value[invoiceId] ?: return
        fs.delete(path, mustExist = false)
        _files.value = _files.value - invoiceId
    }

    /** Deletes the pictures of invoices that are gone: deleted here, or tombstoned by a pull. */
    fun retainOnly(liveIds: Set<String>) {
        val gone = _files.value.keys - liveIds
        if (gone.isEmpty()) return
        gone.forEach { id -> _files.value[id]?.let { fs.delete(it, mustExist = false) } }
        _files.value = _files.value - gone
    }

    /** Everything, for the account-change wipe. */
    fun clear() {
        fs.deleteRecursively(dir, mustExist = false)
        _files.value = emptyMap()
    }

    private fun modified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L

    private fun parse(name: String): Pair<String, String>? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.removeSuffix(EXTENSION)
        val dot = stem.lastIndexOf('.')
        if (dot <= 0) return null
        val id = stem.substring(0, dot)
        val fingerprint = stem.substring(dot + 1)
        return if (SAFE_ID.matches(id) && FINGERPRINT.matches(fingerprint)) id to fingerprint else null
    }

    private companion object {
        const val EXTENSION = ".jpg"
        /** Invoice ids are UUIDs; anything else is refused rather than turned into a path. */
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val FINGERPRINT = Regex("[0-9a-f]{16}")
    }
}
