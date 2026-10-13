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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

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
    private var powerManager: PowerManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SkipperConfig.init(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isTikTokInForeground = true
        Log.d(TAG, "SkipperAccessibilityService connected")
        startVisualMonitoring()
    }

    /**
     * Последовательный фоновый цикл анализа экрана через takeScreenshotSuspend().
     * Исключает ошибки ERROR_TAKE_SCREENSHOT_INTERVAL_RIGID (код 3) благодаря строго последовательному вызову.
     * Немедленно перематывает баннер, а если он продолжается — мгновенно домотает его на следующем кадре.
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
                    // Экономия аккумулятора: если экран выключен, ждем
                    if (powerManager?.isInteractive == false) {
                        delay(1000)
                        continue
                    }

                    if (!isMonitoringActive || !isTikTokInForeground) {
                        delay(500)
                        continue
                    }

                    // Последовательный снимок экрана (ожидаем завершения предыдущего)
                    val bitmap = takeScreenshotSuspend()
                    if (bitmap == null) {
                        // Если вызов отклонен системой (лимит частоты), даем короткую паузу
                        delay(450)
                        continue
                    }

                    var currentThumbX: Float? = null
                    val matchedAd = try {
                        currentThumbX = detectThumbXFromBitmap(bitmap)
                        AdDetector.analyzeFrame(bitmap)
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }

                    if (matchedAd != null) {
                        val now = System.currentTimeMillis()
                        lastSkipTimestamp = now
                        val totalCount = SkipperConfig.incrementSkippedCount()

                        Log.i(TAG, "Ad detected: '$matchedAd' (Thumb at X=$currentThumbX) -> Fast forwarding timeline!")
                        notifyAndFastForward(matchedAd, currentThumbX, totalCount)

                        // Даем жесту перемотки выполниться на экране
                        delay(350)
                        // Проверяем следующий кадр: если баннер все еще виден, он будет домотан сразу!
                    } else {
                        // Обычный кадр без рекламы — безопасный интервал для соблюдения лимитов Android
                        delay(350)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in visual monitoring loop", e)
                    delay(500)
                }
            }
        }
    }

    /**
     * Обертка над асинхронным takeScreenshot() в suspend-функцию.
     * Гарантирует, что следующий скриншот никогда не будет запрошен до завершения текущего.
     */
    private suspend fun takeScreenshotSuspend(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = try {
                    val hw = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val bmp = Bitmap.wrapHardwareBuffer(hw, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()
                    bmp
                } catch (e: Exception) {
                    null
                }
                if (continuation.isActive) {
                    continuation.resume(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot failed with code $errorCode")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
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

        // Если пользователь перелистнул ролик (скролл ленты) — мгновенно сбрасываем таймштамп
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastSkipTimestamp = 0L
        }
    }

    private fun notifyAndFastForward(adLabel: String, detectedThumbX: Float?, totalCount: Int) {
        if (SkipperConfig.showToast) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "⏩ Перемотка рекламы ($adLabel)... [#$totalCount]", Toast.LENGTH_SHORT).show()
            }
        }
        performTimelineFastForward(detectedThumbX, SkipperConfig.seekPercent)
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

            val testYs = listOf(baseY - 2, baseY, baseY + 2).filter { it in 0 until bitmap.height }

            for (y in testYs) {
                for (x in minX..maxX step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
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
     * Перетаскивает ползунок вперед на настраиваемый процент (по умолчанию 25%, ~8-9 сек).
     */
    fun performTimelineFastForward(detectedThumbX: Float? = null, customSeekPercent: Float? = null) {
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

        val currentX = detectedThumbX ?: (barLeft + barWidth * 0.25f)
        val startX = currentX.coerceIn(barLeft + 10f, barRight - 40f)

        val fraction = customSeekPercent ?: SkipperConfig.seekPercent
        val seekForwardAmount = barWidth * fraction
        val endX = (startX + seekForwardAmount).coerceAtMost(barRight - 15f)

        Log.i(TAG, "Executing timeline drag (${(fraction * 100).toInt()}%) from X=$startX to X=$endX at Y=$timelineY")
        dispatchSwipe(startX, endX, timelineY)
    }

    private fun dispatchSwipe(startX: Float, endX: Float, y: Float) {
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 260)
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
