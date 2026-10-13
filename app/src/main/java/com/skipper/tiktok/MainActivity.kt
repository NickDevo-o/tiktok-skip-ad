package com.skipper.tiktok

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvTikTokDetection: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnLaunchAndMonitor: Button

    private var detectedTikTokPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTikTokDetection = findViewById(R.id.tvTikTokDetection)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnLaunchAndMonitor = findViewById(R.id.btnToggleService)

        // Кнопку "Открыть TikTok" делаем дублем или скрываем за ненадобностью
        findViewById<Button>(R.id.btnOpenTikTok).setOnClickListener {
            openDetectedTikTok()
        }

        detectInstalledTikTok()

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Главная кнопка: включает мониторинг и СРАЗУ открывает TikTok без каких-либо диалогов!
        btnLaunchAndMonitor.setOnClickListener {
            if (!SkipperAccessibilityService.isRunning) {
                Toast.makeText(this, "Сначала включите службу спец. возможностей в пункте 1!", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                return@setOnClickListener
            }

            // Активируем мониторинг
            SkipperAccessibilityService.instance?.isMonitoringActive = true

            Toast.makeText(this, "Скиппер активен! Открываем TikTok...", Toast.LENGTH_SHORT).show()

            // МГНОВЕННО ОТКРЫВАЕМ TIKTOK! Никаких вопросов и диалогов!
            openDetectedTikTok()
        }
    }

    override fun onResume() {
        super.onResume()
        detectInstalledTikTok()
        updateUI()
    }

    private fun detectInstalledTikTok() {
        val pm = packageManager
        val candidatePackages = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.aweme.lite",
            "com.tiktok.mod"
        )

        detectedTikTokPackage = null
        for (pkg in candidatePackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                detectedTikTokPackage = pkg
                break
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }

        if (detectedTikTokPackage == null) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val pkg = app.packageName.lowercase()
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (pkg.contains("musically") || pkg.contains("aweme") || pkg.contains("trill") ||
                    label.contains("tiktok") || label.contains("тикток") || label.contains("tik tok")) {
                    detectedTikTokPackage = app.packageName
                    break
                }
            }
        }

        val btnOpenTikTok = findViewById<Button>(R.id.btnOpenTikTok)
        if (detectedTikTokPackage != null) {
            try {
                val appInfo = pm.getApplicationInfo(detectedTikTokPackage!!, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                tvTikTokDetection.text = "TikTok найден: $appName"
                tvTikTokDetection.setTextColor(Color.parseColor("#4CAF50"))
                btnOpenTikTok.isEnabled = true
                btnOpenTikTok.text = "Открыть $appName"
            } catch (e: Exception) {
                tvTikTokDetection.text = "TikTok найден: $detectedTikTokPackage"
                tvTikTokDetection.setTextColor(Color.parseColor("#4CAF50"))
                btnOpenTikTok.isEnabled = true
            }
        } else {
            tvTikTokDetection.text = "TikTok не найден на устройстве"
            tvTikTokDetection.setTextColor(Color.parseColor("#FF9800"))
            btnOpenTikTok.isEnabled = false
            btnOpenTikTok.text = "TikTok не найден"
        }
    }

    private fun openDetectedTikTok() {
        val pkg = detectedTikTokPackage
        if (pkg != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Не удалось открыть $pkg", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Клиент TikTok не найден!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        val tvServiceStatus = findViewById<TextView>(R.id.tvServiceStatus)
        if (SkipperAccessibilityService.isRunning) {
            tvAccessibilityStatus.text = "1. Служба спец. возможностей: ПОДКЛЮЧЕНА"
            tvAccessibilityStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnEnableAccessibility.isEnabled = false
            btnEnableAccessibility.text = "Включено"

            tvServiceStatus.text = "2. Авто-скиппер: ГОТОВ К РАБОТЕ"
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnLaunchAndMonitor.text = "Войти в TikTok с авто-скиппером"
            btnLaunchAndMonitor.setBackgroundColor(Color.parseColor("#FE2C55"))
        } else {
            tvAccessibilityStatus.text = "1. Служба спец. возможностей: НЕ АКТИВНА"
            tvAccessibilityStatus.setTextColor(Color.parseColor("#FF5252"))
            btnEnableAccessibility.isEnabled = true
            btnEnableAccessibility.text = "Включить в настройках"

            tvServiceStatus.text = "2. Ожидание включения службы"
            tvServiceStatus.setTextColor(Color.parseColor("#AAAAAA"))
            btnLaunchAndMonitor.text = "Сначала включите пункт 1"
            btnLaunchAndMonitor.setBackgroundColor(Color.parseColor("#424242"))
        }
    }
}
