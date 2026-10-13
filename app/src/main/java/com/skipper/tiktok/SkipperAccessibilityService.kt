package com.skipper.tiktok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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

        private const val SKIP_COOLDOWN_MS = 2500L
    }

    var isMonitoringActive: Boolean = true
    var isTikTokInForeground: Boolean = true
        private set

    private var lastSkipTimestamp = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var screenshotJob: Job? = null
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var powerManager: PowerManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isTikTokInForeground = true
        Log.d(TAG, "SkipperAccessibilityService connected")
        startVisualMonitoring()
    }

    /**
     * Фоновый цикл визуального сканирования через takeScreenshot() (Android 11+).
     * Проверяет видеокадр каждые 500мс для упреждающего детектирования баннеров.
     * Автоматически приостанавливается, если экран выключен (экономия батареи).
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

                    // Экономия аккумулятора: если экран выключен, ждем
                    if (powerManager?.isInteractive == false) {
                        delay(1000)
                        continue
                    }

                    if (!isMonitoringActive || !isTikTokInForeground) {
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastSkipTimestamp < SKIP_COOLDOWN_MS) {
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
                var bitmap: Bitmap? = null
                try {
                    bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()

                    if (bitmap != null) {
                        serviceScope.launch {
                            val frameBitmap = bitmap
                            try {
                                val matchedAd = AdDetector.analyzeFrame(frameBitmap)

                                if (matchedAd != null) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastSkipTimestamp >= SKIP_COOLDOWN_MS) {
                                        lastSkipTimestamp = now
                                        skippedAdsCount++

                                        // Определяем точное текущее положение ползунка на экране по пикселям
                                        val currentThumbX = detectThumbXFromBitmap(frameBitmap)

                                        Log.i(TAG, "Visual ad detected: '$matchedAd' (Thumb at X=$currentThumbX) -> Fast forwarding timeline!")
                                        notifyAndFastForward(matchedAd, currentThumbX)
                                    }
                                }
                            } finally {
                                if (!frameBitmap.isRecycled) {
                                    frameBitmap.recycle()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot", e)
                    if (bitmap != null && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
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

    private fun notifyAndFastForward(adLabel: String, detectedThumbX: Float?) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "⏩ Перемотка рекламы ($adLabel)... [#$skippedAdsCount]", Toast.LENGTH_SHORT).show()
        }
        performTimelineFastForward(detectedThumbX)
    }

    /**
     * Сканирует строку пикселей таймлайна (Y ≈ 91.67% экрана), находя самую правую белую точку (ползунок).
     * Проверяет несколько соседних Y-строк для абсолютной точности на любых разрешениях экрана.
     */
    private fun detectThumbXFromBitmap(bitmap: Bitmap): Float? {
        return try {
            val baseY = (bitmap.height * 0.9167f).toInt().coerceIn(0, bitmap.height - 1)
            var maxWhiteX = -1

            val minX = (bitmap.width * 0.033f).toInt()
            val maxX = (bitmap.width * 0.967f).toInt().coerceAtMost(bitmap.width - 1)

            // Проверяем 3 соседних строки вокруг линии таймлайна
            val testYs = listOf(baseY - 2, baseY, baseY + 2).filter { it in 0 until bitmap.height }

            for (y in testYs) {
                for (x in minX..maxX step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    // Белая точка прогресса / ползунка (R, G, B > 165)
                    if (r > 165 && g > 165 && b > 165) {
                        if (x > maxWhiteX) {
                            maxWhiteX = x
                        }
                    }
                }
            }

            if (maxWhiteX > 0) maxWhiteX.toFloat() else null
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting thumb X from bitmap", e)
            null
        }
    }

    /**
     * Выполняет точную перемотку ролика вперед по таймлайну TikTok:
     * Берет ТОЧНОЕ место, где сейчас находится белая точка ползунка,
     * и плавно перетаскивает ее вперед на +15% длины видео (~4-5 секунд).
     */
    fun performTimelineFastForward(detectedThumbX: Float? = null) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val timelineY = screenHeight * 0.9167f
        val barLeft = screenWidth * 0.033f
        val barRight = screenWidth * 0.967f
        val barWidth = barRight - barLeft

        // Точка захвата: точное место текущего ползунка, либо четверть экрана
        val currentX = detectedThumbX ?: (barLeft + barWidth * 0.25f)
        val startX = currentX.coerceIn(barLeft + 10f, barRight - 40f)

        // Перетаскиваем на +15% длины таймлайна вперед (аккуратные ~4-5 секунд видео, без перескока контента)
        val seekForwardAmount = barWidth * 0.15f
        val endX = (startX + seekForwardAmount).coerceAtMost(barRight - 15f)

        Log.i(TAG, "Executing precise timeline drag from X=$startX to X=$endX at Y=$timelineY")
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
