package com.example.wechatbot.profile

data class AutomationProfile(
    val name: String,
    val forwardContact: String,
    val allowedSenders: List<String>,
    val targets: List<ColorTarget>,
    val heartbeatSeconds: Long = 60,
)

data class ColorTarget(
    val id: String,
    val description: String,
    val sampleRegion: SampleRegion,
    val hsvRange: HsvRange,
    val templateAsset: String? = null,
    val tapAction: TapAction? = null,
)

data class SampleRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class HsvRange(
    val hueMin: Float,
    val hueMax: Float,
    val saturationMin: Float,
    val saturationMax: Float,
    val valueMin: Float,
    val valueMax: Float,
)

data class TapAction(
    val x: Float,
    val y: Float,
    val delayMs: Long = 200,
)
