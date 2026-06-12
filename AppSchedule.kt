package com.intentlock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_schedules")
data class AppSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val appName: String,
    val startHour: Int,    // 0-23
    val endHour: Int       // 0-23
)
