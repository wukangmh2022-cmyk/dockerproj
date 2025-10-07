package com.example.wechatbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wechatbot.automation.WechatAutomationService
import com.example.wechatbot.databinding.ActivityMainBinding
import com.example.wechatbot.ocr.TemplateRepository
import com.example.wechatbot.profile.ProfileLoader
import com.example.wechatbot.profile.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ProfileRepository
    private lateinit var templateRepository: TemplateRepository

    private var documents: List<ProfileRepository.Document> = emptyList()
    private var selectedDocument: ProfileRepository.Document? = null

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

    private val importProfileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importProfile(uri)
            }
        }

    private val importImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importTemplate(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProfileRepository(applicationContext)
        templateRepository = TemplateRepository(applicationContext)

        binding.requestPermissionsButton.setOnClickListener {
            requestPermissions()
        }

        binding.importProfileButton.setOnClickListener {
            openJsonPicker()
        }

        binding.importImageButton.setOnClickListener {
            openImagePicker()
        }

        binding.saveProfileButton.setOnClickListener {
            saveProfile()
        }

        binding.applyProfileButton.setOnClickListener {
            applyProfile()
        }

        binding.profileSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                documents.getOrNull(position)?.let { selectDocument(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        refreshProfiles()
    }

    private fun requestPermissions() {
        if (!WechatAutomationService.isAccessibilityEnabled(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        mediaProjectionLauncher.launch(WechatMonitoringService.createPermissionIntent(this))
    }

    private fun openJsonPicker() {
        importProfileLauncher.launch(arrayOf("application/json", "text/plain"))
    }

    private fun openImagePicker() {
        importImageLauncher.launch(arrayOf("image/png", "image/jpeg", "image/jpg", "image/webp"))
    }

    private fun saveProfile() {
        lifecycleScope.launch {
            val context = applicationContext
            val currentJson = binding.profileEditor.text?.toString() ?: ""
            if (currentJson.isBlank()) {
                Toast.makeText(context, R.string.error_empty_profile, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val displayName = binding.profileNameInput.text?.toString()?.trim().orEmpty()
            val doc = withContext(Dispatchers.IO) {
                repository.saveProfile(selectedDocument?.takeIf { it.editable }?.id, displayName, currentJson)
            }
            if (doc != null) {
                Toast.makeText(context, R.string.message_profile_saved, Toast.LENGTH_SHORT).show()
                refreshProfiles(doc.id)
            } else {
                Toast.makeText(context, R.string.error_invalid_profile, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyProfile() {
        lifecycleScope.launch {
            val context = applicationContext
            val currentJson = binding.profileEditor.text?.toString() ?: ""
            if (currentJson.isBlank()) {
                Toast.makeText(context, R.string.error_empty_profile, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val profile = withContext(Dispatchers.IO) {
                ProfileLoader.loadFromString(currentJson)
            }
            if (profile != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        getString(R.string.profile_loaded_template, profile.scenes.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                WechatMonitoringService.updateProfile(context, profile)
                binding.profileNameInput.setText(profile.name)
            } else {
                Toast.makeText(context, R.string.error_invalid_profile, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshProfiles(selectId: String? = null) {
        documents = repository.loadProfiles()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            documents.map { it.displayName }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.profileSelector.adapter = adapter
        if (documents.isNotEmpty()) {
            val targetIndex = selectId?.let { id ->
                documents.indexOfFirst { it.id == id }
            }?.takeIf { it >= 0 } ?: 0
            binding.profileSelector.setSelection(targetIndex)
            selectDocument(documents[targetIndex])
        } else {
            selectedDocument = null
            binding.profileEditor.setText("")
            binding.profileNameInput.setText("")
        }
    }

    private fun selectDocument(document: ProfileRepository.Document) {
        selectedDocument = document
        binding.profileNameInput.setText(document.displayName)
        binding.profileEditor.setText(document.content)
        binding.saveProfileButton.isEnabled = true
        binding.applyProfileButton.isEnabled = true
    }

    private fun importProfile(uri: Uri) {
        lifecycleScope.launch {
            val context = applicationContext
            val json = withContext(Dispatchers.IO) { readContent(uri) }
            if (json.isNullOrBlank()) {
                Toast.makeText(context, R.string.error_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            binding.profileEditor.setText(json)
            val profile = withContext(Dispatchers.IO) { ProfileLoader.loadFromString(json) }
            val name = profile?.name?.ifBlank { null } ?: resolveDisplayName(uri) ?: getString(R.string.imported_profile_fallback)
            binding.profileNameInput.setText(name)
            selectedDocument = null
            Toast.makeText(context, R.string.message_profile_imported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importTemplate(uri: Uri) {
        lifecycleScope.launch {
            val context = applicationContext
            val storedName = withContext(Dispatchers.IO) {
                val desired = resolveFileName(uri)
                templateRepository.importTemplate(contentResolver, uri, desired)
            }
            if (storedName != null) {
                val dir = templateRepository.getGalleryDirectory().absolutePath
                Toast.makeText(
                    context,
                    getString(R.string.message_template_imported, storedName, dir),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, R.string.error_template_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readContent(uri: Uri): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(stream.reader()).use { it.readText() }
            }
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
            }
        }
        return name?.substringBeforeLast('.')
    }

    private fun resolveFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
            }
        }
        return name
    }
}
