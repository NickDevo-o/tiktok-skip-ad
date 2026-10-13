package com.skipper.tiktok

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.regex.Pattern

object AdDetector {

    private const val TAG = "AdDetector"

    // Оффлайн-распознаватель текста (ML Kit)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val AD_REGEX_LIST = listOf(
        // 1. СЕТЬ «МУСОР ДРОП» (Musor Drop, Upgrader)
        "(?i)\\b(мусор|musor|mycop|mucor)\\b",
        "(?i)\\b(мусордроп|мусор[\\s\\-_]+дроп|musordrop|musor[\\s\\-_]+drop|mycopdrop|mycop[\\s\\-_]+drop)\\b",
        "(?i)\\b(upgrader|апгрейдер|апгрейд)\\b",
        "(?i)(большой\\s*дроп|высокий\\s*шанс|шанс\\s*на\\s*дроп)",
        "(?i)\\b(bolshoy|bolshoi)\\b",

        // 2. Доменные зоны и ссылки на сайты рулеток
        "(?i)\\b[a-z0-9\\-_]+\\.(gg|fun|run|pro|win|top|bet|xyz|partners)\\b",

        // 3. Другие популярные сайты с кейсами и рулетками
        "(?i)\\b(casedrop|ggdrop|csfail|mycsgo|forcedrop|skinbox|hotcase|easydrop|magicdrop|tastybreak|hellcase|csgo500|skinhouse|скинхаус)\\b",
        "(?i)skin\\s*house|скин\\s*хаус",

        // 3.1 Паттерны рулеток оружия (CS2 скины/ножи в баннерах)
        "(?i)\\b(neo[\\-_ ]*noir|teclu\\s*burner|doppler|karambit|case\\s*hardened|dragon\\s*lore)\\b",

        // 4. Латинские промокоды и термины (только целевые, без одиночных общих слов)
        "(?i)\\b(promocode|promo\\s*code|deposit|depo)\\b",

        // 5. Русские слова (кириллица, связанные с рулетками и кейсами)
        "(?i)\\b(промокод|депозит|деп|балик|халява|кейс|кейсы|рулетка)\\b",

        // 6. Визуальные транслиты латинского OCR для русских букв
        "(?i)\\b(pomokod|npomoko|promokod|depozit|kenc|keuc|keys|balik)\\b"
    ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }

    /**
     * Анализирует битмап кадра на наличие рекламы.
     * Оптимизировано: обрезает кадр до верхней трети/половины экрана (где находятся оверлеи рулеток/баннеров)
     * Возвращает название сработавшего правила или null.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): String? {
        return try {
            // Баннеры рулеток (SkinHouse, MusorDrop, рулетки скинов) монтируются в верхней части экрана (0 .. 45% высоты)
            val cropHeight = (bitmap.height * 0.45f).toInt().coerceAtMost(bitmap.height)
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropHeight)

            val image = InputImage.fromBitmap(croppedBitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text

            if (text.isNotBlank()) {
                val cleanText = text.replace("\n", " ").lowercase()
                Log.d(TAG, "OCR detected in banner area: '$cleanText'")

                for (pattern in AD_REGEX_LIST) {
                    val matcher = pattern.matcher(cleanText)
                    if (matcher.find()) {
                        val matched = matcher.group()
                        Log.i(TAG, "Ad detected! Matched: '$matched' in text: '$cleanText'")
                        return matched
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
            null
        }
    }
}
