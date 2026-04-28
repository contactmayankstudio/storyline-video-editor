package com.video.engine

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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
        if (activity.resources.getBoolean(R.bool.storyline_runtime_startup_media_permissions_enabled)) {
            requestStoragePermissions()
        } else {
            Log.d(TAG, "Startup media permissions disabled for this store build")
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != permissionRequestCode) {
            return
        }
        val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            Log.d(TAG, "Storage permissions granted")
        } else {
            Log.w(TAG, "Storage permissions denied")
        }
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

    private fun requestStoragePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — granular media permissions
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                permissionRequestCode,
            )
        } else {
            Log.d(TAG, "Storage permissions already granted")
        }
    }
}
