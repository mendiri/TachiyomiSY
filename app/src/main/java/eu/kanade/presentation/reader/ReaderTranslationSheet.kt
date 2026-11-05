package eu.kanade.presentation.reader

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.TranslationResult
import eu.kanade.tachiyomi.ui.reader.translate.TranslatedBlock
import eu.kanade.tachiyomi.ui.reader.translate.TranslationEngine
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Share
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shows the text of a page with its translation. Every block, and the whole page, can be copied
 * or shared (for example to a notes app).
 */
@Composable
fun ReaderTranslationSheet(
    result: TranslationResult,
    pageTitle: String,
    includeTranslationWhenCopying: Boolean,
    onDismissRequest: () -> Unit,
    onRetry: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onUseFreeTranslator: () -> Unit,
) {
    val context = LocalContext.current

    fun format(block: TranslatedBlock): String {
        return if (includeTranslationWhenCopying && block.translation.isNotBlank()) {
            "${block.original}\n→ ${block.translation}"
        } else {
            block.original
        }
    }

    fun formatAll(blocks: List<TranslatedBlock>): String {
        return buildString {
            append(pageTitle)
            append("\n\n")
            append(blocks.joinToString("\n\n") { format(it) })
        }
    }

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
                        text = stringResource(SYMR.strings.action_translate_page),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (result is TranslationResult.Success && result.outcome.blocks.isNotEmpty()) {
                    IconButton(onClick = { context.copyText(formatAll(result.outcome.blocks)) }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.ContentCopy,
                            contentDescription = stringResource(SYMR.strings.translation_copy_all),
                        )
                    }
                    IconButton(onClick = { context.shareText(formatAll(result.outcome.blocks)) }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Share,
                            contentDescription = stringResource(SYMR.strings.translation_share_all),
                        )
                    }
                }
                if (result is TranslationResult.Success || result is TranslationResult.Error) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Refresh,
                            contentDescription = stringResource(MR.strings.action_retry),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.small))

            when (result) {
                TranslationResult.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(SYMR.strings.translation_in_progress),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                TranslationResult.MissingApiKey -> {
                    ApiKeyForm(onSaveApiKey = onSaveApiKey, onUseFreeTranslator = onUseFreeTranslator)
                }
                is TranslationResult.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.padding.medium),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        Text(
                            text = stringResource(SYMR.strings.translation_error),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SelectionContainer {
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(onClick = onRetry) {
                            Text(stringResource(MR.strings.action_retry))
                        }
                    }
                }
                is TranslationResult.Success -> {
                    val outcome = result.outcome
                    Text(
                        text = when (outcome.engine) {
                            TranslationEngine.Gemini -> stringResource(SYMR.strings.translation_engine_gemini)
                            TranslationEngine.Free -> stringResource(SYMR.strings.translation_engine_free)
                            TranslationEngine.Azure -> stringResource(SYMR.strings.translation_engine_azure)
                        }.let {
                            if (outcome.usedFallback) {
                                stringResource(SYMR.strings.translation_engine_fallback_note, it)
                            } else {
                                it
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (outcome.usedFallback) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(
                            start = MaterialTheme.padding.medium,
                            end = MaterialTheme.padding.medium,
                            top = MaterialTheme.padding.small,
                        ),
                    )
                    if (outcome.blocks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(SYMR.strings.translation_no_text))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 560.dp),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            contentPadding = PaddingValues(
                                MaterialTheme.padding.medium,
                            ),
                        ) {
                            itemsIndexed(result.outcome.blocks) { _, block ->
                                TranslatedBlockItem(
                                    block = block,
                                    onCopy = { context.copyText(format(block)) },
                                    onShare = { context.shareText(format(block)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslatedBlockItem(
    block: TranslatedBlock,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(modifier = Modifier.weight(1f).padding(vertical = MaterialTheme.padding.small)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = block.original,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (block.translation.isNotBlank()) {
                        Text(
                            text = block.translation,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Column {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.ContentCopy,
                        contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Share,
                        contentDescription = stringResource(MR.strings.action_share),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyForm(onSaveApiKey: (String) -> Unit, onUseFreeTranslator: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(SYMR.strings.translation_missing_api_key),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.trim() },
            label = { Text(stringResource(SYMR.strings.pref_translation_gemini_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onUseFreeTranslator) {
                Text(stringResource(SYMR.strings.translation_use_free))
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = { onSaveApiKey(apiKey) },
                enabled = apiKey.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        }
    }
}

private fun Context.copyText(text: String) {
    copyToClipboard(getString(android.R.string.copy), text)
}

private fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
