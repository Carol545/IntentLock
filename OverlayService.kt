package com.intentlock.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.intentlock.app.R
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.data.UnlockEvent
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_NOT_STICKY
        val appName     = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName
        val confidence  = intent.getIntExtra(EXTRA_CONFIDENCE, 0)
        val autoLaunch  = intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, false)

        Log.d("IntentLock", "OverlayService: Showing overlay for $appName ($packageName) with confidence $confidence, autoLaunch=$autoLaunch")
        showOverlay(packageName, appName, confidence, autoLaunch)
        return START_NOT_STICKY
    }

    private fun showOverlay(pkg: String, appName: String, confidence: Int, autoLaunch: Boolean) {
        // Don't stack overlays
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_intentlock, null)
        overlayView = view

        // Ensure we only show the prediction UI and hide activity-specific layouts
        view.findViewById<View>(R.id.layoutPrediction).visibility = View.VISIBLE
        view.findViewById<View>(R.id.layoutMainSelection).visibility = View.GONE
        view.findViewById<View>(R.id.layoutQuickPick).visibility = View.GONE
        view.findViewById<View>(R.id.layoutAllApps).visibility = View.GONE

        // Ensure status bar is translucent/dark in the overlay
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                  View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        // Populate prediction info
        view.findViewById<TextView>(R.id.tvAppName).text = if (appName.isEmpty()) "Unknown App" else appName
        view.findViewById<TextView>(R.id.tvConfidence).text = "$confidence% match"

        try {
            val icon = packageManager.getApplicationIcon(pkg)
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(icon)
        } catch (_: PackageManager.NameNotFoundException) { }

        val tvCountdown = view.findViewById<TextView>(R.id.tvCountdown)

        if (autoLaunch) {
            object : CountDownTimer(3000, 1000) {
                override fun onTick(ms: Long) {
                    tvCountdown.text = "Opening in ${ms / 1000 + 1}s…"
                }
                override fun onFinish() {
                    logUnlock(pkg, true)
                    launchApp(pkg)
                    dismiss()
                }
            }.start()
        } else {
            tvCountdown.text = "Tap to open"
            view.findViewById<ImageView>(R.id.ivAppIcon).setOnClickListener {
                logUnlock(pkg, true)
                launchApp(pkg)
                dismiss()
            }
        }

        // "Not this" → show quick-pick grid
        view.findViewById<Button>(R.id.btnNotThis).setOnClickListener {
            Log.d("IntentLock", "OverlayService: 'Not this' clicked")
            view.findViewById<View>(R.id.layoutPrediction).visibility = View.GONE
            view.findViewById<View>(R.id.layoutQuickPick).visibility = View.VISIBLE
            populateQuickPick(view, pkg)
        }

        // Mic — voice thought capture
        view.findViewById<ImageButton>(R.id.btnMic).setOnClickListener {
            startVoiceCapture()
        }

        // Dismiss
        view.findViewById<ImageButton>(R.id.btnDismiss).setOnClickListener {
            logUnlock(pkg, false)
            dismiss()
        }

        windowManager.addView(view, params)
    }

    private fun populateQuickPick(view: View, currentPkg: String) {
        val grid = view.findViewById<GridLayout>(R.id.layoutQuickPick)
        grid.removeAllViews()

        scope.launch {
            val db = AppDatabase.getInstance(this@OverlayService)
            val recentPkgs = withContext(Dispatchers.IO) {
                val events = db.unlockDao().getRecentEvents(50)
                Log.d("IntentLock", "OverlayService: Found ${events.size} recent events")
                events.map { it.packageName }
                    .distinct()
                    .filter { it != currentPkg && it != packageName } // don't show current app or IntentLock itself
                    .take(4)
            }

            Log.d("IntentLock", "OverlayService: Populating quick pick with ${recentPkgs.size} apps")

            if (recentPkgs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    // If no history, jump directly to OverlayActivity to browse everything
                    val intent = Intent(this@OverlayService, com.intentlock.app.OverlayActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    dismiss()
                }
                return@launch
            }

            recentPkgs.forEach { pkg ->
                val btn = ImageButton(this@OverlayService).apply {
                    setBackgroundResource(android.R.drawable.btn_default)
                    contentDescription = pkg
                    val size = (64 * resources.displayMetrics.density).toInt()
                    layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                        setMargins(16, 16, 16, 16)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(16, 16, 16, 16)
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(pkg))
                    } catch (e: Exception) {
                        Log.e("IntentLock", "OverlayService: Error loading icon for $pkg", e)
                    }
                    setOnClickListener {
                        Log.d("IntentLock", "OverlayService: Quick pick clicked: $pkg")
                        logUnlock(pkg, true)
                        launchApp(pkg)
                        dismiss()
                    }
                }
                grid.addView(btn)
            }
        }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { startActivity(it) }
    }

    private fun startVoiceCapture() {
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What's on your mind?")
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                openNotesWithText(text)
                dismiss()
            }
            override fun onError(error: Int) {
                Log.e("IntentLock", "SpeechRecognizer error: $error")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(recognizerIntent)
    }

    private fun openNotesWithText(text: String) {
        val keepPkg = "com.google.android.keep"
        val intent = packageManager.getLaunchIntentForPackage(keepPkg)
            ?: Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun logUnlock(pkg: String, wasCorrect: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val cal = Calendar.getInstance()
                val event = UnlockEvent(
                    timestamp = cal.timeInMillis,
                    hour = cal.get(Calendar.HOUR_OF_DAY),
                    dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(cal.time),
                    packageName = pkg,
                    wasCorrect = wasCorrect
                )
                AppDatabase.getInstance(this@OverlayService).unlockDao().insert(event)
            }
        }
    }

    private fun dismiss() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
        scope.cancel()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME     = "app_name"
        const val EXTRA_CONFIDENCE   = "confidence"
        const val EXTRA_AUTO_LAUNCH  = "auto_launch"
    }
}
