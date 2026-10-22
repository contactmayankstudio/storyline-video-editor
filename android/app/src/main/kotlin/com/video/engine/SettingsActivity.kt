package com.video.engine

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }

        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(24 + systemBars.left, 24 + systemBars.top, 24 + systemBars.right, 24 + systemBars.bottom)
            insets
        }

        val privacyBtn = Button(this).apply {
            text = "Privacy Policy"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, PrivacyPolicyActivity::class.java))
            }
        }

        val termsBtn = Button(this).apply {
            text = "Terms of Use"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, TermsActivity::class.java))
            }
        }

        container.addView(privacyBtn)
        container.addView(termsBtn)

        setContentView(container)
        title = "Settings"
    }
}
