package com.example.wechatbot.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.wechatbot.profile.ImageTarget
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ImageAnalyzer(private val repository: TemplateRepository) {

    data class Match(
        val target: ImageTarget,
        val score: Float,
        val bounds: Rect,
    )

    fun findBestMatch(frame: Bitmap, targets: List<ImageTarget>): Match? {
        if (targets.isEmpty()) return null
        val prepared = prepareFrame(frame)
        try {
            var best: Match? = null
            targets.forEach { target ->
                val match = evaluateTarget(prepared, target)
                if (match != null && match.score >= target.threshold) {
                    if (best == null || match.score > best!!.score) {
                        best = match
                    }
                }
            }
            return best
        } finally {
            if (prepared.needsRecycle) {
                prepared.bitmap.recycle()
            }
        }
    }

    private fun evaluateTarget(frame: PreparedFrame, target: ImageTarget): Match? {
        val original = repository.loadTemplate(target.name) ?: return null
        val scale = frame.scale
        val scaledOriginal = getScaledTemplate(target.name, original, scale) ?: return null
        val scales = SCALE_FACTORS
        var bestScore = Float.NEGATIVE_INFINITY
        var bestRect: Rect? = null
        scales.forEach { relative ->
            val effectiveScale = scale * relative
            val template = if (relative == 1f) {
                scaledOriginal
            } else {
                getScaledTemplate("${target.name}@$relative", original, effectiveScale)
                    ?: return@forEach
            }
            val result = match(frame.bitmap, template)
            if (result != null && result.score > bestScore) {
                bestScore = result.score
                bestRect = result.bounds
            }
        }

        val rect = bestRect ?: return null
        if (bestScore < 0f) return null
        val scaleBack = frame.scaleBack
        val normalized = Rect(
            (rect.left * scaleBack).roundToInt(),
            (rect.top * scaleBack).roundToInt(),
            (rect.right * scaleBack).roundToInt(),
            (rect.bottom * scaleBack).roundToInt(),
        )
        return Match(target, bestScore, normalized)
    }

    private fun getScaledTemplate(key: String, original: Bitmap, scale: Float): Bitmap? {
        val safeScale = scale.coerceAtLeast(MIN_SCALE)
        val width = (original.width * safeScale).roundToInt().coerceAtLeast(1)
        val height = (original.height * safeScale).roundToInt().coerceAtLeast(1)
        val cacheKey = "$key:$width:${height}"
        scaledCache[cacheKey]?.let { if (!it.isRecycled) return it }
        if (width <= 0 || height <= 0) return null
        val scaled = Bitmap.createScaledBitmap(original, width, height, true)
        scaledCache[cacheKey] = scaled
        return scaled
    }

    private fun match(frame: Bitmap, template: Bitmap): MatchResult? {
        if (template.width > frame.width || template.height > frame.height) return null
        val framePixels = obtainPixels(frame)
        val templatePixels = obtainPixels(template)
        val frameWidth = frame.width
        val frameHeight = frame.height
        val templateWidth = template.width
        val templateHeight = template.height

        var bestScore = Float.NEGATIVE_INFINITY
        var bestX = 0
        var bestY = 0

        val stepX = max(1, templateWidth / 6)
        val stepY = max(1, templateHeight / 6)
        for (y in 0..(frameHeight - templateHeight) step stepY) {
            for (x in 0..(frameWidth - templateWidth) step stepX) {
                val score = similarity(framePixels, templatePixels, frameWidth, templateWidth, templateHeight, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }

        if (stepX > 1 || stepY > 1) {
            val refineStartX = max(0, bestX - stepX)
            val refineStartY = max(0, bestY - stepY)
            val refineEndX = min(frameWidth - templateWidth, bestX + stepX)
            val refineEndY = min(frameHeight - templateHeight, bestY + stepY)
            for (y in refineStartY..refineEndY) {
                for (x in refineStartX..refineEndX) {
                    val score = similarity(framePixels, templatePixels, frameWidth, templateWidth, templateHeight, x, y)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                }
            }
        }

        if (bestScore <= 0f) return null
        return MatchResult(bestScore, Rect(bestX, bestY, bestX + templateWidth, bestY + templateHeight))
    }

    private fun similarity(
        framePixels: IntArray,
        templatePixels: IntArray,
        frameWidth: Int,
        templateWidth: Int,
        templateHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ): Float {
        var diff: Long = 0
        var indexTemplate = 0
        var indexFrameRow = offsetY * frameWidth + offsetX
        repeat(templateHeight) {
            var indexFrame = indexFrameRow
            repeat(templateWidth) {
                val framePixel = framePixels[indexFrame]
                val templatePixel = templatePixels[indexTemplate]
                diff += channelDifference(framePixel, templatePixel)
                indexFrame++
                indexTemplate++
            }
            indexFrameRow += frameWidth
        }
        val maxDiff = templateWidth.toLong() * templateHeight.toLong() * 255L * 3L
        val normalized = 1f - (diff.toFloat() / maxDiff.toFloat())
        return normalized.coerceIn(0f, 1f)
    }

    private fun channelDifference(a: Int, b: Int): Int {
        val dr = abs(Color.red(a) - Color.red(b))
        val dg = abs(Color.green(a) - Color.green(b))
        val db = abs(Color.blue(a) - Color.blue(b))
        return dr + dg + db
    }

    private fun obtainPixels(bitmap: Bitmap): IntArray {
        val buffer = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(buffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return buffer
    }

    private fun prepareFrame(bitmap: Bitmap): PreparedFrame {
        val width = bitmap.width
        val height = bitmap.height
        val maxEdge = max(width, height)
        if (maxEdge <= MAX_EDGE) {
            return PreparedFrame(bitmap, scale = 1f, scaleBack = 1f, needsRecycle = false)
        }
        val scale = MAX_EDGE.toFloat() / maxEdge.toFloat()
        val scaledBitmap = Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).roundToInt(),
            (height * scale).roundToInt(),
            true
        )
        val scaleBack = 1f / scale
        return PreparedFrame(scaledBitmap, scale = scale, scaleBack = scaleBack, needsRecycle = true)
    }

    private data class PreparedFrame(
        val bitmap: Bitmap,
        val scale: Float,
        val scaleBack: Float,
        val needsRecycle: Boolean,
    )

    private data class MatchResult(
        val score: Float,
        val bounds: Rect,
    )

    companion object {
        private const val MAX_EDGE = 1440
        private const val MIN_SCALE = 0.1f
        private val SCALE_FACTORS = floatArrayOf(0.75f, 0.9f, 1f, 1.1f, 1.25f)
        private val scaledCache = ConcurrentHashMap<String, Bitmap>()
    }
}
