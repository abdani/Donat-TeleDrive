package donat.teledrive.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey
import donat.teledrive.core.Direction
import donat.teledrive.core.SafetyState
import donat.teledrive.core.TransferState
import kotlinx.serialization.Serializable

// ponytail: Room entities double as domain models; split them out if the UI starts needing shapes the DB doesn't have.
// All ids are app-owned stable UUID strings; provider ids only appear in FilePart.remoteRef / Drive.remoteId.

@Entity(tableName = "drives")
data class Drive(
    @PrimaryKey val id: String,
    val name: String,
    val providerType: String,
    val remoteId: String,
    val createdAt: Long,
    val settingsJson: String = "{}",
)

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(Drive::class, ["id"], ["driveId"], onDelete = CASCADE)],
    indices = [Index("driveId", "parentId")],
)
@Serializable
data class Folder(
    @PrimaryKey val id: String,
    val driveId: String,
    val parentId: String?,
    val name: String,
    val favorite: Boolean = false,
    val trashedAt: Long? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "files",
    foreignKeys = [ForeignKey(Drive::class, ["id"], ["driveId"], onDelete = CASCADE)],
    indices = [Index("driveId", "folderId"), Index("sha256"), Index("name")],
)
@Serializable
data class DriveFile(
    @PrimaryKey val id: String,
    val driveId: String,
    val folderId: String?,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String?,
    val safety: SafetyState,
    val favorite: Boolean = false,
    val trashedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Set when a newer upload with the same name replaced this file: id of the current version. Hidden from listings. */
    val versionOf: String? = null,
)

@Entity(
    tableName = "file_parts",
    primaryKeys = ["fileId", "partIndex"],
    foreignKeys = [ForeignKey(DriveFile::class, ["id"], ["fileId"], onDelete = CASCADE)],
)
@Serializable
data class FilePart(
    val fileId: String,
    val partIndex: Int,
    val remoteRef: String,
    val offset: Long,
    val size: Long,
    val sha256: String,
)

@Entity(tableName = "transfers", indices = [Index("state")])
data class Transfer(
    @PrimaryKey val id: String,
    val fileId: String,
    val direction: Direction,
    val localPath: String,
    val state: TransferState,
    val bytesDone: Long = 0, // confirmed bytes only, never optimistic
    val bytesTotal: Long,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val driveId: String,
    val manifestRevision: Long = 0,
    val lastSyncAt: Long? = null,
    val status: String = "IDLE",
    val error: String? = null,
    /** [StorageProvider.manifestVersion] this PC last read or wrote; a different remote value = another PC saved. */
    val remoteVersion: String? = null,
)
