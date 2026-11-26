package eu.kanade.tachiyomi.ui.reader.translate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A translated text kept to be shared later, together with where it comes from. */
data class SavedNote(
    val id: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val pageNumber: Int,
    val original: String,
    val translation: String,
)

/**
 * Texts the reader saved to share them all at once, e.g. when the chapter is finished. They are
 * kept in the preferences, so they survive the app being closed.
 */
@Inject
@SingleIn(AppScope::class)
class SavedNotesStore(
    private val readerPreferences: ReaderPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val mutableNotes = MutableStateFlow(load())
    val notes: StateFlow<List<SavedNote>> = mutableNotes.asStateFlow()

    /** Saves [note] unless the same text of the same page is already saved. */
    fun add(note: SavedNote) {
        mutableNotes.update { notes ->
            val duplicate = notes.any {
                it.chapterId == note.chapterId && it.pageNumber == note.pageNumber && it.original == note.original
            }
            if (duplicate) notes else notes + note
        }
        persist()
    }

    fun remove(id: Long) {
        mutableNotes.update { notes -> notes.filterNot { it.id == id } }
        persist()
    }

    fun clear() {
        mutableNotes.value = emptyList()
        persist()
    }

    fun hasNotesFor(chapterId: Long): Boolean = mutableNotes.value.any { it.chapterId == chapterId }

    private fun persist() {
        val array = buildJsonArray {
            mutableNotes.value.forEach { note ->
                addJsonObject {
                    put("id", note.id)
                    put("manga", note.mangaTitle)
                    put("chapterId", note.chapterId)
                    put("chapter", note.chapterName)
                    put("page", note.pageNumber)
                    put("original", note.original)
                    put("translation", note.translation)
                }
            }
        }
        readerPreferences.translationSavedNotes.set(array.toString())
    }

    private fun load(): List<SavedNote> {
        val stored = readerPreferences.translationSavedNotes.get()
        if (stored.isBlank()) return emptyList()
        return runCatching {
            (json.parseToJsonElement(stored) as JsonArray).mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                SavedNote(
                    id = obj["id"]?.jsonPrimitive?.long ?: return@mapNotNull null,
                    mangaTitle = obj["manga"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    chapterId = obj["chapterId"]?.jsonPrimitive?.long ?: -1,
                    chapterName = obj["chapter"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pageNumber = obj["page"]?.jsonPrimitive?.int ?: 0,
                    original = obj["original"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    translation = obj["translation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * The saved texts as one text for a notes app, grouped by chapter:
 *
 *     ONE PIECE · Chapter 212
 *
 *     [Pág. 2]
 *     Original
 *     → Translation
 */
fun List<SavedNote>.toNotesText(includeTranslation: Boolean, pageLabel: (Int) -> String): String {
    return groupBy { it.chapterId }.values.joinToString("\n\n\n") { chapterNotes ->
        val first = chapterNotes.first()
        val header = listOf(first.mangaTitle, first.chapterName).filter { it.isNotBlank() }.joinToString(" · ")
        header + "\n\n" + chapterNotes.joinToString("\n\n") { note ->
            val text = if (includeTranslation && note.translation.isNotBlank()) {
                "${note.original}\n→ ${note.translation}"
            } else {
                note.original
            }
            "[${pageLabel(note.pageNumber)}]\n$text"
        }
    }
}
