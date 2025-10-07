package com.example.wechatbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wechatbot.databinding.ActivityProfileEditorBinding
import com.example.wechatbot.profile.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileEditorBinding
    private lateinit var repository: ProfileRepository

    private var document: ProfileRepository.Document? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProfileRepository(applicationContext)

        val docId = intent.getStringExtra(EXTRA_DOCUMENT_ID)
        document = docId?.let { repository.loadProfileById(it) }

        if (document != null) {
            binding.profileNameInput.setText(document?.displayName)
            binding.profileEditor.setText(document?.content)
        } else {
            binding.profileNameInput.setText("")
            binding.profileEditor.setText(DEFAULT_TEMPLATE)
        }

        binding.editorToolbar.setNavigationOnClickListener { finish() }
        binding.saveButton.setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        val json = binding.profileEditor.text?.toString() ?: ""
        if (json.isBlank()) {
            Toast.makeText(this, R.string.error_empty_profile, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val name = binding.profileNameInput.text?.toString()?.trim().orEmpty()
            val saved = withContext(Dispatchers.IO) {
                repository.saveProfile(document?.takeIf { it.editable }?.id, name, json)
            }
            if (saved != null) {
                Toast.makeText(this@ProfileEditorActivity, R.string.message_profile_saved, Toast.LENGTH_SHORT).show()
                val resultIntent = Intent().putExtra(EXTRA_UPDATED_ID, saved.id)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this@ProfileEditorActivity, R.string.error_invalid_profile, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        const val EXTRA_UPDATED_ID = "updated_id"

        private const val DEFAULT_TEMPLATE = """
        {
          \"name\": \"新建脚本\",
          \"heartbeatSeconds\": 90,
          \"scenes\": []
        }
        """
    }
}
