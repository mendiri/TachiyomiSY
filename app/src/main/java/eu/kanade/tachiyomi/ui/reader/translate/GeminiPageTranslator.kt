package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import android.util.Base64
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One block of text found on a page (a speech bubble, a caption...) and its translation. */
data class TranslatedBlock(
    val original: String,
    val translation: String,
)

open class TranslationException(message: String) : Exception(message)

/** The model is overloaded or rate limited: worth retrying later or with another model. */
class TemporaryTranslationException(message: String) : TranslationException(message)

/**
 * Reads the text of a page image and translates it with the Google Gemini API.
 *
 * The page image is sent to Gemini, which does both the text recognition and the translation, so
 * no OCR model has to be bundled with the app. Very tall images (webtoon strips) are sent in
 * several slices so the text keeps enough resolution to be read.
 */
@Inject
@SingleIn(AppScope::class)
class GeminiPageTranslator(
    private val networkHelper: NetworkHelper,
    private val readerPreferences: ReaderPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        networkHelper.client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    val isConfigured: Boolean
        get() = readerPreferences.translationGeminiApiKey.get().isNotBlank()

    suspend fun translate(openStream: () -> InputStream): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val apiKey = readerPreferences.translationGeminiApiKey.get().trim()
        if (apiKey.isBlank()) throw TranslationException("Missing Gemini API key")
        val model = readerPreferences.translationGeminiModel.get().trim().ifBlank { DEFAULT_MODEL }
        val target = readerPreferences.translationTargetLanguage.get().trim().ifBlank { "Spanish" }

        val slices = decodeSlices(openStream)
        slices.flatMap { jpeg -> requestWithRetries(apiKey, model, target, jpeg) }
    }

    /** Decodes the page and returns it as one or more JPEG slices ready to be sent. */
    private fun decodeSlices(openStream: () -> InputStream): List<ByteArray> {
        val source: Bitmap = try {
            openStream().use { stream ->
                with(ImageUtil) {
                    ImageDecoder.open(stream).use { decoder -> decoder.decodeNext().use { it.toBitmap() } }
                }
            }
        } catch (e: Exception) {
            throw TranslationException("Could not decode the page image: ${e.message}")
        }

        val bitmap = if (source.width > MAX_WIDTH) {
            val height = (source.height.toLong() * MAX_WIDTH / source.width).toInt().coerceAtLeast(1)
            source.scale(MAX_WIDTH, height).also { if (it !== source) source.recycle() }
        } else {
            source
        }

        val slices = mutableListOf<ByteArray>()
        var top = 0
        while (top < bitmap.height) {
            val remaining = bitmap.height - top
            // Don't leave a tiny strip to be sent on its own: keep it in this slice instead
            val sliceHeight = if (remaining <= MAX_SLICE_HEIGHT + MIN_SLICE_HEIGHT) remaining else MAX_SLICE_HEIGHT
            val slice = if (top == 0 && sliceHeight == bitmap.height) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, top, bitmap.width, sliceHeight)
            }
            slices += ByteArrayOutputStream().use { out ->
                slice.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            if (slice !== bitmap) slice.recycle()
            top += sliceHeight
        }
        bitmap.recycle()
        return slices
    }

    /**
     * Gemini often answers 503 (model overloaded) or 429 (rate limited) for a short while. Retry a
     * few times with a growing pause, then try the fallback models before giving up.
     */
    private suspend fun requestWithRetries(
        apiKey: String,
        model: String,
        target: String,
        jpeg: ByteArray,
    ): List<TranslatedBlock> {
        val models = (listOf(model) + FALLBACK_MODELS).distinct()
        var lastError: TemporaryTranslationException? = null
        for (candidate in models) {
            repeat(RETRIES_PER_MODEL) { attempt ->
                try {
                    return request(apiKey, candidate, target, jpeg)
                } catch (e: TemporaryTranslationException) {
                    lastError = e
                    if (attempt < RETRIES_PER_MODEL - 1) delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: TranslationException("Gemini is not available")
    }

    private fun request(apiKey: String, model: String, target: String, jpeg: ByteArray): List<TranslatedBlock> {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                            }
                        }
                        addJsonObject { put("text", prompt(target)) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("original") { put("type", "STRING") }
                            putJsonObject("translation") { put("type", "STRING") }
                        }
                        putJsonArray("required") {
                            add("original")
                            add("translation")
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("$API_URL/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val error = "Gemini $model (HTTP ${response.code}): ${message ?: response.message}"
                if (response.code in TEMPORARY_ERROR_CODES) throw TemporaryTranslationException(error)
                throw TranslationException(error)
            }
            return parse(text)
        }
    }

    private fun parse(responseText: String): List<TranslatedBlock> {
        val root = json.parseToJsonElement(responseText).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val output = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
        if (output == null) {
            val reason = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                ?: candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
            throw TranslationException("Gemini returned no result" + (reason?.let { " ($it)" } ?: ""))
        }

        val cleaned = output.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = runCatching { json.parseToJsonElement(cleaned) as JsonArray }
            .getOrElse { throw TranslationException("Unexpected response from Gemini") }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val original = obj["original"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val translation = obj["translation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (original.isEmpty() && translation.isEmpty()) null else TranslatedBlock(original, translation)
        }
    }

    private fun prompt(target: String) = """
        You are an expert comic, manga and webtoon translator helping a language learner.
        Read ALL the text in this image: speech bubbles, thought bubbles, captions, narration boxes, signs and
        meaningful sound effects. Follow the natural reading order of the page (left-to-right and top-to-bottom for
        Western comics and webtoons, right-to-left for Japanese manga).
        For each separate block of text return:
        - "original": the text exactly as written, joined into a single line (remove the line breaks and
          hyphenation of the lettering). If the lettering is in ALL CAPS, rewrite it with normal sentence
          capitalization. Do not correct or change the wording.
        - "translation": a natural and faithful translation into $target, keeping the tone, slang and register of
          the characters.
        If the image has no text, return an empty array. Do not add any commentary.
    """.trimIndent()

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash")
        private val TEMPORARY_ERROR_CODES = setOf(429, 500, 502, 503, 504)
        private const val RETRIES_PER_MODEL = 2
        private const val RETRY_DELAY_MS = 2_000L
        private const val API_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_WIDTH = 1536
        private const val MAX_SLICE_HEIGHT = 3072
        private const val MIN_SLICE_HEIGHT = 768
        private const val JPEG_QUALITY = 90
    }
}
