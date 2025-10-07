package com.example.wechatbot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.wechatbot.databinding.ActivityMainBinding

/**
 * Minimal settings activity that reuses the main layout to keep footprint small.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.statusText.text = getString(R.string.service_description)
        binding.requestPermissionsButton.text = getString(android.R.string.ok)
        binding.requestPermissionsButton.setOnClickListener { finish() }
        binding.loadProfileButton.text = getString(android.R.string.cancel)
        binding.loadProfileButton.setOnClickListener { finish() }
    }
}
