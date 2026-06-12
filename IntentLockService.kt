package com.intentlock.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.intentlock.app.OverlayActivity
import com.intentlock.app.R
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.prediction.ContextEngine
import com.intentlock.app.prediction.FallbackPredictor
import com.intentlock.app.prediction.GeminiPredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IntentLockService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastUnlockTime = 0L
    private val DEBOUNCE_MS = 2000L
    private val CHANNEL_ID = "IntentLockChannel"
    private var currentForegroundPackage: String? = null
    private var isSelectionPending = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.intentlock.app.ACTION_UNLOCK") {
            handleUnlockEvent("Direct Intent")
        }
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showNotification()
        Log.d(TAG, "Service Connected")
    }

    private fun showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IntentLock Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IntentLock Active")
            .setContentText("Monitoring in the background...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val pkg = event.packageName?.toString() ?: return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundPackage = pkg
            
            // If the user is on the launcher, they are starting their "unlock flow"
            if (isLauncherPackage(pkg)) {
                handleUnlockEvent("Accessibility ($pkg)")
            } else {
                // If they are in an app, we are just monitoring silently.
                // We don't overlay if they are already in an app unless we just came from an unlock.
            }
        }
    }

    private fun handleUnlockEvent(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnlockTime > DEBOUNCE_MS) {
            lastUnlockTime = now
            isSelectionPending = true
            onUnlockDetected(source)
        }
    }

    private fun onUnlockDetected(source: String) {
        Log.d(TAG, "Unlock detected from $source. Prompting for intent.")
        
        scope.launch {
            val db = AppDatabase.getInstance(this@IntentLockService)
            val contextEngine = ContextEngine(this@IntentLockService)
            val ctx = contextEngine.collect()
            val installedApps = getInstalledPackages()
            
            var predictions = GeminiPredictor(this@IntentLockService).predictMultiple(ctx, installedApps)
            
            if (predictions.isEmpty()) {
                Log.d(TAG, "Gemini failed or returned empty. Trying FallbackPredictor...")
                val fallback = FallbackPredictor(db.unlockDao(), this@IntentLockService).predict(ctx)
                if (fallback != null) {
                    predictions = listOf(fallback)
                }
            }

            if (predictions.isNotEmpty()) {
                val topPrediction = predictions[0]
                val intent = Intent(this@IntentLockService, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_PACKAGE_NAME, topPrediction.packageName)
                    putExtra(OverlayService.EXTRA_APP_NAME, topPrediction.appName)
                    putExtra(OverlayService.EXTRA_CONFIDENCE, topPrediction.confidence)
                    putExtra(OverlayService.EXTRA_AUTO_LAUNCH, topPrediction.confidence >= 80)
                }
                startService(intent)
            } else {
                Log.d(TAG, "No predictions found from any source. Opening OverlayActivity.")
                // Fallback to OverlayActivity if no predictions
                val intent = Intent(this@IntentLockService, OverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }
    }

    private fun getInstalledPackages(): List<String> {
        return packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName }
    }

    private fun isLauncherPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos.any { it.activityInfo.packageName == pkg }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "IntentLock"
    }
}
