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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text on the device with ML Kit text recognition and translates it with Google
 * Translate (public web endpoint: free, no account or key), DeepL or Microsoft Azure Translator.
 */
@Inject
@SingleIn(AppScope::class)
class FreePageTranslator(
    private val networkHelper: NetworkHelper,
    private val readerPreferences: ReaderPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val isAzureConfigured: Boolean
        get() = readerPreferences.translationAzureKey.get().isNotBlank()

    val isDeepLConfigured: Boolean
        get() = readerPreferences.translationDeepLKey.get().isNotBlank()

    /**
     * @param rightToLeft whether the page is read right to left (Japanese manga).
     * @param service who translates the text: [TranslationEngine.Free] (Google Translate),
     * [TranslationEngine.Azure] or [TranslationEngine.DeepL].
     */
    suspend fun translate(
        slices: List<PageSlice>,
        rightToLeft: Boolean,
        service: TranslationEngine,
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val recognized = slices.flatMap { recognize(it, rightToLeft) }
        val target = languageCode(readerPreferences.translationTargetLanguage.get())
        if (recognized.isEmpty()) return@withContext emptyList()
        val batch = when (service) {
            TranslationEngine.Azure -> translateWithAzure(recognized.map { it.original }, target)
            TranslationEngine.DeepL -> translateWithDeepL(recognized.map { it.original }, target)
            else -> null
        }
        if (batch != null) {
            return@withContext recognized.zip(batch) { block, translation ->
                block.copy(translation = translation)
            }
        }
        val permits = Semaphore(MAX_PARALLEL_REQUESTS)
        recognized
            .map { block ->
                async { permits.withPermit { block.copy(translation = translateText(block.original, target)) } }
            }
            .awaitAll()
    }

    /** Reads the text blocks of a slice in reading order, still untranslated. */
    private suspend fun recognize(slice: PageSlice, rightToLeft: Boolean): List<TranslatedBlock> {
        val bitmap = slice.bitmap
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

        val lines = text.textBlocks
            .flatMap { it.lines }
            // Skip what's probably not dialogue: tilted text and unsure readings are usually sound effects
            .filter { line -> line.confidence >= MIN_CONFIDENCE && kotlin.math.abs(line.angle) <= MAX_ANGLE }
            .mapNotNull { line -> line.boundingBox?.let { TextLine(line.text, it) } }

        val rowHeight = (bitmap.height / ROWS_PER_PAGE).coerceAtLeast(1)
        val usualLineHeight = lines.map { it.box.height() }.sorted().getOrNull(lines.size / 2) ?: 0
        return BubbleGrouper.group(bitmap, lines)
            .sortedWith(
                compareBy<TextGroup> { it.box.top / rowHeight }
                    .thenBy { it.box.horizontalOrder(rightToLeft) },
            )
            .map { group ->
                val ordered = group.lines.sortedWith(compareBy<TextLine> { it.box.top }.thenBy { it.box.left })
                group to normalize(ordered.map { it.text })
            }
            .filter { (group, original) ->
                original.count { it.isLetter() } >= MIN_LETTERS &&
                    // A single big word outside of any bubble is almost always a sound effect
                    (
                        group.inBubble ||
                            original.trim().contains(' ') ||
                            group.maxLineHeight <= usualLineHeight * SOUND_EFFECT_SIZE
                        )
            }
            .map { (group, original) -> TranslatedBlock(original, "", group.box.toPageBox(slice)) }
    }

    private fun Rect.toPageBox(slice: PageSlice): BlockBox? {
        val width = slice.bitmap.width.toFloat()
        val height = slice.bitmap.height.toFloat()
        return slice.toPageBox(left / width, top / height, right / width, bottom / height)
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

    /** Translates all the blocks in one request with Azure Translator (API v3). */
    private fun translateWithAzure(texts: List<String>, target: String): List<String> {
        val key = readerPreferences.translationAzureKey.get().trim()
        if (key.isBlank()) throw TranslationException("Missing Microsoft Azure key")
        val region = readerPreferences.translationAzureRegion.get().trim()

        val body = buildJsonArray {
            texts.forEach { text -> addJsonObject { put("Text", text) } }
        }
        val url = AZURE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("api-version", "3.0")
            .addQueryParameter("to", target)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Ocp-Apim-Subscription-Key", key)
            .apply { if (region.isNotEmpty() && region != "global") header("Ocp-Apim-Subscription-Region", region) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val error = "Microsoft Azure (HTTP ${response.code}): ${message ?: response.message}"
                if (response.code == 429 || response.code >= 500) throw TemporaryTranslationException(error)
                throw TranslationException(error)
            }
            val results = json.parseToJsonElement(text) as? JsonArray
                ?: throw TranslationException("Unexpected response from Microsoft Azure")
            return texts.indices.map { i ->
                (results.getOrNull(i) as? JsonObject)?.get("translations")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            }
        }
    }

    /** Translates all the blocks in one request with the DeepL API (free or pro). */
    private fun translateWithDeepL(texts: List<String>, target: String): List<String> {
        val key = readerPreferences.translationDeepLKey.get().trim()
        if (key.isBlank()) throw TranslationException("Missing DeepL key")
        // Keys of the free plan end with ":fx" and use their own server
        val url = if (key.endsWith(":fx")) DEEPL_FREE_URL else DEEPL_PRO_URL

        val body = buildJsonObject {
            putJsonArray("text") { texts.forEach { add(it) } }
            put("target_lang", deepLLanguage(target))
            // Comic dialogue: keep the informal tone of the characters
            put("formality", "prefer_less")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val error = when (response.code) {
                    403 -> "DeepL (HTTP 403): wrong key"
                    456 -> "DeepL (HTTP 456): monthly character limit reached"
                    else -> "DeepL (HTTP ${response.code}): ${message ?: response.message}"
                }
                if (response.code == 429 || response.code == 456 || response.code >= 500) {
                    throw TemporaryTranslationException(error)
                }
                throw TranslationException(error)
            }
            val translations = json.parseToJsonElement(text).jsonObject["translations"]?.jsonArray
                ?: throw TranslationException("Unexpected response from DeepL")
            return texts.indices.map { i ->
                translations.getOrNull(i)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            }
        }
    }

    /** DeepL wants the variant for some languages, in capitals ("ES", "EN-US", "PT-PT"). */
    private fun deepLLanguage(code: String) = when (code.lowercase(Locale.ROOT)) {
        "en" -> "EN-US"
        "pt" -> "PT-PT"
        "zh" -> "ZH-HANS"
        else -> code.uppercase(Locale.ROOT)
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
        private const val AZURE_URL = "https://api.cognitive.microsofttranslator.com/translate"
        private const val DEEPL_FREE_URL = "https://api-free.deepl.com/v2/translate"
        private const val DEEPL_PRO_URL = "https://api.deepl.com/v2/translate"
        private const val MAX_PARALLEL_REQUESTS = 4
        private const val ROWS_PER_PAGE = 12
        private const val MIN_LETTERS = 2
        private const val MIN_CONFIDENCE = 0.5f
        private const val SOUND_EFFECT_SIZE = 1.8f
        private const val MAX_ANGLE = 20f
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
