package com.intentlock.app.prediction

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.CalendarContract
import androidx.core.app.ActivityCompat
import com.intentlock.app.data.UnlockContext
import java.text.SimpleDateFormat
import java.util.*

class ContextEngine(private val context: Context) {

    fun collect(): UnlockContext {
        val cal = Calendar.getInstance()
        val event = getNextCalendarEvent()
        return UnlockContext(
            hour = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(cal.time),
            headphonesConnected = isHeadphonesConnected(),
            nextCalendarEvent = event?.first,
            minutesUntilEvent = event?.second,
            recentApps = getRecentApps(),
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging()
        )
    }

    private fun isHeadphonesConnected(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.isWiredHeadsetOn || am.isBluetoothA2dpOn
    }

    private fun getNextCalendarEvent(): Pair<String, Int>? {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) return null

        val now = System.currentTimeMillis()
        val oneHour = 60 * 60 * 1000L
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(now.toString(), (now + oneHour).toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                val title = it.getString(0) ?: return null
                val start = it.getLong(1)
                val minsUntil = ((start - now) / 60000).toInt()
                Pair(title, minsUntil)
            } else null
        }
    }

    private fun getRecentApps(): List<String> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 24 * 60 * 60 * 1000,
                now
            )
                .filter { it.packageName != context.packageName }
                .sortedByDescending { it.lastTimeUsed }
                .take(3)
                .map { it.packageName }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (scale > 0) (level * 100 / scale) else -1
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }
}
