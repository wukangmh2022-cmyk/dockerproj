package com.example.wechatbot

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.wechatbot.automation.WechatAutomationService
import com.example.wechatbot.databinding.ActivityMainBinding
import com.example.wechatbot.profile.ProfileLoader
import com.example.wechatbot.profile.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ProfileRepository

    private var documents: List<ProfileRepository.Document> = emptyList()
    private var selectedIndex: Int = -1
    private var monitoringService: WechatMonitoringService? = null
    private var startWhenConnected: Boolean = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    WechatAutomationService.enqueueScreenCapturePermission(this, result.resultCode, data)
                }
            }
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val updatedId = result.data?.getStringExtra(ProfileEditorActivity.EXTRA_UPDATED_ID)
                refreshProfiles(updatedId)
            } else {
                refreshProfiles()
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            monitoringService = (service as? WechatMonitoringService.LocalBinder)?.getService()
            if (startWhenConnected) {
                monitoringService?.setAutomationEnabled(true)
                startWhenConnected = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitoringService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProfileRepository(applicationContext)

        setupToolbar()
        setupList()
        setupButtons()
        observeServiceState()
        refreshProfiles()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, WechatMonitoringService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        updatePermissionState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(serviceConnection) }
        monitoringService = null
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open_gallery -> {
                    startActivity(Intent(this, GalleryActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupList() {
        binding.profileList.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        binding.profileList.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            updateSelectionUi()
        }
    }

    private fun setupButtons() {
        binding.requestPermissionsButton.setOnClickListener { requestPermissions() }
        binding.startButton.setOnClickListener { startAutomation() }
        binding.pauseButton.setOnClickListener { pauseAutomation() }
        binding.newProfileButton.setOnClickListener { openEditor(null) }
        binding.editProfileButton.setOnClickListener { openEditor(currentDocument()?.id) }
        binding.deleteProfileButton.setOnClickListener { confirmDelete() }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WechatMonitoringService.observeState().collect { state ->
                    val statusText = when {
                        state.automationEnabled -> getString(R.string.status_running)
                        state.profileName != null -> getString(R.string.status_paused)
                        else -> getString(R.string.status_idle)
                    }
                    binding.statusText.text = getString(
                        R.string.status_automation_template,
                        state.profileName ?: getString(R.string.status_idle),
                        statusText
                    )
                    binding.startButton.isEnabled = currentDocument() != null && !state.automationEnabled
                    binding.pauseButton.isEnabled = state.automationEnabled
                }
            }
        }
    }

    private fun refreshProfiles(selectId: String? = null) {
        documents = repository.loadProfiles()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_activated_1,
            documents.map { it.displayName }
        )
        binding.profileList.adapter = adapter
        if (documents.isNotEmpty()) {
            val targetIndex = selectId?.let { id ->
                documents.indexOfFirst { it.id == id }
            }?.takeIf { it >= 0 } ?: 0
            binding.profileList.setItemChecked(targetIndex, true)
            selectedIndex = targetIndex
        } else {
            selectedIndex = -1
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val document = currentDocument()
        binding.deleteProfileButton.visibility = if (document?.editable == true) android.view.View.VISIBLE else android.view.View.GONE
        binding.editProfileButton.isEnabled = document != null
        binding.startButton.isEnabled = document != null && !WechatMonitoringService.observeState().value.automationEnabled
    }

    private fun currentDocument(): ProfileRepository.Document? = documents.getOrNull(selectedIndex)

    private fun openEditor(documentId: String?) {
        val intent = Intent(this, ProfileEditorActivity::class.java)
        if (documentId != null) {
            intent.putExtra(ProfileEditorActivity.EXTRA_DOCUMENT_ID, documentId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun confirmDelete() {
        val document = currentDocument() ?: return
        if (!document.editable) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_profile, document.displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val removed = repository.deleteProfile(document.id)
                    withContext(Dispatchers.Main) {
                        if (removed) {
                            Toast.makeText(this@MainActivity, R.string.message_profile_deleted, Toast.LENGTH_SHORT).show()
                            refreshProfiles()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestPermissions() {
        if (!WechatAutomationService.isAccessibilityEnabled(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        mediaProjectionLauncher.launch(WechatMonitoringService.createPermissionIntent(this))
    }

    private fun updatePermissionState() {
        val missingAccessibility = !WechatAutomationService.isAccessibilityEnabled(this)
        val missingOverlay = !Settings.canDrawOverlays(this)
        val missingNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        val needsPermission = missingAccessibility || missingOverlay || missingNotification
        binding.requestPermissionsButton.visibility = if (needsPermission) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startAutomation() {
        val document = currentDocument() ?: return
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) { ProfileLoader.loadFromString(document.content) }
            if (profile == null) {
                Toast.makeText(this@MainActivity, R.string.error_invalid_profile, Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.Main) {
                val service = monitoringService
                if (service != null) {
                    service.updateProfile(profile)
                    service.setAutomationEnabled(true)
                } else {
                    startWhenConnected = true
                    WechatMonitoringService.updateProfile(applicationContext, profile)
                }
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profile_loaded_template, profile.scenes.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun pauseAutomation() {
        monitoringService?.setAutomationEnabled(false)
    }
}
