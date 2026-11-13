package donat.teledrive.data

import donat.teledrive.core.SafetyState

class MergeResult(val manifest: Manifest, val conflicts: List<String>)

/**
 * Three-way merge of drive metadata between this PC ([local]) and another PC ([remote]).
 * [base] is the last manifest both sides agreed on; null = unknown, so nothing is treated as deleted.
 * File bytes are immutable, so conflicts are metadata-only (rename/move/trash/favorite). A change made on
 * only one side always wins; a change on both sides keeps the newer one and marks the file CONFLICT.
 */
fun merge(base: Manifest?, local: Manifest, remote: Manifest): MergeResult {
    val conflicts = mutableListOf<String>()
    val conflictedFiles = mutableSetOf<String>()
    val folders = mergeById(base?.folders, local.folders, remote.folders, { it.id }, { it.updatedAt }, { "Folder “${it.name}”" }, conflicts, mutableSetOf())
    // Safety (verified, error…) is sync state, not a user edit: merged on its own so it never causes a conflict or a resurrection.
    fun meta(l: List<DriveFile>?) = l?.map { it.copy(safety = SafetyState.REMOTE_SAFE) }
    fun safety(m: Manifest?) = m?.files?.associate { it.id to it.safety }.orEmpty()
    val (bs, ls, rs) = Triple(safety(base), safety(local), safety(remote))
    val files = mergeById(meta(base?.files), meta(local.files)!!, meta(remote.files)!!, { it.id }, { it.updatedAt }, { "“${it.name}”" }, conflicts, conflictedFiles)
        .map { f ->
            val s = when {
                f.id in conflictedFiles -> SafetyState.CONFLICT
                ls[f.id] == null || (ls[f.id] == bs[f.id] && rs[f.id] != null) -> rs.getValue(f.id) // only the other PC changed it
                else -> ls.getValue(f.id)
            }
            f.copy(safety = s)
        }
    val fileIds = files.map { it.id }.toSet()
    val parts = (local.parts + remote.parts).filter { it.fileId in fileIds }.distinctBy { it.fileId to it.partIndex }
    return MergeResult(
        remote.copy(revision = maxOf(local.revision, remote.revision), folders = folders, files = files, parts = parts),
        conflicts,
    )
}

/** True when two manifests describe the same drive contents (revision/timestamps ignored). */
fun sameContent(a: Manifest, b: Manifest) =
    a.folders.toSet() == b.folders.toSet() && a.files.toSet() == b.files.toSet() && a.parts.toSet() == b.parts.toSet()

private fun <T : Any> mergeById(
    base: List<T>?, local: List<T>, remote: List<T>,
    id: (T) -> String, updated: (T) -> Long, label: (T) -> String,
    conflicts: MutableList<String>, conflicted: MutableSet<String>,
): List<T> {
    val b = base.orEmpty().associateBy(id)
    val l = local.associateBy(id)
    val r = remote.associateBy(id)
    return (b.keys + l.keys + r.keys).mapNotNull { k ->
        val bv = b[k]
        val lv = l[k]
        val rv = r[k]
        when {
            lv == rv -> lv
            lv == bv -> rv // only the other PC changed it (or deleted it)
            rv == bv -> lv // only this PC changed it (or deleted it)
            lv == null -> rv!!.also { conflicts += "${label(it)} was deleted here but changed on another PC — kept it"; conflicted += k }
            rv == null -> lv.also { conflicts += "${label(it)} was deleted on another PC but changed here — kept it"; conflicted += k }
            else -> (if (updated(lv) >= updated(rv)) lv else rv).also { conflicts += "${label(lv)} was changed on two PCs — kept the newer change"; conflicted += k }
        }
    }
}
