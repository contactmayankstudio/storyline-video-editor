package com.video.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
            gravity = Gravity.TOP
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
