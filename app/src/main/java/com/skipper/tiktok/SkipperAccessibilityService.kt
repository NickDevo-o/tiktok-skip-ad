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

        // Статистика сессии
        var skippedAdsCount: Int = 0
            private set
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
     * Проверяет видеокадр каждые 500мс для упреждающего детектирования баннеров.
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
                    delay(500)

                    if (!isMonitoringActive || !isTikTokInForeground) {
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastSkipTimestamp < 4000) {
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
                                if (now - lastSkipTimestamp >= 4000) {
                                    lastSkipTimestamp = now
                                    skippedAdsCount++
                                    Log.i(TAG, "Visual ad detected: '$matchedAd' (Total skipped: $skippedAdsCount) -> Fast forwarding timeline!")
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

        // Если пользователь перелистнул ролик (скролл ленты) — мгновенно сбрасываем кулдаун!
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (lastSkipTimestamp != 0L) {
                lastSkipTimestamp = 0L
                Log.d(TAG, "User scrolled to next video: cooldown reset to 0ms immediately.")
            }
        }
    }

    private fun notifyAndFastForward(adLabel: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "⏩ Перемотка рекламы ($adLabel)... [#$skippedAdsCount]", Toast.LENGTH_SHORT).show()
        }
        performTimelineFastForward()
    }

    /**
     * Выполняет точную перемотку ролика вперед по таймлайну TikTok:
     * Динамически определяет текущую позицию ползунка (SeekBar) и протягивает его
     * вперед ровно от текущей точки на безопасное расстояние (+30-35% длины видео).
     */
    fun performTimelineFastForward() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        // Стандартная Y-координата SeekBar на экране: ~91.67% высоты (2145px на 2340px)
        var timelineY = screenHeight * 0.9167f
        var startX = screenWidth * 0.25f
        var endX = screenWidth * 0.65f

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            try {
                // Ищем элемент прогресс-бара по ID из мода и официального клиента
                val seekBars = rootNode.findAccessibilityNodeInfosByViewId("com.zhiliaoapp.musically:id/uxs")
                if (!seekBars.isNullOrEmpty()) {
                    val bar = seekBars[0]
                    val rect = android.graphics.Rect()
                    bar.getBoundsInScreen(rect)

                    timelineY = rect.centerY().toFloat()
                    val barWidth = rect.width().toFloat()

                    // Читаем текущий прогресс через RangeInfo, если доступен
                    val rangeInfo = bar.rangeInfo
                    if (rangeInfo != null && rangeInfo.max > 0) {
                        val currentFraction = (rangeInfo.current / rangeInfo.max).coerceIn(0f, 0.9f)
                        startX = rect.left + barWidth * currentFraction
                        // Перематываем на +30% вперед от текущего места, не выходя за пределы бара
                        endX = (startX + barWidth * 0.32f).coerceAtMost(rect.right.toFloat() - 15f)
                    } else {
                        // Если RangeInfo пуст — берем динамический сдвиг от первой трети вперед
                        startX = rect.left + barWidth * 0.25f
                        endX = (rect.left + barWidth * 0.65f).coerceAtMost(rect.right.toFloat() - 15f)
                    }

                    bar.recycle()
                    dispatchSwipe(startX, endX, timelineY)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error calculating dynamic seekbar position", e)
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
