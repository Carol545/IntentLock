package com.intentlock.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.data.DataSeeder
import com.intentlock.app.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Seed demo data on first launch
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DataSeeder.seed(AppDatabase.getInstance(this@MainActivity))
            }
        }

        // If setup not complete, start onboarding
        if (!PermissionHelper.allPermissionsGranted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusCards()
    }

    private fun updateStatusCards() {
        val overlayOk = PermissionHelper.hasOverlayPermission(this)
        val accessOk  = PermissionHelper.isAccessibilityServiceEnabled(this)
        val usageOk   = PermissionHelper.hasUsageStatsPermission(this)

        // Force a toast if accessibility is reporting false but the user thinks it is on
        if (!accessOk) {
            android.util.Log.d("IntentLock", "Accessibility reports DISABLED")
        }

        setStatus(R.id.tvOverlayBadge, overlayOk)
        setStatus(R.id.tvAccessibilityBadge, accessOk)
        setStatus(R.id.tvUsageBadge, usageOk)

        val allOk = overlayOk && accessOk && usageOk
        findViewById<TextView>(R.id.tvActiveStatus).text = if (allOk) {
            "✓ IntentLock is active — lock your phone to try it"
        } else {
            "Complete permissions above to activate IntentLock"
        }
        findViewById<Button>(R.id.btnSetup).text = if (allOk) "Review Setup" else "Complete Setup"
    }

    private fun setStatus(viewId: Int, granted: Boolean) {
        val tv = findViewById<TextView>(viewId)
        tv.text = "●"
        tv.setTextColor(
            if (granted) android.graphics.Color.parseColor("#00C9A7")
            else android.graphics.Color.parseColor("#FF4444")
        )
    }
}
