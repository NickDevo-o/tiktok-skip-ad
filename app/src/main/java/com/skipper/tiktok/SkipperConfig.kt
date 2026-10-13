package com.skipper.tiktok

import android.content.Context
import android.content.SharedPreferences

object SkipperConfig {
    private const val PREFS_NAME = "tiktok_skipper_prefs"
    private const val KEY_SKIPPED_COUNT = "key_skipped_count"
    private const val KEY_SHOW_TOAST = "key_show_toast"
    private const val KEY_SEEK_PERCENT = "key_seek_percent"
    private const val KEY_CUSTOM_KEYWORDS = "key_custom_keywords"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun getSafePrefs(): SharedPreferences? = prefs

    var skippedAdsCount: Int
        get() = getSafePrefs()?.getInt(KEY_SKIPPED_COUNT, 0) ?: 0
        set(value) {
            getSafePrefs()?.edit()?.putInt(KEY_SKIPPED_COUNT, value)?.apply()
        }

    fun incrementSkippedCount(): Int {
        val next = skippedAdsCount + 1
        skippedAdsCount = next
        return next
    }

    fun resetStats() {
        skippedAdsCount = 0
    }

    var showToast: Boolean
        get() = getSafePrefs()?.getBoolean(KEY_SHOW_TOAST, true) ?: true
        set(value) {
            getSafePrefs()?.edit()?.putBoolean(KEY_SHOW_TOAST, value)?.apply()
        }

    // По умолчанию 22% (эквивалент ~7-8 секунд видео), гарантирующий полный пропуск рекламной вставки
    var seekPercent: Float
        get() = getSafePrefs()?.getFloat(KEY_SEEK_PERCENT, 0.22f) ?: 0.22f
        set(value) {
            getSafePrefs()?.edit()?.putFloat(KEY_SEEK_PERCENT, value)?.apply()
        }

    var customKeywords: Set<String>
        get() = getSafePrefs()?.getStringSet(KEY_CUSTOM_KEYWORDS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            getSafePrefs()?.edit()?.putStringSet(KEY_CUSTOM_KEYWORDS, value)?.apply()
        }

    fun addCustomKeyword(keyword: String): Boolean {
        val trimmed = keyword.trim().lowercase()
        if (trimmed.isEmpty()) return false
        val current = customKeywords.toMutableSet()
        val added = current.add(trimmed)
        if (added) {
            customKeywords = current
        }
        return added
    }

    fun removeCustomKeyword(keyword: String): Boolean {
        val current = customKeywords.toMutableSet()
        val removed = current.remove(keyword.trim().lowercase())
        if (removed) {
            customKeywords = current
        }
        return removed
    }
}
