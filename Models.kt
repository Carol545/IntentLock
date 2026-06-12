package com.intentlock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Context snapshot collected at each unlock ──────────────────────────────

data class UnlockContext(
    val hour: Int,                          // 0–23
    val dayOfWeek: String,                 // "Monday" etc.
    val headphonesConnected: Boolean,
    val nextCalendarEvent: String?,        // title of next event within 60min, or null
    val minutesUntilEvent: Int?,
    val recentApps: List<String>,          // last 3 package names from UsageStatsManager
    val batteryLevel: Int,
    val isCharging: Boolean
)

// ── Prediction result ───────────────────────────────────────────────────

data class PredictionResult(
    val packageName: String,               // e.g. "com.notion.id"
    val appName: String,
    val confidence: Int,                   // 0–100
    val autoLaunch: Boolean               // true if confidence >= 70
)
