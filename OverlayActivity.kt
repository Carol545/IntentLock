package com.intentlock.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.data.UnlockEvent
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class OverlayActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME     = "app_name"
        const val EXTRA_CONFIDENCE   = "confidence"
        const val EXTRA_AUTO_LAUNCH  = "auto_launch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure it shows over lockscreen and wakes screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.overlay_intentlock)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return finish()
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
        val confidence = intent.getIntExtra(EXTRA_CONFIDENCE, 0)
        val autoLaunch = intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, false)

        setupUI(pkg, appName, confidence, autoLaunch)
    }

    private fun setupUI(pkg: String, appName: String, confidence: Int, autoLaunch: Boolean) {
        findViewById<TextView>(R.id.tvAppName).text = appName
        findViewById<TextView>(R.id.tvConfidence).text = "$confidence% match"

        try {
            val icon = packageManager.getApplicationIcon(pkg)
            findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(icon)
        } catch (_: Exception) { }

        val tvCountdown = findViewById<TextView>(R.id.tvCountdown)
        val ivIcon = findViewById<ImageView>(R.id.ivAppIcon)

        if (autoLaunch) {
            object : CountDownTimer(3000, 1000) {
                override fun onTick(ms: Long) {
                    tvCountdown.text = "Opening in ${ms / 1000 + 1}s…"
                }
                override fun onFinish() {
                    logUnlock(pkg, true)
                    launchApp(pkg)
                }
            }.start()
        } else {
            tvCountdown.text = "Tap to open"
            ivIcon.setOnClickListener {
                logUnlock(pkg, true)
                launchApp(pkg)
            }
        }

        findViewById<Button>(R.id.btnNotThis).setOnClickListener {
            findViewById<View>(R.id.layoutPrediction).visibility = View.GONE
            findViewById<View>(R.id.layoutQuickPick).visibility = View.VISIBLE
            populateQuickPick(pkg)
        }

        findViewById<ImageButton>(R.id.btnDismiss).setOnClickListener {
            logUnlock(pkg, false)
            finish()
        }
    }

    private fun populateQuickPick(currentPkg: String) {
        val grid = findViewById<GridLayout>(R.id.layoutQuickPick)
        scope.launch {
            val db = AppDatabase.getInstance(this@OverlayActivity)
            val recentPkgs = withContext(Dispatchers.IO) {
                db.unlockDao().getRecentEvents(20)
                    .map { it.packageName }
                    .distinct()
                    .filter { it != currentPkg }
                    .take(4)
            }

            recentPkgs.forEach { pkg ->
                val btn = ImageButton(this@OverlayActivity).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(pkg))
                    } catch (_: Exception) { }
                    setOnClickListener {
                        logUnlock(pkg, true)
                        launchApp(pkg)
                    }
                }
                grid.addView(btn)
            }
        }
    }

    private fun launchApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent?.let { startActivity(it) }
        finish()
    }

    private fun logUnlock(pkg: String, wasCorrect: Boolean) {
        scope.launch(Dispatchers.IO) {
            val cal = Calendar.getInstance()
            val event = UnlockEvent(
                timestamp = cal.timeInMillis,
                hour = cal.get(Calendar.HOUR_OF_DAY),
                dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(cal.time),
                packageName = pkg,
                wasCorrect = wasCorrect
            )
            AppDatabase.getInstance(this@OverlayActivity).unlockDao().insert(event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
