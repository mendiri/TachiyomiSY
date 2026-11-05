package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Reads the text of a page image and translates it with the Google Gemini API.
 *
 * The page image is sent to Gemini, which does both the text recognition and the translation.
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

    suspend fun translate(slices: List<PageSlice>): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val apiKey = readerPreferences.translationGeminiApiKey.get().trim()
        if (apiKey.isBlank()) throw TranslationException("Missing Gemini API key")
        val model = readerPreferences.translationGeminiModel.get().trim().ifBlank { DEFAULT_MODEL }
        val target = readerPreferences.translationTargetLanguage.get().trim().ifBlank { "Spanish" }

        slices.flatMap { slice ->
            val jpeg = ByteArrayOutputStream().use { out ->
                slice.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            // Boxes come relative to the slice: place them on the whole page
            requestWithRetries(apiKey, model, target, jpeg).map { block ->
                block.copy(box = block.box?.let { slice.toPageBox(it.left, it.top, it.right, it.bottom) })
            }
        }
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
                            putJsonObject("box_2d") {
                                put("type", "ARRAY")
                                putJsonObject("items") { put("type", "INTEGER") }
                            }
                        }
                        putJsonArray("required") {
                            add("original")
                            add("translation")
                            add("box_2d")
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
            if (original.isEmpty() && translation.isEmpty()) return@mapNotNull null
            // [ymin, xmin, ymax, xmax] from 0 to 1000
            val box = (obj["box_2d"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toFloatOrNull() }
                ?.takeIf { it.size == 4 }
                ?.let { (yMin, xMin, yMax, xMax) -> BlockBox(xMin / 1000f, yMin / 1000f, xMax / 1000f, yMax / 1000f) }
            TranslatedBlock(original, translation, box)
        }
    }

    private fun prompt(target: String) = """
        You are an expert comic, manga and webtoon translator helping a language learner.
        Read the text in this image: speech bubbles, thought bubbles, captions, narration boxes and signs. Ignore
        sound effects and onomatopoeia drawn as part of the artwork (like "FWUMP", "SHAKE", "DOOO").
        Return exactly ONE entry per bubble or caption box: all the lines inside the same balloon are a single
        entry, never split a balloon into several entries. Follow the natural reading order of the page
        (left-to-right and top-to-bottom for Western comics and webtoons, right-to-left for Japanese manga).
        For each bubble return:
        - "original": the text exactly as written, joined into a single line (remove the line breaks and
          hyphenation of the lettering). If the lettering is in ALL CAPS, rewrite it with normal sentence
          capitalization. Do not correct or change the wording.
        - "translation": a natural and faithful translation into $target, keeping the tone, slang and register of
          the characters.
        - "box_2d": the bounding box of the whole speech bubble, caption box or text area that contains this text
          (the full balloon, not only the letters), as [ymin, xmin, ymax, xmax] normalized to 0-1000.
        If the image has no text, return an empty array. Do not add any commentary.
    """.trimIndent()

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        val MODELS = listOf(
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-pro-latest",
            "gemini-2.5-pro",
        )
        private val FALLBACK_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash")
        private val TEMPORARY_ERROR_CODES = setOf(429, 500, 502, 503, 504)
        private const val RETRIES_PER_MODEL = 2
        private const val RETRY_DELAY_MS = 2_000L
        private const val API_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val JPEG_QUALITY = 90
    }
}
