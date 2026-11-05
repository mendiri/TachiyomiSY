package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Free translator that needs no account or API key: the text is read on the device with ML Kit
 * text recognition and translated with the public Google Translate web endpoint.
 */
@Inject
@SingleIn(AppScope::class)
class FreePageTranslator(
    private val networkHelper: NetworkHelper,
    private val readerPreferences: ReaderPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** @param rightToLeft whether the page is read right to left (Japanese manga). */
    suspend fun translate(
        slices: List<Bitmap>,
        rightToLeft: Boolean,
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val originals = slices.flatMap { recognize(it, rightToLeft) }
        val target = languageCode(readerPreferences.translationTargetLanguage.get())
        val permits = Semaphore(MAX_PARALLEL_REQUESTS)
        originals
            .map { original ->
                async { permits.withPermit { TranslatedBlock(original, translateText(original, target)) } }
            }
            .awaitAll()
    }

    /** Reads the text blocks of [bitmap] in reading order. */
    private suspend fun recognize(bitmap: Bitmap, rightToLeft: Boolean): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }

        val rowHeight = (bitmap.height / ROWS_PER_PAGE).coerceAtLeast(1)
        return text.textBlocks
            .filter { it.boundingBox != null }
            .sortedWith(
                compareBy<Text.TextBlock> { it.boundingBox!!.top / rowHeight }
                    .thenBy { it.boundingBox!!.horizontalOrder(rightToLeft) },
            )
            .map { block -> normalize(block.lines.map { it.text }) }
            .filter { line -> line.count { it.isLetter() } >= MIN_LETTERS }
    }

    private fun Rect.horizontalOrder(rightToLeft: Boolean) = if (rightToLeft) -right else left

    /** Joins the lines of a bubble and turns comic ALL CAPS lettering into normal capitalization. */
    private fun normalize(lines: List<String>): String {
        val joined = buildString {
            lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                when {
                    isEmpty() -> append(line)
                    endsWith("-") && line.first().isLetter() -> {
                        deleteCharAt(length - 1)
                        append(line)
                    }
                    else -> append(' ').append(line)
                }
            }
        }
        val letters = joined.filter { it.isLetter() }
        if (letters.isEmpty() || letters.count { it.isUpperCase() } < letters.length * 0.8) return joined

        val lower = joined.lowercase(Locale.ENGLISH)
            .replace(STANDALONE_I) { "I" }
        val result = StringBuilder(lower)
        var capitalizeNext = true
        for (i in result.indices) {
            val c = result[i]
            if (capitalizeNext && c.isLetter()) {
                result.setCharAt(i, c.uppercaseChar())
                capitalizeNext = false
            } else if (c == '.' || c == '!' || c == '?') {
                capitalizeNext = true
            }
        }
        return result.toString()
    }

    private fun translateText(text: String, target: String): String {
        val url = TRANSLATE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val request = Request.Builder().url(url).get().build()
        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val error = "Google Translate (HTTP ${response.code})"
                if (response.code == 429 || response.code >= 500) throw TemporaryTranslationException(error)
                throw TranslationException(error)
            }
            val root = json.parseToJsonElement(response.body.string()) as? JsonArray
                ?: throw TranslationException("Unexpected response from Google Translate")
            val segments = root.firstOrNull() as? JsonArray ?: return ""
            return segments.joinToString("") { segment ->
                ((segment as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull).orEmpty()
            }.trim()
        }
    }

    companion object {
        private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val MAX_PARALLEL_REQUESTS = 4
        private const val ROWS_PER_PAGE = 12
        private const val MIN_LETTERS = 2
        private val STANDALONE_I = Regex("\\bi\\b")

        private val LANGUAGE_NAMES = mapOf(
            "es" to listOf("español", "espanol", "spanish", "castellano"),
            "en" to listOf("inglés", "ingles", "english"),
            "fr" to listOf("francés", "frances", "french", "français"),
            "pt" to listOf("portugués", "portugues", "portuguese", "português"),
            "it" to listOf("italiano", "italian"),
            "de" to listOf("alemán", "aleman", "german", "deutsch"),
            "ca" to listOf("catalán", "catalan", "català"),
            "gl" to listOf("gallego", "galician", "galego"),
            "eu" to listOf("euskera", "basque", "vasco"),
            "ja" to listOf("japonés", "japones", "japanese"),
        )

        /** Turns the free-text target language of the settings ("Español", "es"...) into a language code. */
        fun languageCode(name: String): String {
            val value = name.trim().lowercase(Locale.ROOT)
            if (value.isEmpty()) return Locale.getDefault().language
            if (value.length in 2..3 && value.all { it.isLetter() }) return value
            LANGUAGE_NAMES.entries.firstOrNull { (_, names) -> names.any { value.startsWith(it) } }
                ?.let { return it.key }
            return Locale.getAvailableLocales().firstOrNull { locale ->
                value.startsWith(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)) ||
                    value.startsWith(locale.getDisplayLanguage(locale).lowercase(Locale.ROOT))
            }?.language?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().language
        }
    }
}
