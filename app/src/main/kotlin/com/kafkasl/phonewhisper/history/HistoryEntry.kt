package com.kafkasl.phonewhisper.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per transcription attempt. Persisted in the Room "history" table.
 *
 * Status semantics:
 *  - SUCCESS:        Whisper succeeded, and (if polish was enabled) Llama also succeeded.
 *                    polishedTranscript may be null if polish was disabled at recording time.
 *  - POLISH_FAILED:  Whisper succeeded but Llama failed or hit a safety check.
 *                    rawTranscript is filled; polishedTranscript is null. Retry-polish is offered.
 *  - STT_FAILED:     Whisper failed (network, API error, empty result).
 *                    Both rawTranscript and polishedTranscript are null. Retry-transcribe is offered.
 *  - FLAGGED_BAD:    Manually flagged by the user from the History screen.
 */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val audioPath: String?,
    val rawTranscript: String?,
    val polishedTranscript: String?,
    val status: String,
    val errorMessage: String?,
    val targetAppPackage: String?,
    val polishWasEnabled: Boolean
)

object HistoryStatus {
    const val SUCCESS = "SUCCESS"
    const val POLISH_FAILED = "POLISH_FAILED"
    const val STT_FAILED = "STT_FAILED"
    const val FLAGGED_BAD = "FLAGGED_BAD"
}
