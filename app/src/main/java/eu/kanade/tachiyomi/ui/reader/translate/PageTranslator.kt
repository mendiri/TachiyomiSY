package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

/** One block of text found on a page (a speech bubble, a caption...) and its translation. */
data class TranslatedBlock(
    val original: String,
    val translation: String,
)

open class TranslationException(message: String) : Exception(message)

/** The service is overloaded or rate limited: worth retrying later or with another service. */
class TemporaryTranslationException(message: String) : TranslationException(message)

enum class TranslationEngine(val value: String) {
    /** Google Gemini reads and translates the page image. Needs an API key. */
    Gemini("gemini"),

    /** On-device text recognition (ML Kit) + Google Translate. Free, no account needed. */
    Free("free"),
    ;

    companion object {
        fun fromValue(value: String) = entries.find { it.value == value } ?: Gemini
    }
}

data class TranslationOutcome(
    val blocks: List<TranslatedBlock>,
    val engine: TranslationEngine,
    /** True when Gemini failed and the free translator was used instead. */
    val usedFallback: Boolean,
)

/** Picks the translator chosen in the settings, falling back to the free one when Gemini fails. */
@Inject
@SingleIn(AppScope::class)
class PageTranslator(
    private val gemini: GeminiPageTranslator,
    private val free: FreePageTranslator,
    private val readerPreferences: ReaderPreferences,
) {

    val engine: TranslationEngine
        get() = TranslationEngine.fromValue(readerPreferences.translationEngine.get())

    /** Whether the chosen translator can be used without asking the user for anything. */
    val isReady: Boolean
        get() = engine == TranslationEngine.Free || gemini.isConfigured

    suspend fun translate(
        openStream: () -> InputStream,
        rightToLeft: Boolean,
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        val slices = decodeSlices(openStream)
        try {
            if (engine == TranslationEngine.Free) {
                return@withContext TranslationOutcome(free.translate(slices, rightToLeft), TranslationEngine.Free, false)
            }
            try {
                TranslationOutcome(gemini.translate(slices), TranslationEngine.Gemini, false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!readerPreferences.translationFallbackToFree.get()) throw e
                logcat(LogPriority.WARN, e) { "Gemini failed, using the free translator" }
                TranslationOutcome(free.translate(slices, rightToLeft), TranslationEngine.Free, true)
            }
        } finally {
            slices.forEach { it.recycle() }
        }
    }

    /**
     * Decodes the page. Very tall images (webtoon strips) are cut in several slices so the text
     * keeps enough resolution to be read.
     */
    private fun decodeSlices(openStream: () -> InputStream): List<Bitmap> {
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
        if (bitmap.height <= MAX_SLICE_HEIGHT + MIN_SLICE_HEIGHT) return listOf(bitmap)

        val slices = mutableListOf<Bitmap>()
        var top = 0
        while (top < bitmap.height) {
            val remaining = bitmap.height - top
            // Don't leave a tiny strip on its own: keep it in this slice instead
            val sliceHeight = if (remaining <= MAX_SLICE_HEIGHT + MIN_SLICE_HEIGHT) remaining else MAX_SLICE_HEIGHT
            slices += Bitmap.createBitmap(bitmap, 0, top, bitmap.width, sliceHeight)
            top += sliceHeight
        }
        bitmap.recycle()
        return slices
    }

    companion object {
        private const val MAX_WIDTH = 1536
        private const val MAX_SLICE_HEIGHT = 3072
        private const val MIN_SLICE_HEIGHT = 768
    }
}
