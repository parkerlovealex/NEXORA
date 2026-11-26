package com.nexora.browser.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nexora.browser.databinding.ActivityAboutBinding
import com.nexora.browser.util.UiFeedback

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiFeedback.install(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        title = "About NEXORA"
        // The about text itself lives in strings.xml (@string/about_text),
        // bound directly by activity_about.xml.
    }
}
