package com.example.wechatbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wechatbot.automation.WechatAutomationService
import com.example.wechatbot.databinding.ActivityMainBinding
import com.example.wechatbot.profile.ProfileLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, R.string.service_description, Toast.LENGTH_SHORT).show()
                binding.statusText.text = getString(R.string.service_description)
            } else {
                binding.statusText.text = getString(R.string.app_name)
            }
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    WechatAutomationService.enqueueScreenCapturePermission(this, result.resultCode, data)
                    binding.statusText.text = getString(R.string.service_description)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.requestPermissionsButton.setOnClickListener {
            requestPermissions()
        }

        binding.loadProfileButton.setOnClickListener {
            loadProfile()
        }
    }

    private fun requestPermissions() {
        if (!WechatAutomationService.isAccessibilityEnabled(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        mediaProjectionLauncher.launch(WechatMonitoringService.createPermissionIntent(this))
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            val context = applicationContext
            val result = withContext(Dispatchers.IO) {
                ProfileLoader.loadFromRawResource(context, R.raw.default_profile)
            }
            if (result != null) {
                Toast.makeText(context, "Loaded profile with ${result.targets.size} targets", Toast.LENGTH_SHORT)
                    .show()
                WechatMonitoringService.updateProfile(context, result)
            } else {
                Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
