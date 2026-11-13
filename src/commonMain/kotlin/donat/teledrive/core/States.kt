package donat.teledrive.core

/** How safe a file is. Never show a file as backed up before [VERIFIED]. */
enum class SafetyState { LOCAL_ONLY, UPLOADING, REMOTE_SAFE, VERIFIED, SYNCING, CONFLICT, ERROR }

enum class Direction { UPLOAD, DOWNLOAD }

enum class TransferState {
    QUEUED, PREPARING, RUNNING, PAUSED, RETRY_WAIT, FAILED, COMPLETED, CANCELLED;

    val isTerminal get() = this == COMPLETED || this == CANCELLED

    fun canMoveTo(next: TransferState): Boolean = when (this) {
        QUEUED -> next in setOf(PREPARING, PAUSED, CANCELLED)
        PREPARING -> next in setOf(RUNNING, PAUSED, RETRY_WAIT, FAILED, CANCELLED)
        RUNNING -> next in setOf(PAUSED, RETRY_WAIT, FAILED, COMPLETED, CANCELLED)
        PAUSED, FAILED -> next == QUEUED || next == CANCELLED // resume / manual retry
        RETRY_WAIT -> next in setOf(QUEUED, PAUSED, FAILED, CANCELLED)
        COMPLETED, CANCELLED -> false
    }
}
