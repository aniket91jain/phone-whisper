package com.kafkasl.phonewhisper.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * All DAO methods are synchronous. Callers must invoke them from a background thread
 * (Room enforces this at runtime and will crash if called on the main thread).
 */
@Dao
interface HistoryDao {

    @Insert
    fun insert(entry: HistoryEntry): Long

    @Update
    fun update(entry: HistoryEntry)

    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getAll(): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE id = :id")
    fun getById(id: Long): HistoryEntry?

    @Query("DELETE FROM history WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM history")
    fun deleteAll()

    @Query("SELECT audioPath FROM history WHERE timestamp < :cutoff AND audioPath IS NOT NULL")
    fun audioPathsOlderThan(cutoff: Long): List<String>

    @Query("UPDATE history SET audioPath = NULL WHERE timestamp < :cutoff AND audioPath IS NOT NULL")
    fun clearAudioPathsOlderThan(cutoff: Long)
}
