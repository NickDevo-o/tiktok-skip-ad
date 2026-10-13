package com.skipper.tiktok

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvTikTokDetection: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnLaunchAndMonitor: Button
    private lateinit var tvSkippedCount: TextView
    private lateinit var tvSavedTime: TextView
    private lateinit var btnResetStats: TextView

    private lateinit var switchShowToast: SwitchMaterial
    private lateinit var chipGroupSeek: ChipGroup
    private lateinit var etCustomKeyword: EditText
    private lateinit var btnAddKeyword: Button
    private lateinit var chipGroupKeywords: ChipGroup

    private var detectedTikTokPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SkipperConfig.init(this)
        setContentView(R.layout.activity_main)

        tvTikTokDetection = findViewById(R.id.tvTikTokDetection)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnLaunchAndMonitor = findViewById(R.id.btnToggleService)
        tvSkippedCount = findViewById(R.id.tvSkippedCount)
        tvSavedTime = findViewById(R.id.tvSavedTime)
        btnResetStats = findViewById(R.id.btnResetStats)

        switchShowToast = findViewById(R.id.switchShowToast)
        chipGroupSeek = findViewById(R.id.chipGroupSeek)
        etCustomKeyword = findViewById(R.id.etCustomKeyword)
        btnAddKeyword = findViewById(R.id.btnAddKeyword)
        chipGroupKeywords = findViewById(R.id.chipGroupKeywords)

        findViewById<Button>(R.id.btnOpenTikTok).setOnClickListener {
            openDetectedTikTok()
        }

        detectInstalledTikTok()
        setupSettingsControls()

        btnResetStats.setOnClickListener {
            SkipperConfig.resetStats()
            updateUI()
            Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnDonate).setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("TON Wallet", "UQAjHHEZOrKhrbt3aeQT9Xm3kHt2Xeo3Pnz3ok6HhR8OYzn-")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "💎 Адрес TON кошелька скопирован!", Toast.LENGTH_SHORT).show()
        }

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnLaunchAndMonitor.setOnClickListener {
            if (!SkipperAccessibilityService.isRunning) {
                Toast.makeText(this, "Сначала включите службу спец. возможностей в пункте 1!", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                return@setOnClickListener
            }

            SkipperAccessibilityService.instance?.isMonitoringActive = true
            Toast.makeText(this, "Скиппер активен! Открываем TikTok...", Toast.LENGTH_SHORT).show()
            openDetectedTikTok()
        }
    }

    private fun setupSettingsControls() {
        // 1. Уведомления
        switchShowToast.isChecked = SkipperConfig.showToast
        switchShowToast.setOnCheckedChangeListener { _, isChecked ->
            SkipperConfig.showToast = isChecked
        }

        // 2. Длина перемотки
        val currentPercent = SkipperConfig.seekPercent
        when {
            currentPercent <= 0.20f -> chipGroupSeek.check(R.id.chipSeekFast)
            currentPercent >= 0.30f -> chipGroupSeek.check(R.id.chipSeekDeep)
            else -> chipGroupSeek.check(R.id.chipSeekNormal)
        }

        chipGroupSeek.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds[0]) {
                R.id.chipSeekFast -> SkipperConfig.seekPercent = 0.18f
                R.id.chipSeekNormal -> SkipperConfig.seekPercent = 0.25f
                R.id.chipSeekDeep -> SkipperConfig.seekPercent = 0.35f
            }
        }

        // 3. Пользовательские ключевые слова
        refreshKeywordChips()

        btnAddKeyword.setOnClickListener {
            val word = etCustomKeyword.text.toString().trim()
            if (word.isNotEmpty()) {
                val added = SkipperConfig.addCustomKeyword(word)
                if (added) {
                    etCustomKeyword.text.clear()
                    refreshKeywordChips()
                    Toast.makeText(this, "Добавлен фильтр: $word", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Слово уже добавлено", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshKeywordChips() {
        chipGroupKeywords.removeAllViews()
        for (keyword in SkipperConfig.customKeywords) {
            val chip = Chip(this).apply {
                text = keyword
                isCloseIconVisible = true
                setTextColor(Color.WHITE)
                setChipBackgroundColorResource(android.R.color.darker_gray)
                setOnCloseIconClickListener {
                    SkipperConfig.removeCustomKeyword(keyword)
                    refreshKeywordChips()
                }
            }
            chipGroupKeywords.addView(chip)
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
        val skippedCount = SkipperConfig.skippedAdsCount
        tvSkippedCount.text = skippedCount.toString()

        val totalSeconds = skippedCount * 5
        tvSavedTime.text = if (totalSeconds >= 60) {
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            "${mins}м ${secs}с"
        } else {
            "${totalSeconds} сек"
        }

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
