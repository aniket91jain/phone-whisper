package com.kafkasl.phonewhisper.history

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Periodic background task that deletes saved WAV files older than 14 days,
 * implementing the audio retention half of the History feature.
 *
 * History rows themselves are kept indefinitely (text retention is until the user
 * manually clears). Only the audio files (which are the largest on-disk artefacts
 * and are only useful for retry) expire.
 */
class AudioPruneWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        return try {
            val repo = HistoryRepository(applicationContext)
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val deleted = repo.pruneAudioOlderThan(cutoff)
            Log.i(TAG, "Audio prune complete: $deleted files deleted")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Audio prune failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AudioPruneWorker"
        private const val WORK_NAME = "phone_whisper_audio_prune"
        private const val RETENTION_DAYS = 14L
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(RETENTION_DAYS)

        /**
         * Schedule the daily prune. KEEP policy means existing schedules are preserved
         * across app launches — we don't reschedule on every onCreate.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AudioPruneWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
