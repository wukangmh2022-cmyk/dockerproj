package com.example.wechatbot.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Bitmap
import com.example.wechatbot.ocr.ImageAnalyzer
import com.example.wechatbot.ocr.TemplateRepository
import com.example.wechatbot.ocr.TextAnalyzer
import com.example.wechatbot.profile.ActionType
import com.example.wechatbot.profile.AutomationAction
import com.example.wechatbot.profile.AutomationProfile
import com.example.wechatbot.profile.AutomationScene
import com.example.wechatbot.profile.SampleRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class AutomationOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private var profile: AutomationProfile,
    templateRepository: TemplateRepository,
    private val statusCallback: (StatusMessage) -> Unit,
) {
    data class StatusMessage(val text: String, val transient: Boolean)

    private data class SceneCandidate(
        val scene: AutomationScene,
        val score: Float,
        val bounds: Rect?,
    )

    private val lastExecution = ConcurrentHashMap<String, Long>()
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var lastFrameMetrics: FrameMetrics? = null
    private val imageAnalyzer = ImageAnalyzer(templateRepository)

    data class FrameMetrics(val width: Int, val height: Int)

    fun updateProfile(profile: AutomationProfile) {
        this.profile = profile
        lastExecution.clear()
    }

    fun processFrame(frame: Bitmap, analysis: TextAnalyzer.Analysis) {
        lastFrameMetrics = FrameMetrics(analysis.width, analysis.height)
        val now = System.currentTimeMillis()
        val candidate = profile.scenes
            .mapNotNull { scene -> evaluateScene(scene, frame, analysis) }
            .sortedByDescending { it.score }
            .firstOrNull { candidate ->
                val last = lastExecution[candidate.scene.id] ?: 0L
                now - last >= candidate.scene.cooldownMs
            }

        if (candidate != null) {
            val scene = candidate.scene
            val regionalText = extractRegionText(scene.captureRegion, analysis)
            lastExecution[scene.id] = now
            val bounds = candidate.bounds
            val jitter = computeJitter(bounds)
            val normalized = bounds?.let { normalizeBounds(it, analysis.width, analysis.height) }

            if (scene.debugOnly) {
                val message = buildDebugMessage(scene, normalized)
                statusCallback(StatusMessage(message, transient = true))
                return
            }

            scope.launch {
                statusCallback(StatusMessage("执行场景: ${scene.title}", transient = false))
                executeScene(scene, analysis, regionalText, jitter)
                normalized?.let {
                    statusCallback(StatusMessage(buildDebugMessage(scene, it), transient = true))
                }
                scene.nextSceneId?.let { nextId -> lastExecution.remove(nextId) }
            }
        }
    }

    private fun buildDebugMessage(scene: AutomationScene, normalized: NormalizedBounds?): String {
        return if (normalized != null) {
            String.format(
                Locale.getDefault(),
                "调试命中[%s] 中心(%.2f, %.2f) 尺寸(%.2f×%.2f)",
                scene.title,
                normalized.centerX,
                normalized.centerY,
                normalized.width,
                normalized.height,
            )
        } else {
            "调试命中[${scene.title}]"
        }
    }

    private suspend fun executeScene(
        scene: AutomationScene,
        analysis: TextAnalyzer.Analysis,
        regionalText: String?,
        jitter: Float,
    ) {
        for (action in scene.actions) {
            when (action.type) {
                ActionType.TAP -> performTap(action, jitter)
                ActionType.LONG_PRESS -> performLongPress(action, jitter)
                ActionType.WAIT -> delay(action.delayMs)
                ActionType.GLOBAL_BACK -> {
                    WechatAutomationService.globalBack()
                    delay(action.delayMs)
                }
                ActionType.COPY_REGION_TO_CLIPBOARD -> {
                    val text = regionalText ?: extractRegionText(action.region, analysis) ?: ""
                    setClipboard(text)
                    statusCallback(StatusMessage("复制文本: ${text.take(24)}", transient = true))
                    delay(action.delayMs)
                }
                ActionType.PASTE_CLIPBOARD -> {
                    performTap(action, jitter)
                    delay(action.delayMs)
                }
            }
        }
    }

    private fun performTap(action: AutomationAction, jitter: Float) {
        val x = action.x ?: return
        val y = action.y ?: return
        WechatAutomationService.tap(x, y, jitter)
    }

    private fun performLongPress(action: AutomationAction, jitter: Float) {
        val x = action.x ?: return
        val y = action.y ?: return
        WechatAutomationService.longPress(x, y, action.durationMs, jitter)
    }

    private fun evaluateScene(
        scene: AutomationScene,
        frame: Bitmap,
        analysis: TextAnalyzer.Analysis,
    ): SceneCandidate? {
        val textCandidate = if (scene.keywords.isNotEmpty()) {
            evaluateTextScene(scene, analysis)
        } else {
            null
        }
        val imageCandidate = if (scene.imageTargets.isNotEmpty()) {
            evaluateImageScene(scene, frame)
        } else {
            null
        }

        return when {
            textCandidate != null -> {
                val mergedBounds = textCandidate.bounds ?: imageCandidate?.bounds
                if (mergedBounds != null && mergedBounds != textCandidate.bounds) {
                    textCandidate.copy(bounds = mergedBounds)
                } else {
                    textCandidate
                }
            }
            imageCandidate != null -> imageCandidate
            else -> null
        }
    }

    private fun evaluateImageScene(scene: AutomationScene, frame: Bitmap): SceneCandidate? {
        val match = imageAnalyzer.findBestMatch(frame, scene.imageTargets)
        val bounds = match?.bounds ?: return null
        return SceneCandidate(scene, match.score, bounds)
    }

    private fun evaluateTextScene(scene: AutomationScene, analysis: TextAnalyzer.Analysis): SceneCandidate? {
        val keywords = scene.keywords.map { it.lowercase(Locale.getDefault()) }.filter { it.isNotBlank() }
        if (keywords.isEmpty()) return null
        val elements = filterElements(scene.captureRegion, analysis)
        if (elements.isEmpty()) return null
        var hits = 0
        var bounds: RectF? = null
        elements.forEach { element ->
            val text = element.text.lowercase(Locale.getDefault())
            if (keywords.any { keyword -> text.contains(keyword) }) {
                hits += 1
                element.boundingBox?.let { rect ->
                    bounds = bounds?.apply { union(RectF(rect)) } ?: RectF(rect)
                }
            }
        }
        if (hits == 0) return null
        val score = hits.toFloat() / keywords.size.toFloat()
        if (score < scene.minMatchRatio) return null
        val rectBounds = bounds?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        return SceneCandidate(scene, score, rectBounds)
    }

    private fun extractRegionText(region: SampleRegion?, analysis: TextAnalyzer.Analysis): String? {
        val elements = filterElements(region, analysis)
        if (elements.isEmpty()) return null
        return elements.joinToString(separator = "\n") { it.text }
    }

    private fun filterElements(region: SampleRegion?, analysis: TextAnalyzer.Analysis): List<TextAnalyzer.RecognizedElement> {
        if (region == null) return analysis.elements
        val bounds = region.toRect()
        return analysis.elements.filter { element ->
            val box = element.boundingBox ?: return@filter false
            intersects(bounds, box)
        }
    }

    private fun SampleRegion.toRect(): Rect {
        return Rect(x, y, x + width, y + height)
    }

    private fun intersects(a: Rect, b: Rect): Boolean {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        return right > left && bottom > top
    }

    private fun setClipboard(text: String) {
        val clip = ClipData.newPlainText("wechat-forward", text)
        clipboardManager.setPrimaryClip(clip)
    }

    private fun computeJitter(bounds: Rect?): Float {
        val size = bounds?.let { max(it.width(), it.height()).toFloat() } ?: run {
            val metrics = lastFrameMetrics ?: return DEFAULT_JITTER
            min(metrics.width, metrics.height) * 0.08f
        }
        return size * 0.1f
            .coerceAtLeast(8f)
            .coerceAtMost(56f)
    }

    private fun normalizeBounds(bounds: Rect, width: Int, height: Int): NormalizedBounds {
        val centerX = bounds.exactCenterX() / width.toFloat()
        val centerY = bounds.exactCenterY() / height.toFloat()
        val normalizedWidth = bounds.width().toFloat() / width.toFloat()
        val normalizedHeight = bounds.height().toFloat() / height.toFloat()
        return NormalizedBounds(centerX, centerY, normalizedWidth, normalizedHeight)
    }

    data class NormalizedBounds(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    companion object {
        private const val DEFAULT_JITTER = 24f
    }
}
