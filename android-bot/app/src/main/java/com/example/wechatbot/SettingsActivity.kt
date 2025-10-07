package com.example.wechatbot

import android.os.Bundle
import android.view.View
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
        binding.profileSelector.visibility = View.GONE
        binding.profileNameInput.visibility = View.GONE
        binding.profileEditor.visibility = View.GONE
        binding.buttonRow.visibility = View.GONE
    }
}
