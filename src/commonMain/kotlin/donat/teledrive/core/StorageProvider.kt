package donat.teledrive.core

open class StorageException(message: String) : Exception(message)

/** Downloaded bytes don't match the stored SHA-256 — the remote copy is damaged (not a network hiccup). */
class IntegrityException(message: String) : StorageException(message)

/** Backend asked us to slow down (Telegram FLOOD_WAIT). Retry after the given delay, never sooner. */
class RateLimitedException(val retryAfterSeconds: Long) : StorageException(
    "Telegram asks to wait ${if (retryAfterSeconds >= 60) "${retryAfterSeconds / 60} min ${retryAfterSeconds % 60} s" else "$retryAfterSeconds s"} (too many requests) — try again then",
)

/** No manifest anywhere in the backend for this drive (never saved, or its data channel was deleted). */
class NoManifestException : StorageException("No Donat TeleDrive manifest found in this channel")

/** One stored object in a drive's backend, whether or not a file of the drive points to it. */
data class RemoteObject(val ref: String, val name: String, val size: Long, val mime: String, val createdAt: Long)

/**
 * Boundary between the app and a storage backend. Telegram is the first implementation.
 * `remoteRef` is opaque to the app (e.g. a Telegram message id) and lives only in file_parts.
 */
interface StorageProvider {
    /** Largest single part the backend accepts; bigger files are split into parts. */
    val maxPartSize: Long

    /** Uploads bytes [offset, offset+size) of a local file. `label` is the name the part is shown with (file name, or "name.part1of3"). */
    suspend fun uploadPart(localPath: String, offset: Long, size: Long, label: String, onProgress: (sentBytes: Long) -> Unit): String

    suspend fun downloadPart(remoteRef: String, destPath: String, onProgress: (receivedBytes: Long) -> Unit)

    /** Reads bytes of one part without downloading all of it (streaming). Not integrity-checked. */
    suspend fun readRange(remoteRef: String, offset: Long, length: Long): ByteArray

    /** Frees any local copy the backend kept for a part (after streaming). */
    suspend fun evict(remoteRef: String) {}

    suspend fun deletePart(remoteRef: String)

    /** Small preview image the backend generated for a part, written to [destPath]. False = backend has none. */
    suspend fun thumbnail(remoteRef: String, destPath: String): Boolean

    /** Opaque id of the newest manifest (cheap to ask). Changes whenever any device writes one. Null = none yet. */
    suspend fun manifestVersion(): String?

    /** Remote Drive Manifest: the metadata source of truth used to rebuild the local index. Null = none exists yet. */
    suspend fun readManifest(): ByteArray?

    /** Writes a new manifest and returns its [manifestVersion]. */
    suspend fun writeManifest(data: ByteArray): String

    /** Background housekeeping when a drive opens (e.g. removing old manifests). Never touches file content. */
    suspend fun tidy() {}

    /** Every manifest version still kept, newest first (to spot another PC's write that raced ours). */
    suspend fun manifestVersions(): List<String> = listOfNotNull(manifestVersion())

    /** A specific manifest version from [manifestVersions]; null if it is gone. */
    suspend fun readManifest(version: String): ByteArray? = if (version == manifestVersion()) readManifest() else null

    /** Every stored object of the drive (files shared from a phone, leftovers, or everything after a manifest loss). */
    suspend fun listObjects(): List<RemoteObject> = emptyList()
}
