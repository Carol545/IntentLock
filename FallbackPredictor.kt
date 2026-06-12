package com.intentlock.app.prediction

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.intentlock.app.data.PredictionResult
import com.intentlock.app.data.UnlockContext
import com.intentlock.app.data.UnlockDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class FallbackPredictor(
    private val dao: UnlockDao,
    private val context: Context
) {

    suspend fun predict(unlockContext: UnlockContext): PredictionResult? =
        withContext(Dispatchers.IO) {
            try {
                // 1. Check User-Defined Schedules FIRST
                val schedules = dao.getAllSchedules()
                val activeSchedule = schedules.find { 
                    unlockContext.hour >= it.startHour && unlockContext.hour < it.endHour 
                }

                if (activeSchedule != null) {
                    Log.d("IntentLock", "Active user schedule found: ${activeSchedule.packageName}")
                    return@withContext PredictionResult(
                        packageName = activeSchedule.packageName,
                        appName = activeSchedule.appName,
                        confidence = 90, 
                        autoLaunch = false // ALWAYS FALSE per user request
                    )
                }

                // 2. If no schedule, use historical pattern scoring
                val history = dao.getRecentEvents(200)
                if (history.isEmpty()) return@withContext null

                val currentHour = unlockContext.hour
                val scores = history.groupBy { it.packageName }.mapValues { (pkg, events) ->
                    var score = 0.0
                    val timeMatchCount = events.count { it.hour in (currentHour - 1)..(currentHour + 1) }
                    score += timeMatchCount * 5.0
                    val mostRecent = events.maxBy { it.timestamp }.timestamp
                    val hoursSince = (System.currentTimeMillis() - mostRecent) / 3_600_000.0
                    if (hoursSince < 24) score += 10.0
                    if (unlockContext.recentApps.contains(pkg)) {
                        score += 8.0
                    }
                    score
                }

                val winner = scores.maxByOrNull { it.value }
                winner?.let { (pkg, score) ->
                    val totalScore = scores.values.sum()
                    val confidence = if (totalScore > 0) ((score / totalScore) * 100).toInt().coerceIn(40, 65) else 50

                    PredictionResult(
                        packageName = pkg,
                        appName = getAppName(pkg),
                        confidence = confidence,
                        autoLaunch = false // ALWAYS FALSE
                    )
                }
            } catch (e: Exception) {
                Log.e("IntentLock", "FallbackPredictor error: ${e.message}")
                null
            }
        }

    private fun getAppName(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.split(".").lastOrNull()?.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
            } ?: packageName
        }
    }
}
