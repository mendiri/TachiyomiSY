package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A line of text found by the text recognition, in pixels of the image. */
internal class TextLine(val text: String, val box: Rect)

/**
 * The lines of one speech bubble (or of one caption). [box] is the area to cover with the
 * translation: the inside of the bubble when it was found, or the text grown a little otherwise.
 */
internal class TextGroup(val lines: List<TextLine>, val box: Rect, val inBubble: Boolean) {
    /** Height of the biggest line: sound effects are drawn much bigger than dialogue. */
    val maxLineHeight: Int get() = lines.maxOf { it.box.height() }
}

/**
 * Groups the lines of text by speech bubble.
 *
 * The text recognition often splits one bubble into several blocks, so the bubbles are found in
 * the image instead: a bubble is a light area closed by its outline. Starting next to a line of
 * text, the light pixels around it are filled until the outline is reached. All the lines inside
 * the same filled area belong to the same bubble. Lines that are not inside a closed area (open
 * bubbles, text over the drawing) are grouped with the lines right above or below them.
 */
internal object BubbleGrouper {

    fun group(image: Bitmap, lines: List<TextLine>): List<TextGroup> {
        if (lines.isEmpty()) return emptyList()

        // Work on a smaller copy: it's enough to find the bubbles and much faster
        val scale = min(1f, MAX_DETECTION_WIDTH.toFloat() / image.width)
        val small = if (scale < 1f) {
            image.scale((image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1))
        } else {
            image
        }
        val regions = try {
            Regions(small)
        } finally {
            if (small !== image) small.recycle()
        }

        // Find the bubble of every line
        val bubbleOfLine = lines.map { line -> regions.enclosingRegion(line.box.scaled(scale)) }

        val groups = mutableListOf<TextGroup>()
        val loose = mutableListOf<TextLine>()
        lines.indices.groupBy { bubbleOfLine[it] }.forEach { (region, indices) ->
            val groupLines = indices.map { lines[it] }
            val textBox = groupLines.union()
            val bubble = region?.bounds?.unscaled(scale)
            // A light area much bigger than its text is a panel background, not a bubble
            if (region == null || bubble == null || bubble.area() > textBox.area() * MAX_BUBBLE_TO_TEXT_RATIO) {
                loose += groupLines
                return@forEach
            }
            // Bubbles touching each other can end up as one area: their texts are separate
            // columns, so split them and cover each text on its own
            val columns = groupByProximity(groupLines)
            if (columns.size == 1) {
                groups += TextGroup(groupLines, coverBox(bubble, textBox), inBubble = true)
            } else {
                columns.forEach { column ->
                    groups += TextGroup(column, column.union().grown(LOOSE_GROW_X, LOOSE_GROW_Y), inBubble = true)
                }
            }
        }
        groups += groupByProximity(loose).map { groupLines ->
            TextGroup(groupLines, groupLines.union().grown(LOOSE_GROW_X, LOOSE_GROW_Y), inBubble = false)
        }
        return groups
    }

    /**
     * The bubble shrunk a little, so the rounded box drawn over it stays inside the outline, but
     * always covering the original text.
     */
    private fun coverBox(bubble: Rect, text: Rect): Rect {
        val insetX = (bubble.width() * BUBBLE_INSET).toInt()
        val insetY = (bubble.height() * BUBBLE_INSET).toInt()
        val inner = Rect(bubble.left + insetX, bubble.top + insetY, bubble.right - insetX, bubble.bottom - insetY)
        inner.union(text.grown(0.04f, 0.08f))
        return inner
    }

    /**
     * Joins lines that are stacked one over the other, as the lines of a bubble are. Lines side
     * by side (not overlapping horizontally) stay apart, as they belong to different bubbles.
     */
    private fun groupByProximity(lines: List<TextLine>): List<List<TextLine>> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            return root
        }
        for (i in lines.indices) {
            for (j in i + 1..<lines.size) {
                val a = lines[i].box
                val b = lines[j].box
                val lineHeight = max(a.height(), b.height())
                val gap = max(a.top, b.top) - min(a.bottom, b.bottom)
                val overlap = min(a.right, b.right) - max(a.left, b.left)
                val narrower = min(a.width(), b.width())
                if (gap <= lineHeight * MAX_LINE_GAP && overlap > narrower * MIN_HORIZONTAL_OVERLAP) {
                    parent[find(j)] = find(i)
                }
            }
        }
        return lines.indices.groupBy { find(it) }.values.map { indices -> indices.map { lines[it] } }
    }

    /** The light areas of the image, found on demand by filling from a starting point. */
    private class Regions(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        private val light = BooleanArray(width * height)
        private val labels = IntArray(width * height)
        private val found = mutableListOf<Region>()
        private val stack = IntArray(width * height)

        init {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val dark = BooleanArray(width * height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val luminance = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                dark[i] = luminance < LIGHT_THRESHOLD
            }
            // Thicken the dark lines by a pixel: it closes the small gaps of thin outlines, so
            // the filling doesn't leak from a bubble into the one next to it
            for (y in 0..<height) {
                for (x in 0..<width) {
                    var isLight = true
                    loop@ for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0..<height) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx in 0..<width && dark[ny * width + nx]) {
                                isLight = false
                                break@loop
                            }
                        }
                    }
                    light[y * width + x] = isLight
                }
            }
        }

        /** The closed light area that contains [box], or null if there's none. */
        fun enclosingRegion(box: Rect): Region? {
            val d = max(2, box.height() / 3)
            val seeds = listOf(
                box.left - d to box.centerY(),
                box.right + d to box.centerY(),
                box.centerX() to box.top - d,
                box.centerX() to box.bottom + d,
                box.left - d to box.top - d,
                box.right + d to box.bottom + d,
            )
            return seeds
                .mapNotNull { (x, y) -> regionAt(x, y) }
                .filter { !it.leaked && it.bounds.containsWithMargin(box, 2) }
                .minByOrNull { it.area }
        }

        private fun regionAt(x: Int, y: Int): Region? {
            if (x !in 0..<width || y !in 0..<height) return null
            val start = y * width + x
            if (!light[start]) return null
            if (labels[start] != 0) return found[labels[start] - 1]

            val maxArea = width * height * MAX_BUBBLE_AREA
            val id = found.size + 1
            var area = 0
            var left = x
            var top = y
            var right = x
            var bottom = y
            var leaked = false
            var size = 0
            stack[size++] = start
            labels[start] = id
            while (size > 0) {
                val i = stack[--size]
                area++
                val px = i % width
                val py = i / width
                if (px < left) left = px
                if (px > right) right = px
                if (py < top) top = py
                if (py > bottom) bottom = py
                // Growing too big: it's not a closed bubble. Touching the edge of the image is
                // fine, as bubbles are often cut by it.
                if (area > maxArea) leaked = true
                if (px > 0) size = push(i - 1, id, size)
                if (px < width - 1) size = push(i + 1, id, size)
                if (py > 0) size = push(i - width, id, size)
                if (py < height - 1) size = push(i + width, id, size)
            }
            return Region(area, Rect(left, top, right + 1, bottom + 1), leaked).also { found += it }
        }

        private fun push(i: Int, id: Int, size: Int): Int {
            if (!light[i] || labels[i] != 0) return size
            labels[i] = id
            stack[size] = i
            return size + 1
        }
    }

    private class Region(val area: Int, val bounds: Rect, val leaked: Boolean)

    private fun Rect.containsWithMargin(other: Rect, margin: Int) =
        left - margin <= other.left && top - margin <= other.top &&
            right + margin >= other.right && bottom + margin >= other.bottom

    private fun Rect.scaled(scale: Float) = Rect(
        (left * scale).toInt(),
        (top * scale).toInt(),
        (right * scale).roundToInt(),
        (bottom * scale).roundToInt(),
    )

    private fun Rect.unscaled(scale: Float) = Rect(
        (left / scale).toInt(),
        (top / scale).toInt(),
        (right / scale).roundToInt(),
        (bottom / scale).roundToInt(),
    )

    private fun Rect.area() = width().toLong() * height()

    private fun Rect.grown(x: Float, y: Float): Rect {
        val dx = (width() * x).toInt()
        val dy = (height() * y).toInt()
        return Rect(left - dx, top - dy, right + dx, bottom + dy)
    }

    private fun List<TextLine>.union(): Rect = Rect(first().box).also { rect -> forEach { rect.union(it.box) } }

    private const val MAX_DETECTION_WIDTH = 1280
    private const val LIGHT_THRESHOLD = 200

    /** A bubble can't take more than this part of the image. */
    private const val MAX_BUBBLE_AREA = 0.2f

    /** A bubble is at most this many times bigger than the text inside it. */
    private const val MAX_BUBBLE_TO_TEXT_RATIO = 10

    private const val BUBBLE_INSET = 0.08f
    private const val LOOSE_GROW_X = 0.15f
    private const val LOOSE_GROW_Y = 0.2f
    private const val MAX_LINE_GAP = 0.9f
    private const val MIN_HORIZONTAL_OVERLAP = 0.2f
}
