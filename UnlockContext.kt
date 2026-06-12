package com.intentlock.app.prediction

data class UnlockContext(
    val hour: Int,
    val dayOfWeek: String,
    val headphonesConnected: Boolean,
    val nextCalendarEvent: String?,
    val minutesUntilEvent: Int?,
    val recentApps: List<String>,
    val batteryLevel: Int,
    val isCharging: Boolean
)

data class PredictionResult(
    val packageName: String,
    val appName: String,
    val confidence: Int,
    val autoLaunch: Boolean
)
