package donat.teledrive.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Upsert
import donat.teledrive.core.SafetyState
import donat.teledrive.core.TransferState
import kotlinx.coroutines.flow.Flow

/** Folders in the Trash, directly or below a trashed folder: they and their files are hidden everywhere except Trash. */
private const val GONE = "WITH RECURSIVE gone(id) AS (SELECT id FROM folders WHERE driveId = :driveId AND trashedAt IS NOT NULL " +
    "UNION SELECT f.id FROM folders f JOIN gone g ON f.parentId = g.id) "
private const val LIVE_FILE = "driveId = :driveId AND trashedAt IS NULL AND versionOf IS NULL AND (folderId IS NULL OR folderId NOT IN (SELECT id FROM gone))"
private const val LIVE_FOLDER = "driveId = :driveId AND id NOT IN (SELECT id FROM gone)"

@Dao
interface DriveDao {
    @Query("SELECT * FROM drives ORDER BY createdAt")
    fun drives(): Flow<List<Drive>>

    @Query("SELECT * FROM folders WHERE driveId = :driveId AND parentId IS :parentId AND trashedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun folders(driveId: String, parentId: String?): Flow<List<Folder>>

    @Query("SELECT * FROM files WHERE driveId = :driveId AND folderId IS :folderId AND trashedAt IS NULL AND versionOf IS NULL ORDER BY name COLLATE NOCASE")
    fun files(driveId: String, folderId: String?): Flow<List<DriveFile>>

    /** Transfers of one drive only (several drives can share the DB). */
    @Query("SELECT t.* FROM transfers t JOIN files f ON f.id = t.fileId WHERE f.driveId = :driveId ORDER BY t.createdAt DESC")
    fun transfers(driveId: String): Flow<List<Transfer>>

    @Query("SELECT t.* FROM transfers t JOIN files f ON f.id = t.fileId WHERE f.driveId = :driveId AND t.state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') ORDER BY t.createdAt")
    suspend fun pendingTransfers(driveId: String): List<Transfer>

    @Query("SELECT t.* FROM transfers t JOIN files f ON f.id = t.fileId WHERE f.driveId = :driveId AND t.state IN (:states)")
    suspend fun transfersIn(driveId: String, states: List<TransferState>): List<Transfer>

    @Query("${GONE}SELECT * FROM files WHERE $LIVE_FILE") fun activeFiles(driveId: String): Flow<List<DriveFile>>
    @Query("${GONE}SELECT * FROM folders WHERE $LIVE_FOLDER ORDER BY name COLLATE NOCASE") fun activeFolders(driveId: String): Flow<List<Folder>>
    @Query("${GONE}SELECT * FROM files WHERE $LIVE_FILE AND name LIKE '%' || :q || '%' ORDER BY name COLLATE NOCASE")
    fun searchFiles(driveId: String, q: String): Flow<List<DriveFile>>
    @Query("${GONE}SELECT * FROM folders WHERE $LIVE_FOLDER AND name LIKE '%' || :q || '%' ORDER BY name COLLATE NOCASE")
    fun searchFolders(driveId: String, q: String): Flow<List<Folder>>
    @Query("${GONE}SELECT * FROM files WHERE $LIVE_FILE AND favorite = 1 ORDER BY name COLLATE NOCASE") fun favoriteFiles(driveId: String): Flow<List<DriveFile>>
    @Query("${GONE}SELECT * FROM files WHERE $LIVE_FILE ORDER BY updatedAt DESC LIMIT 100") fun recentFiles(driveId: String): Flow<List<DriveFile>>
    @Query("SELECT * FROM files WHERE driveId = :driveId AND trashedAt IS NOT NULL AND versionOf IS NULL ORDER BY trashedAt DESC") fun trashedFiles(driveId: String): Flow<List<DriveFile>>
    @Query("SELECT * FROM folders WHERE driveId = :driveId AND trashedAt IS NOT NULL ORDER BY trashedAt DESC") fun trashedFolders(driveId: String): Flow<List<Folder>>

    @Query("SELECT COUNT(*) FROM files WHERE driveId = :driveId AND trashedAt IS NULL AND versionOf IS NULL") suspend fun fileCount(driveId: String): Int
    @Query("SELECT COUNT(*) FROM folders WHERE driveId = :driveId AND trashedAt IS NULL") suspend fun folderCount(driveId: String): Int
    @Query("SELECT * FROM transfers WHERE id = :id") suspend fun transfer(id: String): Transfer?
    @Query("SELECT * FROM transfers WHERE fileId = :fileId") suspend fun transfersOf(fileId: String): List<Transfer>
    @Query("SELECT * FROM files WHERE id = :id") suspend fun file(id: String): DriveFile?
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): Folder?
    @Query("SELECT * FROM folders WHERE parentId = :id") suspend fun childFolders(id: String): List<Folder>
    @Query("SELECT * FROM files WHERE folderId = :id") suspend fun childFiles(id: String): List<DriveFile>
    @Query("SELECT * FROM files WHERE driveId = :driveId AND sha256 = :sha AND size = :size AND id != :exceptId AND safety IN ('REMOTE_SAFE', 'VERIFIED', 'CONFLICT') LIMIT 1")
    suspend fun duplicateOf(driveId: String, sha: String, size: Long, exceptId: String): DriveFile?
    @Query("SELECT COUNT(*) FROM file_parts p JOIN files f ON f.id = p.fileId WHERE f.driveId = :driveId AND p.remoteRef = :ref")
    suspend fun refCount(driveId: String, ref: String): Int
    @Query("DELETE FROM files WHERE id = :id") suspend fun deleteFile(id: String)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: String)
    @Query("DELETE FROM transfers WHERE state IN ('COMPLETED', 'CANCELLED')") suspend fun clearFinishedTransfers()
    @Query("SELECT * FROM file_parts WHERE fileId = :fileId ORDER BY partIndex") suspend fun parts(fileId: String): List<FilePart>
    @Query("SELECT * FROM sync_state WHERE driveId = :driveId") suspend fun syncState(driveId: String): SyncState?

    @Query("SELECT * FROM folders WHERE driveId = :driveId") suspend fun allFolders(driveId: String): List<Folder>
    @Query("SELECT * FROM files WHERE driveId = :driveId AND safety IN ('REMOTE_SAFE', 'VERIFIED', 'CONFLICT', 'ERROR')") suspend fun remoteFiles(driveId: String): List<DriveFile>
    @Query("SELECT p.* FROM file_parts p JOIN files f ON f.id = p.fileId WHERE f.driveId = :driveId AND f.safety IN ('REMOTE_SAFE', 'VERIFIED', 'CONFLICT', 'ERROR')")
    suspend fun remoteParts(driveId: String): List<FilePart>

    @Query("SELECT * FROM files WHERE versionOf = :id ORDER BY createdAt DESC") fun versions(id: String): Flow<List<DriveFile>>
    @Query("SELECT * FROM files WHERE versionOf = :id") suspend fun versionList(id: String): List<DriveFile>
    @Query("SELECT * FROM files WHERE driveId = :driveId AND folderId IS :folderId AND name = :name AND id != :exceptId AND versionOf IS NULL AND trashedAt IS NULL AND safety IN ('REMOTE_SAFE', 'VERIFIED', 'CONFLICT') LIMIT 1")
    suspend fun sameName(driveId: String, folderId: String?, name: String, exceptId: String): DriveFile?
    /** Makes [newId] the current file: [oldId] and all its older versions become versions of it. */
    @Query("UPDATE files SET versionOf = :newId WHERE id = :oldId OR versionOf = :oldId") suspend fun supersede(oldId: String, newId: String)
    @Query("SELECT * FROM files WHERE driveId = :driveId AND safety = 'REMOTE_SAFE'") suspend fun unverifiedFiles(driveId: String): List<DriveFile>
    /** Safety is sync state, not a user edit — it must not change the "Modified" date. */
    @Query("UPDATE files SET safety = :safety WHERE id = :id") suspend fun setSafety(id: String, safety: SafetyState)
    @Query("DELETE FROM drives WHERE id = :id") suspend fun deleteDrive(id: String)
    @Query("DELETE FROM drives") suspend fun deleteAllDrives()
    @Query("DELETE FROM transfers") suspend fun deleteAllTransfers()

    @Query("DELETE FROM transfers WHERE fileId IN (SELECT id FROM files WHERE driveId = :driveId)") suspend fun deleteTransfersOf(driveId: String)

    /** Every backend object any file of the drive points to — trashed files, old versions and unfinished uploads included. */
    @Query("SELECT DISTINCT p.remoteRef FROM file_parts p JOIN files f ON f.id = p.fileId WHERE f.driveId = :driveId")
    suspend fun allRefs(driveId: String): List<String>

    /** Recovered files start without hashes; the first full download fills them in (never overwrites a known hash). */
    @Query("UPDATE files SET sha256 = :sha WHERE id = :id AND sha256 IS NULL") suspend fun fillFileSha(id: String, sha: String)
    @Query("UPDATE file_parts SET sha256 = :sha WHERE fileId = :fileId AND partIndex = :index AND sha256 = ''") suspend fun fillPartSha(fileId: String, index: Int, sha: String)

    /** The versions of a deleted file move under [to], which becomes the current file again. */
    @Query("UPDATE files SET versionOf = CASE WHEN id = :to THEN NULL ELSE :to END WHERE versionOf = :from")
    suspend fun reparentVersions(from: String, to: String)

    /** Removes a drive from this PC: its transfers, then the drive (cascade: folders, files, parts). Other drives untouched. */
    @Transaction
    suspend fun forgetDrive(driveId: String) {
        deleteTransfersOf(driveId)
        deleteDrive(driveId)
    }

    @Upsert suspend fun upsert(drive: Drive)
    @Upsert suspend fun upsert(folder: Folder)
    @Upsert suspend fun upsert(file: DriveFile)
    @Upsert suspend fun upsert(part: FilePart)
    @Upsert suspend fun upsert(transfer: Transfer)
    @Upsert suspend fun upsert(state: SyncState)
    @Upsert suspend fun upsertFolders(folders: List<Folder>)
    @Upsert suspend fun upsertFiles(files: List<DriveFile>)
    @Upsert suspend fun upsertParts(parts: List<FilePart>)

    /** Replaces everything known about a drive with the manifest's content (cascade wipes old folders/files/parts). */
    @Transaction
    suspend fun restore(drive: Drive, m: Manifest, now: Long, remoteVersion: String?) {
        deleteDrive(drive.id)
        upsert(drive)
        upsertFolders(m.folders)
        upsertFiles(m.files)
        upsertParts(m.parts)
        upsert(SyncState(drive.id, m.revision, now, "OK", remoteVersion = remoteVersion))
    }

    /** Applies a merged manifest. Only items that were in [previous] (the local snapshot) can be removed; pending uploads are untouched. */
    @Transaction
    suspend fun applyMerged(merged: Manifest, previous: Manifest, state: SyncState) {
        val files = merged.files.map { it.id }.toSet()
        val folders = merged.folders.map { it.id }.toSet()
        previous.files.filter { it.id !in files }.forEach { deleteFile(it.id) }
        previous.folders.filter { it.id !in folders }.forEach { deleteFolder(it.id) }
        upsertFolders(merged.folders)
        upsertFiles(merged.files)
        upsertParts(merged.parts)
        upsert(state)
    }
}

@Database(
    entities = [Drive::class, Folder::class, DriveFile::class, FilePart::class, Transfer::class, SyncState::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DriveDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
