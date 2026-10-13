package com.skipper.tiktok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.regex.Pattern

class SkipperAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SkipperAccessibility"
        var instance: SkipperAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.aweme.lite",
            "com.tiktok.mod"
        )
    }

    var isMonitoringActive: Boolean = true
    var isTikTokInForeground: Boolean = true
        private set

    private var lastSkipTimestamp = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var screenshotJob: Job? = null
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isTikTokInForeground = true
        Log.d(TAG, "SkipperAccessibilityService connected")
        startVisualMonitoring()
    }

    /**
     * Фоновый цикл визуального сканирования через takeScreenshot() (Android 11+).
     * Проверяет видеокадр каждые 500мс для упреждающего детектирования баннеров до того, как они пройдут.
     * Не требует разрешений MediaProjection и не показывает никаких диалогов пользователю.
     */
    private fun startVisualMonitoring() {
        screenshotJob?.cancel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot API requires Android 11+ (API 30)")
            return
        }

        screenshotJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(500) // Быстрый упреждающий опрос кадров (2 раза в секунду)

                    if (!isMonitoringActive || !isTikTokInForeground) {
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastSkipTimestamp < 5000) {
                        continue
                    }

                    captureAndAnalyzeScreen()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in visual monitoring loop", e)
                }
            }
        }
    }

    private fun captureAndAnalyzeScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()

                    if (bitmap != null) {
                        serviceScope.launch {
                            val matchedAd = AdDetector.analyzeFrame(bitmap)
                            bitmap.recycle()

                            if (matchedAd != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastSkipTimestamp >= 5000) {
                                    lastSkipTimestamp = now
                                    Log.i(TAG, "Visual ad detected: '$matchedAd' -> Fast forwarding video timeline!")
                                    notifyAndFastForward(matchedAd)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot", e)
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot failed with code $errorCode")
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (!pkg.contains("com.skipper.tiktok") && !pkg.contains("android")) {
            isTikTokInForeground = TIKTOK_PACKAGES.contains(pkg) ||
                    pkg.contains("musically") ||
                    pkg.contains("aweme") ||
                    pkg.contains("trill") ||
                    pkg.contains("tiktok")
        }
    }

    private fun notifyAndFastForward(adLabel: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "⏩ Перемотка рекламы ($adLabel)...", Toast.LENGTH_SHORT).show()
        }
        performTimelineFastForward()
    }

    /**
     * Выполняет точную перемотку ролика вперед по таймлайну TikTok:
     * Координаты SeekBar на 1080x2340: bounds [36, 2103][1044, 2187], центр Y = 2145px
     * Перетаскивает ползунок вперед вправо, пропуская рекламную вставку (~4-5 секунд).
     */
    fun performTimelineFastForward() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        // Точная Y-координата SeekBar: ~91.6% высоты экрана (2145px на 2340px)
        val timelineY = screenHeight * 0.9167f

        // Ищем SeekBar в дереве окон для абсолютно точных границ, если доступно
        var startX = screenWidth * 0.25f
        var endX = screenWidth * 0.70f

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            try {
                val seekBars = rootNode.findAccessibilityNodeInfosByViewId("com.zhiliaoapp.musically:id/uxs")
                if (!seekBars.isNullOrEmpty()) {
                    val rect = android.graphics.Rect()
                    seekBars[0].getBoundsInScreen(rect)
                    val currentTimelineY = rect.centerY().toFloat()
                    val barWidth = rect.width().toFloat()
                    startX = rect.left + barWidth * 0.20f
                    endX = (startX + barWidth * 0.45f).coerceAtMost(rect.right.toFloat() - 20f)
                    dispatchSwipe(startX, endX, currentTimelineY)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not find seek bar view node, falling back to screen metrics", e)
            } finally {
                rootNode.recycle()
            }
        }

        dispatchSwipe(startX, endX, timelineY)
    }

    private fun dispatchSwipe(startX: Float, endX: Float, y: Float) {
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        // Длительность 350мс гарантирует, что TikTok воспримет жест именно как драг ползунка без паузы
        val stroke = GestureDescription.StrokeDescription(path, 0, 350)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Timeline drag fast forward completed from $startX to $endX at Y=$y")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Timeline drag fast forward cancelled")
            }
        }, null)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        screenshotJob?.cancel()
        screenshotExecutor.shutdown()
        instance = null
        Log.d(TAG, "SkipperAccessibilityService destroyed")
    }
}
