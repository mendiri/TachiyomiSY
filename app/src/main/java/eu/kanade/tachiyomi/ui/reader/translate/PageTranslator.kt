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
    /** Where the bubble is on the page, if known. */
    val box: BlockBox? = null,
)

/** A rectangle on the page, with coordinates from 0 to 1 relative to the whole page. */
data class BlockBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Part of a page sent to be read. Very tall pages (webtoon strips) are cut in several slices,
 * [top] being where this slice starts in the page, in pixels of [pageHeight].
 */
class PageSlice(
    val bitmap: Bitmap,
    val top: Int,
    val pageHeight: Int,
) {
    /** Turns a box relative to this slice (0 to 1) into a box relative to the whole page. */
    fun toPageBox(left: Float, top: Float, right: Float, bottom: Float): BlockBox? {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        if (r <= l || b <= t) return null
        val scale = bitmap.height.toFloat() / pageHeight
        val offset = this.top.toFloat() / pageHeight
        return BlockBox(l, offset + t * scale, r, offset + b * scale)
    }
}

open class TranslationException(message: String) : Exception(message)

/** The service is overloaded or rate limited: worth retrying later or with another service. */
class TemporaryTranslationException(message: String) : TranslationException(message)

enum class TranslationEngine(val value: String) {
    /** Google Gemini reads and translates the page image. Needs an API key. */
    Gemini("gemini"),

    /** On-device text recognition (ML Kit) + Google Translate. Free, no account needed. */
    Free("free"),

    /** On-device text recognition (ML Kit) + Microsoft Azure Translator. Needs an Azure key. */
    Azure("azure"),
    ;

    companion object {
        fun fromValue(value: String) = entries.find { it.value == value } ?: Gemini
    }
}

class TranslationOutcome(
    val blocks: List<TranslatedBlock>,
    val engine: TranslationEngine,
    /** True when Gemini failed and another translator was used instead. */
    val usedFallback: Boolean,
    /** The page, to draw the translations over it. */
    val image: Bitmap?,
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
        get() = engine != TranslationEngine.Gemini || gemini.isConfigured

    suspend fun translate(
        openStream: () -> InputStream,
        rightToLeft: Boolean,
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        val (page, slices) = decodeSlices(openStream)
        try {
            val (blocks, usedEngine, usedFallback) = when (engine) {
                TranslationEngine.Free, TranslationEngine.Azure -> {
                    val useAzure = engine == TranslationEngine.Azure
                    Triple(free.translate(slices, rightToLeft, useAzure), engine, false)
                }
                TranslationEngine.Gemini -> try {
                    Triple(gemini.translate(slices), TranslationEngine.Gemini, false)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!readerPreferences.translationFallbackToFree.get()) throw e
                    logcat(LogPriority.WARN, e) { "Gemini failed, using another translator" }
                    fallback(slices, rightToLeft)
                }
            }
            TranslationOutcome(blocks, usedEngine, usedFallback, displayImage(page))
        } finally {
            slices.forEach { if (it.bitmap !== page) it.bitmap.recycle() }
        }
    }

    /** A copy of the page small enough to be kept in memory while it's shown. */
    private fun displayImage(page: Bitmap): Bitmap {
        val pixels = page.width.toLong() * page.height
        if (pixels <= MAX_DISPLAY_PIXELS) return page
        val ratio = kotlin.math.sqrt(MAX_DISPLAY_PIXELS.toDouble() / pixels)
        val width = (page.width * ratio).toInt().coerceAtLeast(1)
        val height = (page.height * ratio).toInt().coerceAtLeast(1)
        return page.scale(width, height).also { if (it !== page) page.recycle() }
    }

    /** Azure first when it's set up (official API), Google Translate otherwise or if Azure fails. */
    private suspend fun fallback(
        slices: List<PageSlice>,
        rightToLeft: Boolean,
    ): Triple<List<TranslatedBlock>, TranslationEngine, Boolean> {
        if (free.isAzureConfigured) {
            try {
                return Triple(free.translate(slices, rightToLeft, true), TranslationEngine.Azure, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Azure failed, using Google Translate" }
            }
        }
        return Triple(free.translate(slices, rightToLeft, false), TranslationEngine.Free, true)
    }

    /**
     * Decodes the page. Very tall images (webtoon strips) are cut in several slices so the text
     * keeps enough resolution to be read.
     */
    private fun decodeSlices(openStream: () -> InputStream): Pair<Bitmap, List<PageSlice>> {
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
        if (bitmap.height <= MAX_SLICE_HEIGHT + MIN_SLICE_HEIGHT) {
            return bitmap to listOf(PageSlice(bitmap, 0, bitmap.height))
        }

        val slices = mutableListOf<PageSlice>()
        var top = 0
        while (top < bitmap.height) {
            val remaining = bitmap.height - top
            // Don't leave a tiny strip on its own: keep it in this slice instead
            val sliceHeight = if (remaining <= MAX_SLICE_HEIGHT + MIN_SLICE_HEIGHT) remaining else MAX_SLICE_HEIGHT
            slices += PageSlice(Bitmap.createBitmap(bitmap, 0, top, bitmap.width, sliceHeight), top, bitmap.height)
            top += sliceHeight
        }
        return bitmap to slices
    }

    companion object {
        private const val MAX_WIDTH = 1536
        private const val MAX_SLICE_HEIGHT = 3072
        private const val MIN_SLICE_HEIGHT = 768
        private const val MAX_DISPLAY_PIXELS = 12_000_000L
    }
}
