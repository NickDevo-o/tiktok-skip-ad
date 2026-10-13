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

    private fun isTikTokPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        if (p.contains("com.skipper.tiktok")) return false
        return p.contains("musically") ||
                p.contains("trill") ||
                p.contains("aweme") ||
                (p.contains("tiktok") && !p.contains("skipper"))
    }

    private fun isSystemOrOurApp(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return p.contains("com.skipper.tiktok") ||
                p.contains("launcher") ||
                p.contains("systemui") ||
                p == "com.android.settings"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SkipperConfig.init(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isTikTokInForeground = true
        Log.d(TAG, "SkipperAccessibilityService connected and ready")
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

                    // Проверка активного приложения
                    val activePkg = try {
                        rootInActiveWindow?.packageName?.toString()
                    } catch (e: Exception) {
                        null
                    }

                    if (activePkg != null) {
                        if (isTikTokPackage(activePkg)) {
                            isTikTokInForeground = true
                        } else if (isSystemOrOurApp(activePkg)) {
                            isTikTokInForeground = false
                        }
                    }

                    if (!isMonitoringActive || !isTikTokInForeground) {
                        delay(600)
                        continue
                    }

                    // Последовательный снимок экрана (ожидаем завершения предыдущего)
                    val bitmap = takeScreenshotSuspend()
                    if (bitmap == null) {
                        delay(450)
                        continue
                    }

                    var currentThumb: ThumbPoint? = null
                    val matchedAd = try {
                        currentThumb = detectThumbFromBitmap(bitmap)
                        AdDetector.analyzeFrame(bitmap)
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }

                    if (matchedAd != null) {
                        val now = System.currentTimeMillis()
                        val wasRecentlySkipped = (now - lastSkipTimestamp) < 1600L
                        lastSkipTimestamp = now
                        val totalCount = SkipperConfig.incrementSkippedCount()

                        Log.i(TAG, "Ad detected: '$matchedAd' (Thumb=$currentThumb, wasRecentlySkipped=$wasRecentlySkipped)")

                        if (wasRecentlySkipped) {
                            Log.i(TAG, "Banner still present after seek -> Rewinding to start")
                            if (SkipperConfig.showToast) {
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(applicationContext, "⏩ Реклама ($matchedAd) -> Повтор ролика", Toast.LENGTH_SHORT).show()
                                }
                            }
                            performTimelineRewindToStart(currentThumb?.x, currentThumb?.y)
                            delay(600)
                        } else {
                            notifyAndFastForward(matchedAd, currentThumb, totalCount)
                            delay(400)
                        }
                    } else {
                        // Обычный кадр без рекламы
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

        if (isTikTokPackage(pkg)) {
            isTikTokInForeground = true
            return
        }

        if (isSystemOrOurApp(pkg)) {
            isTikTokInForeground = false
            return
        }

        // Если пользователь перелистнул ролик (скролл ленты) — мгновенно сбрасываем таймштамп
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastSkipTimestamp = 0L
        }
    }

    data class ThumbPoint(val x: Float, val y: Float)

    private fun notifyAndFastForward(adLabel: String, thumbPoint: ThumbPoint?, totalCount: Int) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        val barLeft = screenWidth * 0.033f
        val barRight = screenWidth * 0.967f
        val barWidth = barRight - barLeft

        val detectedThumbX = thumbPoint?.x
        val timelineY = thumbPoint?.y ?: (screenHeight * 0.92f)

        // Если бегунок во второй половине видео (>45% длины) ИЛИ не определен (реклама почти всегда в концовке):
        // Возвращаем ползунок в начало ролика (00:00), чтобы видео зациклилось без рекламы!
        // Никаких автосвайпов к следующему видео — пользователь сам листает ленту.
        val isNearEnd = detectedThumbX == null || detectedThumbX > (barLeft + barWidth * 0.45f)

        if (isNearEnd) {
            if (SkipperConfig.showToast) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "⏩ Реклама ($adLabel) -> Повтор ролика [#$totalCount]", Toast.LENGTH_SHORT).show()
                }
            }
            performTimelineRewindToStart(detectedThumbX, timelineY)
        } else {
            if (SkipperConfig.showToast) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "⏩ Перемотка рекламы ($adLabel)... [#$totalCount]", Toast.LENGTH_SHORT).show()
                }
            }
            performTimelineFastForward(detectedThumbX, timelineY, SkipperConfig.seekPercent)
        }
    }

    /**
     * Сканирует строку пикселей таймлайна, находя самую правую белую точку (ползунок).
     * Проверяет диапазон высоты от 90% до 99.5% экрана (учитывая как панель вкладок, так и полноэкранный режим).
     */
    private fun detectThumbFromBitmap(bitmap: Bitmap): ThumbPoint? {
        return try {
            val minX = (bitmap.width * 0.033f).toInt()
            val maxX = (bitmap.width * 0.967f).toInt().coerceAtMost(bitmap.width - 1)

            val testYFractions = listOf(0.9167f, 0.910f, 0.923f, 0.930f, 0.980f, 0.985f, 0.990f)
            val testYs = testYFractions.map { (bitmap.height * it).toInt().coerceIn(0, bitmap.height - 1) }.distinct()

            var bestX = -1
            var bestY = -1

            for (y in testYs) {
                for (x in minX..maxX step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    if (r > 165 && g > 165 && b > 165) {
                        if (x > bestX) {
                            bestX = x
                            bestY = y
                        }
                    }
                }
            }

            if (bestX > 0 && bestY > 0) ThumbPoint(bestX.toFloat(), bestY.toFloat()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting thumb from bitmap", e)
            null
        }
    }

    /**
     * Сбрасывает таймлайн в самое начало (00:00), чтобы зациклить ролик без показа рекламы в концовке.
     */
    fun performTimelineRewindToStart(detectedThumbX: Float? = null, timelineY: Float? = null) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val y = timelineY ?: (screenHeight * 0.92f)
        val barLeft = screenWidth * 0.033f
        val barRight = screenWidth * 0.967f

        val currentX = detectedThumbX ?: (barRight - 80f)
        val startX = currentX.coerceIn(barLeft + 80f, barRight - 20f)
        val endX = barLeft + 15f

        Log.i(TAG, "Executing timeline rewind to 00:00 (Loop) from X=$startX to X=$endX at Y=$y")
        dispatchSwipe(startX, endX, y)
        dispatchTap(endX, y)
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 50, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Выполняет точную перемотку ролика вперед по таймлайну TikTok:
     * Перетаскивает ползунок вперед на настраиваемый процент (по умолчанию 25%, ~8-9 сек).
     */
    fun performTimelineFastForward(detectedThumbX: Float? = null, timelineY: Float? = null, customSeekPercent: Float? = null) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val y = timelineY ?: (screenHeight * 0.92f)
        val barLeft = screenWidth * 0.033f
        val barRight = screenWidth * 0.967f
        val barWidth = barRight - barLeft

        val currentX = detectedThumbX ?: (barLeft + barWidth * 0.25f)
        val startX = currentX.coerceIn(barLeft + 10f, barRight - 40f)

        val fraction = customSeekPercent ?: SkipperConfig.seekPercent
        val seekForwardAmount = barWidth * fraction
        val endX = (startX + seekForwardAmount).coerceAtMost(barRight - 15f)

        Log.i(TAG, "Executing timeline drag (${(fraction * 100).toInt()}%) from X=$startX to X=$endX at Y=$y")
        dispatchSwipe(startX, endX, y)
    }

    private fun dispatchSwipe(startX: Float, endX: Float, y: Float) {
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 320)
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
