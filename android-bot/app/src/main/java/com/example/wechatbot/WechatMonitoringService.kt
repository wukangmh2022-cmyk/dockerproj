package com.example.wechatbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.wechatbot.automation.WechatAutomationService
import com.example.wechatbot.color.ColorAnalyzer
import com.example.wechatbot.profile.AutomationProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class WechatMonitoringService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: TextView? = null
    private var profile: AutomationProfile? = null
    private var colorAnalyzer: ColorAnalyzer? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
        showOverlay("Waiting for profile")
        scope.launch {
            screenPermissionFlow.collect { permission ->
                if (permission != null) {
                    startProjection(permission)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { view ->
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(view)
        }
        stopProjection()
        scope.cancel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    inner class LocalBinder : Binder() {
        fun getService(): WechatMonitoringService = this@WechatMonitoringService
    }

    fun updateProfile(profile: AutomationProfile) {
        this.profile = profile
        if (colorAnalyzer == null) {
            colorAnalyzer = ColorAnalyzer(applicationContext, profile)
        } else {
            colorAnalyzer?.updateProfile(profile)
        }
        updateStatus("Profile loaded: ${profile.name}")
    }

    private fun showOverlay(message: String) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        runCatching {
            overlayView?.let { existing ->
                windowManager.removeView(existing)
            }
            val view = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_1, null) as TextView
            view.text = message
            overlayView = view
            windowManager.addView(view, params)
        }
    }

    private fun updateStatus(status: String) {
        showOverlay(status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun startProjection(permission: ScreenCapturePermission) {
        stopProjection()
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(permission.resultCode, permission.data)
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "wechat-monitor",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        scope.launch(Dispatchers.Default) {
            captureLoop()
        }
        updateStatus("Screen capture active")
    }

    private fun stopProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private suspend fun captureLoop() {
        while (isActive) {
            val reader = imageReader ?: break
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = image.toBitmap()
                image.close()
                if (bitmap != null && profile != null) {
                    val analyzer = colorAnalyzer ?: ColorAnalyzer(applicationContext, profile!!).also {
                        colorAnalyzer = it
                    }
                    val matches = analyzer.evaluate(bitmap)
                    if (matches.isNotEmpty()) {
                        handleMatches(matches)
                    }
                }
            }
            delay((profile?.heartbeatSeconds ?: 90L) * 1000L / 3)
        }
    }

    private fun handleMatches(matches: List<ColorAnalyzer.ColorMatch>) {
        val topMatch = matches.firstOrNull() ?: return
        val status = "Detected ${topMatch.target.id} score=${"%.2f".format(topMatch.score)}"
        updateStatus(status)
        val action = topMatch.target.tapAction ?: return
        scope.launch(Dispatchers.Main) {
            delay(action.delayMs)
            WechatAutomationService.tap(action.x, action.y)
        }
    }

    private fun Image.toBitmap(): Bitmap? {
        if (format != PixelFormat.RGBA_8888 && format != PixelFormat.RGBX_8888) return null
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    companion object {
        private const val CHANNEL_ID = "wechat_monitoring"
        private const val NOTIFICATION_ID = 2001
        private val screenPermissionFlow = MutableStateFlow<ScreenCapturePermission?>(null)

        fun createPermissionIntent(context: Context): Intent {
            val mediaProjectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mediaProjectionManager.createScreenCaptureIntent()
        }

        fun enqueueScreenCapturePermission(context: Context, resultCode: Int, data: Intent) {
            screenPermissionFlow.value = ScreenCapturePermission(resultCode, data)
        }

        suspend fun handleAccessibilityEvent(service: WechatAutomationService, event: android.view.accessibility.AccessibilityEvent) {
            // placeholder for event handling (text detection etc.)
        }

        fun updateProfile(context: Context, profile: AutomationProfile) {
            val intent = Intent(context, WechatMonitoringService::class.java)
            ContextCompat.startForegroundService(context, intent)
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
                    val serviceInstance = (binder as? WechatMonitoringService.LocalBinder)?.getService()
                    serviceInstance?.updateProfile(profile)
                    context.unbindService(this)
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    data class ScreenCapturePermission(val resultCode: Int, val data: Intent)
}
