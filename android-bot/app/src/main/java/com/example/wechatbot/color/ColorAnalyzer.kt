package com.example.wechatbot.color

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.example.wechatbot.profile.AutomationProfile
import com.example.wechatbot.profile.ColorTarget
import com.example.wechatbot.profile.SampleRegion

class ColorAnalyzer(
    private val context: Context,
    profile: AutomationProfile,
) {
    data class ColorMatch(
        val target: ColorTarget,
        val averageColor: Int,
        val score: Float,
    )

    private val templateCache = mutableMapOf<String, Bitmap>()
    private var profile: AutomationProfile = profile

    init {
        primeTemplates(profile)
    }

    fun updateProfile(profile: AutomationProfile) {
        this.profile = profile
        primeTemplates(profile)
    }

    fun evaluate(bitmap: Bitmap): List<ColorMatch> {
        if (bitmap.width == 0 || bitmap.height == 0) return emptyList()
        val matches = mutableListOf<ColorMatch>()
        profile.targets.forEach { target ->
            val region = target.sampleRegion
            val cropped = safeCrop(bitmap, region)
            if (cropped != null) {
                val avgColor = calculateAverageColor(cropped)
                if (withinRange(avgColor, target)) {
                    matches += ColorMatch(target, avgColor, scoreColor(avgColor, target))
                }
            }
        }
        return matches.sortedByDescending { it.score }
    }

    private fun primeTemplates(profile: AutomationProfile) {
        profile.targets.forEach { target ->
            val asset = target.templateAsset ?: return@forEach
            if (!templateCache.containsKey(asset)) {
                val resId = context.resources.getIdentifier(asset, "drawable", context.packageName)
                if (resId != 0) {
                    val drawable = ContextCompat.getDrawable(context, resId)
                    if (drawable is BitmapDrawable) {
                        templateCache[asset] = drawable.bitmap
                    } else if (drawable != null) {
                        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(canvas)
                        templateCache[asset] = bitmap
                    }
                }
            }
        }
    }

    private fun safeCrop(bitmap: Bitmap, region: SampleRegion): Bitmap? {
        val left = region.x.coerceAtLeast(0)
        val top = region.y.coerceAtLeast(0)
        val width = region.width.coerceAtMost(bitmap.width - left)
        val height = region.height.coerceAtMost(bitmap.height - top)
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun calculateAverageColor(bitmap: Bitmap): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { color ->
            r += Color.red(color)
            g += Color.green(color)
            b += Color.blue(color)
        }
        val count = pixels.size.coerceAtLeast(1)
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun withinRange(color: Int, target: ColorTarget): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val range = target.hsvRange
        return hsv[0] in range.hueMin..range.hueMax &&
            hsv[1] in range.saturationMin..range.saturationMax &&
            hsv[2] in range.valueMin..range.valueMax
    }

    private fun scoreColor(color: Int, target: ColorTarget): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val range = target.hsvRange
        val hueCenter = (range.hueMin + range.hueMax) / 2f
        val satCenter = (range.saturationMin + range.saturationMax) / 2f
        val valCenter = (range.valueMin + range.valueMax) / 2f
        val hueScore = 1f - (kotlin.math.abs(hsv[0] - hueCenter) / (range.hueMax - range.hueMin + 0.001f))
        val satScore = 1f - (kotlin.math.abs(hsv[1] - satCenter) / (range.saturationMax - range.saturationMin + 0.001f))
        val valScore = 1f - (kotlin.math.abs(hsv[2] - valCenter) / (range.valueMax - range.valueMin + 0.001f))
        return (hueScore + satScore + valScore) / 3f
    }
}
