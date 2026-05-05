package com.kafkasl.phonewhisper.history

import android.content.Context
import java.io.File

/**
 * High-level operations for the transcription history. Wraps the Room DAO and
 * audio-file persistence (in app-private storage at filesDir/recordings).
 *
 * All methods are synchronous and must be called from a background thread.
 * Audio file I/O happens here so the calling code does not have to know where
 * recordings are stored on disk.
 */
class HistoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = HistoryDatabase.get(appContext)
    private val dao = db.historyDao()

    private val recordingsDir: File
        get() = File(appContext.filesDir, "recordings").apply { if (!exists()) mkdirs() }

    /** Save a WAV byte array to disk under the given timestamp. Returns the absolute file path. */
    fun saveAudio(wav: ByteArray, timestamp: Long): String {
        val file = File(recordingsDir, "$timestamp.wav")
        file.writeBytes(wav)
        return file.absolutePath
    }

    /** Read audio bytes back from a saved path; null if the file is missing (e.g. expired). */
    fun loadAudio(path: String?): ByteArray? {
        if (path == null) return null
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }

    fun insert(entry: HistoryEntry): Long = dao.insert(entry)

    fun update(entry: HistoryEntry) = dao.update(entry)

    fun getAll(): List<HistoryEntry> = dao.getAll()

    fun getById(id: Long): HistoryEntry? = dao.getById(id)

    fun deleteById(id: Long) {
        dao.getById(id)?.audioPath?.let { File(it).delete() }
        dao.deleteById(id)
    }

    /** Clears the database AND all audio files on disk. */
    fun deleteAll() {
        dao.deleteAll()
        recordingsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Delete audio files older than the cutoff timestamp and clear their paths in the DB.
     * History rows themselves are kept indefinitely (only audio expires).
     */
    fun pruneAudioOlderThan(cutoff: Long): Int {
        val expiredPaths = dao.audioPathsOlderThan(cutoff)
        var deleted = 0
        expiredPaths.forEach {
            if (File(it).delete()) deleted++
        }
        dao.clearAudioPathsOlderThan(cutoff)
        return deleted
    }
}
