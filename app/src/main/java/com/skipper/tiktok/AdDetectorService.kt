package com.skipper.tiktok

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AdDetectorService : Service() {

    companion object {
        private const val TAG = "AdDetectorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tiktok_skipper_channel"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var isRunning: Boolean = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320
    private var realWidth = 1080
    private var realHeight = 2400

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastSkipTimestamp = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        initScreenMetrics()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != -1 && resultData != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
            startFrameAnalysisLoop()
        } else {
            Log.e(TAG, "MediaProjection permission data missing!")
            stopSelf()
        }

        return START_STICKY
    }

    private fun initScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        realWidth = metrics.widthPixels
        realHeight = metrics.heightPixels

        // Для высокой четкости OCR берём 70% от исходного разрешения экрана
        screenWidth = (metrics.widthPixels * 0.7f).toInt()
        screenHeight = (metrics.heightPixels * 0.7f).toInt()
        screenDensity = metrics.densityDpi
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TikTokScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun startFrameAnalysisLoop() {
        serviceScope.launch {
            while (isActive) {
                val isTikTokOpen = SkipperAccessibilityService.instance?.isTikTokInForeground ?: true

                if (isTikTokOpen) {
                    val now = System.currentTimeMillis()
                    // Интервал повторного пропуска — не чаще раза в 2.5 секунды
                    if (now - lastSkipTimestamp > 2500) {
                        captureAndCheckFrame()
                    }
                }

                // Сканируем каждые 300 мс (быстрая реакция на 4-секундный баннер)
                delay(300)
            }
        }
    }

    private suspend fun captureAndCheckFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Анализируем всю полезную высоту экрана (от 10% до 90%), отсекая только верхний статус-бар
            val cropY = (screenHeight * 0.1f).toInt()
            val cropHeight = (screenHeight * 0.8f).toInt()
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, cropY, screenWidth, cropHeight)

            val matchedAd = AdDetector.analyzeFrame(croppedBitmap)
            if (matchedAd != null) {
                lastSkipTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Ad banner detected: '$matchedAd'! Performing fast forward gesture...")

                // Перемотка на UI-потоке с визуальным Toast-уведомлением
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "⏩ Реклама обнаружена! Перемотка...", Toast.LENGTH_SHORT).show()

                    val service = SkipperAccessibilityService.instance
                    if (service != null) {
                        service.performTimelineFastForward()
                    } else {
                        Log.e(TAG, "SkipperAccessibilityService instance is null!")
                    }
                }
            }

            if (!bitmap.isRecycled) bitmap.recycle()
            if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TikTok Ad Skipper Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok Ad Skipper")
            .setContentText("Мониторинг активен: автоперемотка включена")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        isRunning = false
        Log.d(TAG, "AdDetectorService stopped")
    }
}
