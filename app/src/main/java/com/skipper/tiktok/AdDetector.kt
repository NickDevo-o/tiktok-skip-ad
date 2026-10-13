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
        w("topskin|top\\s*skin|топскин|топ\\s*скин|top-skin|top_skin"),
        w("casebattle|кейсбатл|case\\-battle|cscase|csgetto|skinon|skinrush"),
        "skin\\s*house|скин\\s*хаус|cknhxayc|ckuhxayc",

        // 3.1 Кнопки действий рулеток («ВЫВЕСТИ», «ОТКРЫТЬ КЕЙС»)
        w("вывести|вывод|выведи|b[b1l]ibect[il]?|bybect[il]?|bibect[il]?|vyvesti|vyvod"),
        "открыть\\s*кейс|открой\\s*кейс|открывай|otkroy\\s*keys",

        // 3.2 Паттерны рулеток оружия (CS2 скины/ножи в баннерах)
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
     * Сканирует от 5% до 88% высоты экрана (полный видеоряд, включая плашки у самого низа).
     * Масштабирует изображение до ширины 540px для молниеносного распознавания.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): String? {
        var croppedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            val topOffset = (bitmap.height * 0.05f).toInt()
            val cropHeight = (bitmap.height * 0.83f).toInt().coerceAtMost(bitmap.height - topOffset)

            croppedBitmap = Bitmap.createBitmap(bitmap, 0, topOffset, bitmap.width, cropHeight)

            val targetWidth = 540
            val targetHeight = (cropHeight * (targetWidth.toFloat() / bitmap.width)).toInt()
            scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, false)

            // 1. Быстрая проверка фирменной кислотно-зеленой плашки «Мусор Дроп» (контейнер / CRT-сетка)
            // Позволяет поймать рекламу уже на первых секундах до появления текста
            if (checkGreenDumpsterSignature(scaledBitmap)) {
                Log.i(TAG, "Ad signature detected: Green Dumpster / Roulette visual banner!")
                return "Мусор Дроп"
            }

            // 2. Распознавание текста через Google ML Kit
            val image = InputImage.fromBitmap(scaledBitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text

            if (text.isNotBlank()) {
                val cleanText = text.replace("\n", " ").lowercase()

                // Проверяем встроенный список правил
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

                // Проверяем пользовательские фильтры из настроек
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

    /**
     * Анализирует нижнюю половину кадра (от 45% до 88% высоты) на наличие кислотно-зеленой
     * заливки контейнера/сетки «Мусор Дроп». Занимает менее 1 миллисекунды на scaledBitmap.
     */
    private fun checkGreenDumpsterSignature(scaledBitmap: Bitmap): Boolean {
        val width = scaledBitmap.width
        val height = scaledBitmap.height

        val startY = (height * 0.40f).toInt()
        val endY = (height * 0.95f).toInt().coerceAtMost(height - 1)
        val sampleHeight = endY - startY
        if (sampleHeight <= 0) return false

        var greenMatchCount = 0
        var totalSampled = 0

        // Прореженная выборка каждого 3-го пикселя для максимальной скорости (0.3 мс)
        for (y in startY..endY step 3) {
            for (x in 0 until width step 3) {
                totalSampled++
                val pixel = scaledBitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                // Фирменный оттенок контейнера/сетки: насыщенный зеленый, преобладающий над красным и синим (синий b строго < 100, исключая циан/бирюзовые кнопки)
                if (g > 130 && g > (r * 1.15f) && g > (b * 1.45f) && b < 100 && (r + b > 45)) {
                    greenMatchCount++
                }
            }
        }

        if (totalSampled == 0) return false
        val percentage = (greenMatchCount.toFloat() / totalSampled) * 100f
        return percentage >= 5.0f
    }
}
