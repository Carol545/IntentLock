package com.intentlock.app.data

import androidx.room.*

@Entity(tableName = "unlock_events")
data class UnlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val hour: Int,
    val dayOfWeek: String,
    val packageName: String,
    val wasCorrect: Boolean
)
