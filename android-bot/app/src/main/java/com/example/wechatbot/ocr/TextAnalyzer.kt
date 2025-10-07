package com.example.wechatbot.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min

class TextAnalyzer {
    data class RecognizedElement(
        val text: String,
        val boundingBox: Rect?,
    )

    data class Analysis(
        val width: Int,
        val height: Int,
        val elements: List<RecognizedElement>,
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyze(bitmap: Bitmap): Analysis {
        val prepared = prepareBitmap(bitmap)
        val image = InputImage.fromBitmap(prepared.bitmap, 0)
        val result = recognizer.process(image).await()
        if (prepared.needsRecycle) {
            prepared.bitmap.recycle()
        }
        val elements = extractElements(result, prepared.scaleBack)
        return Analysis(bitmap.width, bitmap.height, elements)
    }

    private fun extractElements(text: Text, scaleBack: Float): List<RecognizedElement> {
        val elements = mutableListOf<RecognizedElement>()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                if (line.text.isNotBlank()) {
                    val bounding = line.boundingBox?.let { rect ->
                        Rect(
                            (rect.left * scaleBack).toInt(),
                            (rect.top * scaleBack).toInt(),
                            (rect.right * scaleBack).toInt(),
                            (rect.bottom * scaleBack).toInt()
                        )
                    }
                    elements += RecognizedElement(line.text.trim(), bounding)
                }
            }
        }
        return elements
    }

    private fun prepareBitmap(source: Bitmap): PreparedBitmap {
        val width = source.width
        val height = source.height
        val shortEdge = min(width, height)
        if (shortEdge <= MAX_SHORT_EDGE) {
            return PreparedBitmap(source, 1f, needsRecycle = false)
        }
        val scale = MAX_SHORT_EDGE.toFloat() / shortEdge.toFloat()
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val scaleBack = 1f / scale
        return PreparedBitmap(scaledBitmap, scaleBack, needsRecycle = true)
    }

    private data class PreparedBitmap(
        val bitmap: Bitmap,
        val scaleBack: Float,
        val needsRecycle: Boolean,
    )

    companion object {
        private const val MAX_SHORT_EDGE = 1080
    }
}
