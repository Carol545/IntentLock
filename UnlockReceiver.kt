package com.intentlock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.intentlock.app.OverlayActivity

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            Log.d("IntentLock", "Unlock Broadcast Received (Manifest)")
            
            // Wake up the IntentLockService to process prediction
            val serviceIntent = Intent(context, IntentLockService::class.java).apply {
                action = "com.intentlock.app.ACTION_UNLOCK"
            }
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Fallback: If service fails to start, try launching the activity directly 
                // (though prediction context might be missing)
                Log.e("IntentLock", "Service start failed, trying activity fallback")
                val activityIntent = Intent(context, OverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(activityIntent) } catch (_: Exception) {}
            }
        }
    }
}
