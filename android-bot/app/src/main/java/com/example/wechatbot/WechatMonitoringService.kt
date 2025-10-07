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
import android.provider.Settings
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.wechatbot.automation.AutomationOrchestrator
import com.example.wechatbot.automation.WechatAutomationService
import com.example.wechatbot.ocr.TemplateRepository
import com.example.wechatbot.ocr.TextAnalyzer
import com.example.wechatbot.profile.AutomationProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WechatMonitoringService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: android.view.View? = null
    private var overlayStatusView: TextView? = null
    private var overlayStartButton: Button? = null
    private var overlayPauseButton: Button? = null
    private var transientView: TextView? = null
    private var profile: AutomationProfile? = null
    private var textAnalyzer: TextAnalyzer? = null
    private var orchestrator: AutomationOrchestrator? = null
    private lateinit var templateRepository: TemplateRepository
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val automationEnabled = MutableStateFlow(false)
    private var currentStatus: String = "等待脚本"

    override fun onCreate() {
        super.onCreate()
        currentStatus = getString(R.string.status_idle)
        createNotificationChannel()
        if (!startAsForeground()) {
            return
        }
        templateRepository = TemplateRepository(applicationContext)
        updateOverlay(currentStatus)
        publishState()
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
            runCatching { windowManager.removeView(view) }
        }
        transientView?.let { view ->
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { windowManager.removeView(view) }
        }
        stopProjection()
        scope.cancel()
        automationEnabled.value = false
        publishState()
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
        if (textAnalyzer == null) {
            textAnalyzer = TextAnalyzer()
        }
        if (orchestrator == null) {
            orchestrator = AutomationOrchestrator(
                applicationContext,
                scope,
                profile,
                templateRepository,
                ::handleStatusMessage
            )
        } else {
            orchestrator?.updateProfile(profile)
        }
        handleStatusMessage(
            AutomationOrchestrator.StatusMessage(
                "Profile loaded: ${profile.name}",
                transient = false
            )
        )
        publishState()
    }

    fun setAutomationEnabled(enabled: Boolean) {
        val changed = automationEnabled.value != enabled
        automationEnabled.value = enabled
        publishState()
        if (changed) {
            val status = if (enabled) getString(R.string.status_running) else getString(R.string.status_paused)
            handleStatusMessage(
                AutomationOrchestrator.StatusMessage(
                    "自动化状态: $status",
                    transient = false
                )
            )
        } else {
            updateOverlay()
        }
    }

    fun isAutomationEnabled(): Boolean = automationEnabled.value

    private fun handleStatusMessage(message: AutomationOrchestrator.StatusMessage) {
        scope.launch {
            if (message.transient) {
                showTransient(message.text)
            } else {
                updateOverlay(message.text)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(message.text))
            }
        }
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

    private fun startAsForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(currentStatus))
            true
        } catch (exception: SecurityException) {
            val message = getString(R.string.error_notification_permission_required)
            currentStatus = message
            serviceState.update { state ->
                state.copy(status = message)
            }
            scope.launch {
                android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
            }
            stopSelf()
            false
        } catch (throwable: Throwable) {
            val message = getString(R.string.error_service_start_failed)
            currentStatus = message
            serviceState.update { state ->
                state.copy(status = message)
            }
            scope.launch {
                android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
            }
            stopSelf()
            false
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
        handleStatusMessage(
            AutomationOrchestrator.StatusMessage(
                "Screen capture active",
                transient = false
            )
        )
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
        while (scope.isActive) {
            if (!automationEnabled.value || profile == null) {
                delay(500)
                continue
            }
            val reader = imageReader ?: break
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = image.toBitmap()
                image.close()
                if (bitmap != null && profile != null) {
                    val analyzer = textAnalyzer ?: TextAnalyzer().also { textAnalyzer = it }
                    val analysis = analyzer.analyze(bitmap)
                    orchestrator?.processFrame(bitmap, analysis)
                    bitmap.recycle()
                }
            }
            delay((profile?.heartbeatSeconds ?: 90L) * 1000L / 3)
        }
    }

    private fun showTransient(message: String) {
        if (!hasOverlayPermission()) {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = resources.displayMetrics.density.times(96).toInt()

        runCatching {
            transientView?.let { existing ->
                windowManager.removeView(existing)
            }
            val view = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_1, null) as TextView
            view.text = message
            view.setBackgroundResource(android.R.drawable.toast_frame)
            view.alpha = 0f
            transientView = view
            windowManager.addView(view, params)
            view.animate()
                .alpha(1f)
                .setDuration(150)
                .withEndAction {
                    view.animate()
                        .alpha(0f)
                        .setStartDelay(1200)
                        .setDuration(400)
                        .withEndAction {
                            runCatching { windowManager.removeView(view) }
                            if (transientView === view) {
                                transientView = null
                            }
                        }
                        .start()
                }
                .start()
        }
    }

    private fun updateOverlay(status: String? = null) {
        status?.let { currentStatus = it }

        if (!hasOverlayPermission()) {
            removeOverlay()
            publishState()
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = ensureOverlay(windowManager)
        overlayStatusView?.text = currentStatus
        overlayStartButton?.isEnabled = !automationEnabled.value
        overlayPauseButton?.isEnabled = automationEnabled.value
        publishState()
    }

    private fun ensureOverlay(windowManager: WindowManager): android.view.View {
        overlayView?.let { return it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = resources.displayMetrics.density.times(48).toInt()
        }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null)
        overlayStatusView = view.findViewById(R.id.overlayStatus)
        overlayStartButton = view.findViewById<Button>(R.id.overlayStart).apply {
            setOnClickListener { setAutomationEnabled(true) }
        }
        overlayPauseButton = view.findViewById<Button>(R.id.overlayPause).apply {
            setOnClickListener { setAutomationEnabled(false) }
        }
        overlayView = view
        windowManager.addView(view, params)
        return view
    }

    private fun removeOverlay() {
        val windowManager = runCatching {
            getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }.getOrNull() ?: return
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        overlayStatusView = null
        overlayStartButton = null
        overlayPauseButton = null
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
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

    private fun publishState() {
        serviceState.update {
            it.copy(
                automationEnabled = automationEnabled.value,
                profileName = profile?.name,
                status = currentStatus
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "wechat_monitoring"
        private const val NOTIFICATION_ID = 2001
        private val screenPermissionFlow = MutableStateFlow<ScreenCapturePermission?>(null)
        private val serviceState = MutableStateFlow(ServiceState())

        fun createPermissionIntent(context: Context): Intent {
            val mediaProjectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mediaProjectionManager.createScreenCaptureIntent()
        }

        fun enqueueScreenCapturePermission(context: Context, resultCode: Int, data: Intent) {
            screenPermissionFlow.value = ScreenCapturePermission(resultCode, data)
        }

        suspend fun handleAccessibilityEvent(service: WechatAutomationService, event: android.view.accessibility.AccessibilityEvent) {
            // placeholder for event handling
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

        fun observeState(): StateFlow<ServiceState> = serviceState
    }

    data class ScreenCapturePermission(val resultCode: Int, val data: Intent)

    data class ServiceState(
        val automationEnabled: Boolean = false,
        val profileName: String? = null,
        val status: String = ""
    )
}
