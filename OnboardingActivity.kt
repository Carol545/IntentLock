package com.intentlock.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.intentlock.app.util.PermissionHelper

class OnboardingActivity : AppCompatActivity() {

    private var step = 0

    private data class Step(
        val title: String,
        val desc: String,
        val grantLabel: String,
        val isGranted: () -> Boolean,
        val onGrant: () -> Unit
    )

    private val steps by lazy {
        listOf(
            Step(
                title = getString(R.string.onboarding_title_overlay),
                desc  = getString(R.string.onboarding_desc_overlay),
                grantLabel = getString(R.string.btn_grant),
                isGranted  = { PermissionHelper.hasOverlayPermission(this) },
                onGrant    = { PermissionHelper.openOverlaySettings(this) }
            ),
            Step(
                title = getString(R.string.onboarding_title_accessibility),
                desc  = getString(R.string.onboarding_desc_accessibility),
                grantLabel = getString(R.string.btn_grant),
                isGranted  = { PermissionHelper.isAccessibilityServiceEnabled(this) },
                onGrant    = { PermissionHelper.openAccessibilitySettings(this) }
            ),
            Step(
                title = getString(R.string.onboarding_title_usage),
                desc  = getString(R.string.onboarding_desc_usage),
                grantLabel = getString(R.string.btn_grant),
                isGranted  = { PermissionHelper.hasUsageStatsPermission(this) },
                onGrant    = { PermissionHelper.openUsageAccessSettings(this) }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        renderStep()

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            steps[step].onGrant()
        }

        findViewById<Button>(R.id.btnNext).setOnClickListener {
            if (step < steps.size - 1) {
                step++
                renderStep()
            } else {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderStep()  // refresh badge after returning from settings
    }

    private fun renderStep() {
        val s = steps[step]
        val granted = s.isGranted()

        findViewById<TextView>(R.id.tvStepIndicator).text = "Step ${step + 1} of ${steps.size}"
        findViewById<TextView>(R.id.tvStepTitle).text = s.title
        findViewById<TextView>(R.id.tvStepDesc).text = s.desc

        val btnGrant = findViewById<Button>(R.id.btnGrant)
        val btnNext  = findViewById<Button>(R.id.btnNext)

        if (granted) {
            btnGrant.text = "✓ Granted"
            btnGrant.isEnabled = false
            btnGrant.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1A00C9A7")
            )
        } else {
            btnGrant.text = s.grantLabel
            btnGrant.isEnabled = true
            btnGrant.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#00C9A7")
            )
        }

        btnNext.text = when {
            step == steps.size - 1 -> getString(R.string.btn_finish)
            else -> getString(R.string.btn_next)
        }
    }
}
