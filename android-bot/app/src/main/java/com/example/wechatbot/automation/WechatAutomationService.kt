package com.example.wechatbot.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.wechatbot.WechatMonitoringService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WechatAutomationService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ContextCompat.startForegroundService(
            this,
            Intent(this, WechatMonitoringService::class.java)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != "com.tencent.mm") return
        serviceScope.launch {
            WechatMonitoringService.handleAccessibilityEvent(this@WechatAutomationService, event)
        }
    }

    override fun onInterrupt() {
        serviceScope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        private var instance: WechatAutomationService? = null

        fun isAccessibilityEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val componentName = "${context.packageName}/${WechatAutomationService::class.java.name}"
            return enabledServices.split(":").any { it.equals(componentName, ignoreCase = true) }
        }

        fun enqueueScreenCapturePermission(context: Context, resultCode: Int, data: Intent) {
            WechatMonitoringService.enqueueScreenCapturePermission(context, resultCode, data)
        }

        fun tap(x: Float, y: Float) {
            instance?.performTap(x, y)
        }
    }
}
