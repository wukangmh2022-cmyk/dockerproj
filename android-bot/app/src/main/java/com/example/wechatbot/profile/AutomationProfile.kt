package com.example.wechatbot.profile

data class AutomationProfile(
    val name: String,
    val description: String? = null,
    val heartbeatSeconds: Long = 60,
    val scenes: List<AutomationScene>,
)

data class AutomationScene(
    val id: String,
    val title: String,
    val keywords: List<String> = emptyList(),
    val minMatchRatio: Float = 0.75f,
    val cooldownMs: Long = 3000,
    val nextSceneId: String? = null,
    val debugOnly: Boolean = false,
    val actions: List<AutomationAction> = emptyList(),
    val captureRegion: SampleRegion? = null,
    val imageTargets: List<ImageTarget> = emptyList(),
)

data class AutomationAction(
    val type: ActionType,
    val x: Float? = null,
    val y: Float? = null,
    val durationMs: Long = 600,
    val delayMs: Long = 200,
    val region: SampleRegion? = null,
)

data class SampleRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class ImageTarget(
    val name: String,
    val threshold: Float = 0.75f,
)

enum class ActionType {
    TAP,
    LONG_PRESS,
    WAIT,
    GLOBAL_BACK,
    COPY_REGION_TO_CLIPBOARD,
    PASTE_CLIPBOARD,
}
