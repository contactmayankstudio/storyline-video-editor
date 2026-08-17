package com.video.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.video.engine.photo.PhotoEditorActivity

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.btnVideoStudio).setOnClickListener {
            startActivity(Intent(this, VideoEditorActivity::class.java))
        }

        findViewById<Button>(R.id.btnPhotoStudio).setOnClickListener {
            startActivity(Intent(this, PhotoEditorActivity::class.java))
        }
    }
}
