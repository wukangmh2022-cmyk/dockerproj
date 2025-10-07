package com.example.wechatbot

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.wechatbot.databinding.ActivityGalleryBinding
import com.example.wechatbot.ocr.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGalleryBinding
    private lateinit var templateRepository: TemplateRepository

    private val importImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val storedName = withContext(Dispatchers.IO) {
                        templateRepository.importTemplate(contentResolver, uri, templateRepository.resolveDisplayName(contentResolver, uri))
                    }
                    if (storedName != null) {
                        Toast.makeText(
                            this@GalleryActivity,
                            getString(
                                R.string.message_template_imported,
                                storedName,
                                templateRepository.getGalleryDirectory().absolutePath
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        refreshList()
                    } else {
                        Toast.makeText(this@GalleryActivity, R.string.error_template_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        templateRepository = TemplateRepository(applicationContext)

        binding.galleryToolbar.setNavigationOnClickListener { finish() }
        binding.galleryToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_image -> {
                    importImageLauncher.launch(arrayOf("image/png", "image/jpeg", "image/jpg", "image/webp"))
                    true
                }
                else -> false
            }
        }

        refreshList()
    }

    private fun refreshList() {
        val files = templateRepository.getGalleryDirectory().listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        binding.galleryEmptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            files.map { it.name }
        )
        binding.galleryList.adapter = adapter
    }
}
