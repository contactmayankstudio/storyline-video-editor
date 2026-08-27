package com.video.engine

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout

class AppShellController(
    private val activity: Activity,
    private val permissionRequestCode: Int,
    private val onCreateNotificationChannel: () -> Unit,
) {
    companion object {
        private const val TAG = "[UI]"
    }

    fun setup() {
        // Keep editor shell clean: hide settings entry (Privacy/Terms routes).
        onCreateNotificationChannel()
        Log.d(TAG, "Editor shell initialized (Photo Picker integration active)")
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        // No-op: Photo Picker operates permissionless
    }

    private fun addSettingsButton() {
        try {
            val previewContainer = activity.findViewById<FrameLayout>(R.id.previewContainer)
            val settingsBtn = android.widget.ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_info_details)
                setBackgroundColor(0x00FFFFFF)
                setOnClickListener {
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                }
            }
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.setMargins(16, 16, 16, 16)
            previewContainer.addView(settingsBtn, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add settings button: ${e.message}")
        }
    }
}
