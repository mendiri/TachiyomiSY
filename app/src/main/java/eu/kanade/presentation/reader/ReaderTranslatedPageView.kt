package eu.kanade.presentation.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.TranslationResult
import eu.kanade.tachiyomi.ui.reader.translate.TranslatedBlock
import eu.kanade.tachiyomi.ui.reader.translate.TranslationEngine
import eu.kanade.tachiyomi.ui.reader.translate.TranslationOutcome
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.sy.materialsymbols.SYMaterialSymbols
import mihon.icons.sy.materialsymbols.rounded.Translate
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shows the page with the translation drawn over every bubble. Tapping a bubble switches between
 * the translation and the original text, and a long press copies it.
 */
@Composable
fun ReaderTranslatedPageView(
    result: TranslationResult,
    pageTitle: String,
    includeTranslationWhenCopying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShowList: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val outcome = (result as? TranslationResult.Success)?.outcome
    var showTranslations by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding(),
        ) {
            Text(
                text = listOfNotNull(pageTitle, outcome?.engineLabel()).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (outcome?.image == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(SYMR.strings.translation_in_progress),
                            color = Color.White,
                        )
                    }
                } else {
                    TranslatedPage(
                        outcome = outcome,
                        showTranslations = showTranslations,
                        onCopy = { context.copyText(it.toNoteText(includeTranslationWhenCopying)) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrevious, enabled = hasPrevious) {
                    Text(stringResource(SYMR.strings.translation_previous_page))
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(onClick = { showTranslations = !showTranslations }, enabled = outcome != null) {
                        Icon(
                            imageVector = SYMaterialSymbols.Rounded.Translate,
                            contentDescription = stringResource(SYMR.strings.translation_toggle_original),
                            tint = if (showTranslations) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onShowList, enabled = outcome != null) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.FormatListNumbered,
                            contentDescription = stringResource(SYMR.strings.translation_show_list),
                        )
                    }
                    IconButton(
                        onClick = {
                            outcome?.let {
                                context.copyText(it.blocks.toNoteText(pageTitle, includeTranslationWhenCopying))
                            }
                        },
                        enabled = outcome != null,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.ContentCopy,
                            contentDescription = stringResource(SYMR.strings.translation_copy_all),
                        )
                    }
                    IconButton(
                        onClick = {
                            outcome?.let {
                                context.shareText(it.blocks.toNoteText(pageTitle, includeTranslationWhenCopying))
                            }
                        },
                        enabled = outcome != null,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Share,
                            contentDescription = stringResource(SYMR.strings.translation_share_all),
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
                TextButton(onClick = onNext, enabled = hasNext) {
                    Text(stringResource(SYMR.strings.translation_next_page))
                }
            }
        }
    }
}

@Composable
private fun TranslationOutcome.engineLabel(): String {
    val name = when (engine) {
        TranslationEngine.Gemini -> "Gemini"
        TranslationEngine.Free -> "Google Translate"
        TranslationEngine.Azure -> "Microsoft Azure"
    }
    return if (usedFallback) stringResource(SYMR.strings.translation_engine_fallback_note, name) else name
}

@Composable
private fun TranslatedPage(
    outcome: TranslationOutcome,
    showTranslations: Boolean,
    onCopy: (TranslatedBlock) -> Unit,
) {
    val image = outcome.image ?: return
    val bitmap = remember(image) { image.asImageBitmap() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val imageRatio = image.height.toFloat() / image.width
        // Fit the page on screen, unless it's a long strip: then fit the width and scroll
        val fitWidth = maxWidth * imageRatio <= maxHeight || imageRatio > LONG_STRIP_RATIO
        val width: Dp = if (fitWidth) maxWidth else maxHeight / imageRatio
        val height: Dp = width * imageRatio

        val scrollModifier = if (height > maxHeight) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        Box(modifier = scrollModifier) {
            Box(modifier = Modifier.size(width, height)) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showTranslations) {
                    outcome.blocks.forEach { block ->
                        val box = block.box ?: return@forEach
                        if (block.translation.isBlank()) return@forEach
                        Bubble(
                            block = block,
                            onCopy = { onCopy(block) },
                            modifier = Modifier
                                .offset(x = width * box.left, y = height * box.top)
                                .size(
                                    width = (width * box.width).coerceAtLeast(MIN_BUBBLE_WIDTH),
                                    height = (height * box.height).coerceAtLeast(MIN_BUBBLE_HEIGHT),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    block: TranslatedBlock,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOriginal by remember(block) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .background(if (showOriginal) Color(0xFFFFF6D5) else Color.White, shape)
            .border(1.dp, Color(0x66000000), shape)
            .combinedClickable(
                onClick = { showOriginal = !showOriginal },
                onLongClick = onCopy,
            )
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = if (showOriginal) block.original else block.translation,
            style = TextStyle(
                color = Color.Black,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            ),
            autoSize = TextAutoSize.StepBased(
                minFontSize = 6.sp,
                maxFontSize = 22.sp,
                stepSize = 0.5.sp,
            ),
        )
    }
}

private const val LONG_STRIP_RATIO = 2.5f
private val MIN_BUBBLE_WIDTH = 36.dp
private val MIN_BUBBLE_HEIGHT = 20.dp
