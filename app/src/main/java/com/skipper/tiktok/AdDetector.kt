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

    // Кроссплатформенный Unicode-разделитель слов для Android
    private fun w(regex: String): String = "(?:^|[^\\p{L}\\p{Nd}])($regex)(?:$|[^\\p{L}\\p{Nd}])"

    private val AD_REGEX_LIST = listOf(
        // 1. СЕТЬ «МУСОР ДРОП» (Musor Drop, Upgrader)
        // Учитывает как полное написание, так и потерю первой буквы логотипа (usor, усор, ycop)
        w("(?:m|м)?(?:usor|усор|ycop|ucor|ysor|ycor)"),
        w("(?:m|м)?(?:usordrop|усордроп|ycopdrop|ysordrop|ucordrop)"),
        w("(?:мусор|musor|mycop|usor|ycop)[\\s\\-_]*(?:дроп|drop|dpop|apon)"),
        w("upgrader|апгрейдер|апгрейд|апгрейдить|unrpana|anrpeu|upgrade"),
        "большой\\s*дроп|высокий\\s*шанс|шанс\\s*на\\s*дроп|выбей\\s*нож",
        w("bolshoy|bolshoi"),

        // Бонусные проценты рулеток (+15%, +20%, +30% к депу — присутствуют на 100% рекламных баннеров кейсов)
        "\\+\\s*(?:1[0-9]|2[0-9]|3[0-9]|4[0-9]|50|100)\\s*%",
        "(?:бонус|bonus)[\\s\\-_]*(?:к|k)?[\\s\\-_]*(?:деп|dep)",

        // 2. Доменные зоны и ссылки на сайты рулеток
        w("[a-z0-9\\-_]+\\.(?:gg|fun|run|pro|win|top|bet|xyz|partners|drop)"),

        // 3. Другие популярные сайты с кейсами и рулетками
        w("casedrop|ggdrop|csfail|mycsgo|forcedrop|skinbox|hotcase|easydrop|magicdrop|tastybreak|hellcase|csgo500|skinhouse|скинхаус|battledrop|upx|up\\-x"),
        "skin\\s*house|скин\\s*хаус|cknhxayc|ckuhxayc",

        // 3.1 Паттерны рулеток оружия (CS2 скины/ножи в баннерах)
        "neo[\\-_ ]*noir|teclu\\s*burner|doppler|karambit|case\\s*hardened|dragon\\s*lore|допплер|керамбит",

        // 4. Промокоды, депозиты, халява (Русский + Латиница)
        w("promocode|promo\\s*code|промокод|промо-код|промик|депозит|деп|балик|халява|халявный|кейс|кейсы|рулетка|cases|драгонмани|драгон|1win"),

        // 5. Визуальные транслиты латинского OCR для русских букв
        w("pomokod|npomoko|npomokod|promokod|depozit|depo|kenc|keuc|keys|balik|xaljava|xalyava")
    ).map {
        Pattern.compile(
            it,
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    /**
     * Анализирует битмап кадра на наличие рекламы.
     * Сканирует от 5% до 75% высоты экрана (полная область видеоконтента, отсекая лишь верхний статус-бар и нижние титры/хештеги).
     * Масштабирует изображение до ширины 540px для молниеносного распознавания.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): String? {
        var croppedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            val topOffset = (bitmap.height * 0.05f).toInt()
            val cropHeight = (bitmap.height * 0.70f).toInt().coerceAtMost(bitmap.height - topOffset)

            croppedBitmap = Bitmap.createBitmap(bitmap, 0, topOffset, bitmap.width, cropHeight)

            val targetWidth = 540
            val targetHeight = (cropHeight * (targetWidth.toFloat() / bitmap.width)).toInt()
            scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, false)

            val image = InputImage.fromBitmap(scaledBitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text

            if (text.isNotBlank()) {
                val cleanText = text.replace("\n", " ").lowercase()

                // 1. Проверяем встроенный список правил
                for (pattern in AD_REGEX_LIST) {
                    val matcher = pattern.matcher(cleanText)
                    if (matcher.find()) {
                        val matched = if (matcher.groupCount() >= 1 && matcher.group(1) != null) {
                            matcher.group(1)
                        } else {
                            matcher.group()
                        }
                        Log.i(TAG, "Ad detected! Matched: '$matched' in text: '$cleanText'")
                        return matched
                    }
                }

                // 2. Проверяем пользовательские фильтры из настроек
                val customWords = SkipperConfig.customKeywords
                for (customWord in customWords) {
                    if (cleanText.contains(customWord)) {
                        Log.i(TAG, "Custom ad keyword detected! Matched: '$customWord'")
                        return customWord
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
            null
        } finally {
            if (scaledBitmap != null && scaledBitmap != croppedBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (croppedBitmap != null && !croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }
        }
    }
}
