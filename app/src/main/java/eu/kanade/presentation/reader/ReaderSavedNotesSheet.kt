package eu.kanade.presentation.reader

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.translate.SavedNote
import eu.kanade.tachiyomi.ui.reader.translate.toNotesText
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.DeleteSweep
import mihon.icons.materialsymbols.rounded.Share
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource as contextStringResource

/**
 * The texts saved to share later, to share or copy them all at once. Shown when a chapter with
 * saved texts is finished, or on demand.
 *
 * @param finishedChapter name of the chapter just finished, when it's shown for that reason.
 */
@Composable
fun ReaderSavedNotesSheet(
    notes: List<SavedNote>,
    finishedChapter: String?,
    includeTranslation: Boolean,
    onRemove: (SavedNote) -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val text = notes.toNotesText(includeTranslation) { pageNumberLabel(context, it) }
    var confirmClear by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(SYMR.strings.saved_notes_title, notes.size),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (finishedChapter != null) {
                        Text(
                            text = stringResource(SYMR.strings.saved_notes_finished_chapter, finishedChapter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (notes.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.DeleteSweep,
                            contentDescription = stringResource(SYMR.strings.saved_notes_clear),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.small))

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(SYMR.strings.saved_notes_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    contentPadding = PaddingValues(MaterialTheme.padding.medium),
                ) {
                    items(notes, key = { it.id }) { note ->
                        SavedNoteItem(note = note, onRemove = { onRemove(note) })
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    OutlinedButton(
                        onClick = { context.copyText(text) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(MaterialSymbols.Rounded.ContentCopy, contentDescription = null)
                        Text(
                            text = stringResource(SYMR.strings.translation_copy_all),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Button(
                        onClick = { context.shareText(text) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(MaterialSymbols.Rounded.Share, contentDescription = null)
                        Text(
                            text = stringResource(SYMR.strings.translation_share_all),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(SYMR.strings.saved_notes_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = MaterialTheme.padding.medium,
                        end = MaterialTheme.padding.medium,
                        top = MaterialTheme.padding.small,
                    ),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(SYMR.strings.saved_notes_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) {
                    Text(stringResource(SYMR.strings.saved_notes_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SavedNoteItem(note: SavedNote, onRemove: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(modifier = Modifier.weight(1f).padding(vertical = MaterialTheme.padding.small)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${note.chapterName} · ${pageNumberLabel(context, note.pageNumber)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = note.original,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (note.translation.isNotBlank()) {
                        Text(text = note.translation, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}

private fun pageNumberLabel(context: Context, page: Int): String {
    return context.contextStringResource(SYMR.strings.translation_page_number, page)
}
