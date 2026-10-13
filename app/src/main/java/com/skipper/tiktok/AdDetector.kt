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

    // Кроссплатформенный Unicode-разделитель слов для Android (без \b, который сбоит на кириллице)
    private fun w(regex: String): String = "(?:^|[^\\p{L}\\p{Nd}])($regex)(?:$|[^\\p{L}\\p{Nd}])"

    private val AD_REGEX_LIST = listOf(
        // 1. СЕТЬ «МУСОР ДРОП» (Musor Drop, Upgrader)
        w("мусор|musor|mycop|mucor|mysor|mycor"),
        w("мусордроп|мусор[\\s\\-_]+дроп|musordrop|musor[\\s\\-_]+drop|mycopdrop|mycop[\\s\\-_]+drop"),
        w("upgrader|апгрейдер|апгрейд|апгрейдить"),
        "большой\\s*дроп|высокий\\s*шанс|шанс\\s*на\\s*дроп|выбей\\s*нож",
        w("bolshoy|bolshoi"),

        // 2. Доменные зоны и ссылки на сайты рулеток
        w("[a-z0-9\\-_]+\\.(?:gg|fun|run|pro|win|top|bet|xyz|partners)"),

        // 3. Другие популярные сайты с кейсами и рулетками
        w("casedrop|ggdrop|csfail|mycsgo|forcedrop|skinbox|hotcase|easydrop|magicdrop|tastybreak|hellcase|csgo500|skinhouse|скинхаус"),
        "skin\\s*house|скин\\s*хаус",

        // 3.1 Паттерны рулеток оружия (CS2 скины/ножи в баннерах)
        "neo[\\-_ ]*noir|teclu\\s*burner|doppler|karambit|case\\s*hardened|dragon\\s*lore",

        // 4. Промокоды, депозиты, халява (Русский + Латиница)
        w("promocode|promo\\s*code|промокод|промо-код|промик|депозит|деп|балик|халява|халявный|кейс|кейсы|рулетка"),

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
     * Сканирует от 8% до 78% высоты экрана (отсекая статус-бар и нижние кнопки TikTok).
     * Масштабирует изображение до ширины 540px для 4-кратной экономии RAM и 3-кратного ускорения OCR.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): String? {
        var croppedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            val topOffset = (bitmap.height * 0.08f).toInt()
            val cropHeight = (bitmap.height * 0.70f).toInt().coerceAtMost(bitmap.height - topOffset)

            croppedBitmap = Bitmap.createBitmap(bitmap, 0, topOffset, bitmap.width, cropHeight)

            // Масштабируем до 540px ширины для ускорения работы нейросети и оптимизации памяти
            val targetWidth = 540
            val targetHeight = (cropHeight * (targetWidth.toFloat() / bitmap.width)).toInt()
            scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, false)

            val image = InputImage.fromBitmap(scaledBitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text

            if (text.isNotBlank()) {
                val cleanText = text.replace("\n", " ").lowercase()
                Log.d(TAG, "OCR detected in banner area: '$cleanText'")

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
