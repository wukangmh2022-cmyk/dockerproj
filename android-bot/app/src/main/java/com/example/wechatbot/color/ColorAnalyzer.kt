package com.example.wechatbot.color

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight color sampling utility that can be used by automation profiles
 * requiring approximate colour checks. The current automation workflow does
 * not depend on it directly, but it is kept available for future scripts and
 * to satisfy compilation when colour based scenes are declared.
 */
class ColorAnalyzer {

    data class Match(
        val target: ColorTarget,
        val score: Float,
        val bounds: Rect,
    )

    fun analyze(frame: Bitmap, targets: List<ColorTarget>): List<Match> {
        if (targets.isEmpty()) return emptyList()
        val width = frame.width
        val height = frame.height
        val results = ArrayList<Match>()
        targets.forEach { target ->
            val bounds = (target.region?.toRect(width, height)
                ?: Rect(0, 0, width, height)).normalizeWithin(width, height)
            if (bounds.width() <= 0 || bounds.height() <= 0) return@forEach
            val sampled = sample(frame, bounds)
            val score = score(sampled, target.swatch, target.tolerance)
            if (score >= target.minScore) {
                results += Match(target, score, bounds)
            }
        }
        return results
    }

    private fun Rect.normalizeWithin(width: Int, height: Int): Rect {
        val left = min(max(0, this.left), width)
        val top = min(max(0, this.top), height)
        val right = min(max(left, this.right), width)
        val bottom = min(max(top, this.bottom), height)
        return Rect(left, top, right, bottom)
    }

    private fun sample(bitmap: Bitmap, bounds: Rect): RgbColor {
        val stepX = max(1, bounds.width() / SAMPLE_GRID)
        val stepY = max(1, bounds.height() / SAMPLE_GRID)
        var count = 0
        var r = 0L
        var g = 0L
        var b = 0L
        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                val color = bitmap.getPixel(x, y)
                r += Color.red(color)
                g += Color.green(color)
                b += Color.blue(color)
                count += 1
                x += stepX
            }
            y += stepY
        }
        if (count == 0) {
            return RgbColor(0, 0, 0)
        }
        return RgbColor(
            red = (r / count).toInt(),
            green = (g / count).toInt(),
            blue = (b / count).toInt(),
        )
    }

    private fun score(actual: RgbColor, target: ColorSwatch, tolerance: Float): Float {
        val diffR = abs(actual.red - target.red) / 255f
        val diffG = abs(actual.green - target.green) / 255f
        val diffB = abs(actual.blue - target.blue) / 255f
        val average = (diffR + diffG + diffB) / 3f
        if (tolerance <= 0f) {
            return (1f - average).coerceIn(0f, 1f)
        }
        val normalized = 1f - (average / tolerance)
        return normalized.coerceIn(0f, 1f)
    }

    companion object {
        private const val SAMPLE_GRID = 32
    }
}

data class ColorTarget(
    val name: String,
    val region: ColorRegion? = null,
    val swatch: ColorSwatch,
    val tolerance: Float = 0.18f,
    val minScore: Float = 0.75f,
)

data class ColorRegion(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun toRect(frameWidth: Int, frameHeight: Int): Rect {
        val l = (left * frameWidth).roundToInt()
        val t = (top * frameHeight).roundToInt()
        val r = ((left + width) * frameWidth).roundToInt()
        val b = ((top + height) * frameHeight).roundToInt()
        return Rect(l, t, r, b)
    }
}

data class ColorSwatch(
    val red: Int,
    val green: Int,
    val blue: Int,
)

data class RgbColor(
    val red: Int,
    val green: Int,
    val blue: Int,
)
