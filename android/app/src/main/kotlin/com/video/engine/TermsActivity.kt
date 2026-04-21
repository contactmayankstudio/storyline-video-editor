package com.video.engine

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

class TermsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this)
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
