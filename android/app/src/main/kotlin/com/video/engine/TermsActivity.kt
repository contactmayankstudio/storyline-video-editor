package com.video.engine

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class TermsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this)
        ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val tv = TextView(this).apply {
            val input = resources.openRawResource(R.raw.terms_of_use)
            val reader = BufferedReader(InputStreamReader(input))
            val text = reader.readText()
            reader.close()
            setText(text)
            setPadding(24, 24, 24, 24)
        }
        sv.addView(tv)
        setContentView(sv)
        title = "Terms of Use"
    }
}
