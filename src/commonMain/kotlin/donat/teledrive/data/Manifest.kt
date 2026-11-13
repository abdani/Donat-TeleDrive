package donat.teledrive.data

import donat.teledrive.core.RemoteObject
import donat.teledrive.core.SafetyState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Remote Drive Manifest: everything needed to rebuild the local index on a clean device. */
@Serializable
data class Manifest(
    val format: Int = FORMAT,
    val driveId: String,
    val driveName: String,
    val revision: Long,
    val updatedAt: Long,
    val folders: List<Folder>,
    val files: List<DriveFile>,
    val parts: List<FilePart>,
) {
    fun encode(): ByteArray = json.encodeToString(this).encodeToByteArray()

    companion object {
        const val FORMAT = 1
        const val FILE_PREFIX = "donatteledrive-manifest"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(bytes: ByteArray): Manifest = json.decodeFromString<Manifest>(bytes.decodeToString()).also {
            require(it.format <= FORMAT) { "Manifest format ${it.format} needs a newer app version" }
        }
    }
}

private val PIECE = Regex("""^(.*)\.part(\d+)of(\d+)$""")

/**
 * Backend objects → files (name to its pieces in order). "name.partNofM" pieces are joined back when exactly the pieces
 * 1..M are there; an incomplete or ambiguous set stays one file per object, so nothing is ever hidden or mis-joined.
 */
fun groupObjects(objects: List<RemoteObject>): List<Pair<String, List<RemoteObject>>> {
    val joined = mutableListOf<Pair<String, List<RemoteObject>>>()
    val used = HashSet<String>()
    objects.mapNotNull { o -> PIECE.matchEntire(o.name)?.groupValues?.let { (_, name, n, of) -> Triple(o, name to of.toInt(), n.toInt()) } }
        .groupBy { it.second }
        .forEach { (key, pieces) ->
            val (name, total) = key
            val byIndex = pieces.associateBy { it.third }
            if (total > 1 && pieces.size == total && (1..total).all { it in byIndex }) {
                joined += name to (1..total).map { byIndex.getValue(it).first }
                used += pieces.map { it.first.ref }
            }
        }
    return joined + objects.filter { it.ref !in used }.map { it.name to listOf(it) }
}

/**
 * Only files whose content is safely remote go into the manifest: those already saved, plus [pending] uploads whose
 * bytes are all in the backend — they become REMOTE_SAFE in the DB only after this manifest is written.
 */
suspend fun DriveDao.snapshot(drive: Drive, revision: Long, now: Long, pending: Set<String> = emptySet()) = Manifest(
    driveId = drive.id,
    driveName = drive.name,
    revision = revision,
    updatedAt = now,
    folders = allFolders(drive.id),
    files = (remoteFiles(drive.id) + pending.mapNotNull { file(it)?.copy(safety = SafetyState.REMOTE_SAFE) }).distinctBy { it.id },
    parts = (remoteParts(drive.id) + pending.flatMap { parts(it) }).distinctBy { it.fileId to it.partIndex },
)
