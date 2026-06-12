package com.intentlock.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UnlockDao {

    @Insert
    suspend fun insert(event: UnlockEvent)

    @Query("SELECT * FROM unlock_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int): List<UnlockEvent>

    @Query("SELECT COUNT(*) FROM unlock_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM unlock_events")
    suspend fun clearAll()

    // ── App Schedules ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: AppSchedule)

    @Query("SELECT * FROM app_schedules")
    suspend fun getAllSchedules(): List<AppSchedule>

    @Query("DELETE FROM app_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Int)

    @Query("DELETE FROM app_schedules")
    suspend fun clearSchedules()
}
