package ink.inkdeck.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.ImportUiState
import ink.inkdeck.MainViewModel

/**
 * .apkg 导入页。
 *
 * 流程：选择文件 / 文件管理器分享 → 复制到私有目录（字节进度）→ 重复包确认 → 导入
 * （后端阶段文本）→ 结果。
 *
 * 复制和导入由 [MainViewModel] 持有：转屏、改系统字号不会取消任务或丢掉确认页。
 */
@Composable
fun ImportScreen(viewModel: MainViewModel) {
    val state = viewModel.importState
    val pendingUri = viewModel.pendingImportUri

    // 分享/打开进来的文件：等当前任务/重复确认结束再消费，避免把 uri 丢掉。
    val canStage = !viewModel.importBusy &&
        viewModel.importState !is ImportUiState.ConfirmDuplicate
    LaunchedEffect(pendingUri, canStage) {
        if (pendingUri != null && canStage) {
            viewModel.takePendingImportUri()
            viewModel.startImport(pendingUri)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.startImport(uri)
    }

    InkPageScaffold(title = stringResource(R.string.import_title)) {
        Spacer(Modifier.height(inkDp(16.dp)))
        when (state) {
            ImportUiState.Idle -> IdleContent()
            is ImportUiState.Copying -> CopyingContent(state)
            is ImportUiState.ConfirmDuplicate -> DuplicateContent(state)
            is ImportUiState.Importing -> ImportingContent(state)
            is ImportUiState.Done -> DoneContent(state)
            is ImportUiState.Failed -> FailedContent(state)
        }

        Spacer(Modifier.weight(1f))

        when (state) {
            ImportUiState.Idle -> InkPrimaryButton(
                label = stringResource(R.string.import_choose_file),
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )

            is ImportUiState.Copying -> InkOutlineButton(
                label = stringResource(R.string.import_cancel_copy),
                onClick = viewModel::cancelCopy,
                modifier = Modifier.fillMaxWidth(),
            )

            is ImportUiState.ConfirmDuplicate -> Column {
                InkPrimaryButton(
                    label = stringResource(R.string.import_again),
                    onClick = viewModel::confirmDuplicateImport,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(inkDp(10.dp)))
                InkOutlineButton(
                    label = stringResource(R.string.import_discard_package),
                    onClick = viewModel::discardDuplicateImport,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ImportUiState.Importing -> InkOutlineButton(
                label = stringResource(R.string.import_cancel_import),
                onClick = viewModel::cancelImport,
                modifier = Modifier.fillMaxWidth(),
            )

            is ImportUiState.Done -> InkPrimaryButton(
                label = stringResource(R.string.import_select_again),
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )

            is ImportUiState.Failed -> Column {
                InkPrimaryButton(
                    label = stringResource(R.string.import_reselect),
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(inkDp(10.dp)))
                InkOutlineButton(
                    label = stringResource(R.string.import_discard),
                    onClick = viewModel::resetImport,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(inkDp(16.dp)))
    }
}

@Composable
private fun IdleContent() {
    InkText(text = stringResource(R.string.import_idle_line1), size = 18.sp)
    Spacer(Modifier.height(inkDp(8.dp)))
    InkText(text = stringResource(R.string.import_idle_line2), size = 14.sp)
    Spacer(Modifier.height(inkDp(20.dp)))
    InkSectionTitle(stringResource(R.string.import_notes_title))
    Spacer(Modifier.height(2.dp))
    InkText(text = stringResource(R.string.import_idle_note), size = 14.sp)
}

@Composable
private fun CopyingContent(state: ImportUiState.Copying) {
    InkSectionTitle(stringResource(R.string.import_copying))
    Spacer(Modifier.height(inkDp(8.dp)))
    InkText(text = stringResource(R.string.import_copying_hint), size = 18.sp)
    Spacer(Modifier.height(inkDp(6.dp)))
    InkText(
        text = if (state.total > 0) {
            stringResource(
                R.string.import_copied_progress,
                state.copied.asSize(),
                state.total.asSize(),
            )
        } else {
            stringResource(R.string.import_copied, state.copied.asSize())
        },
        size = 16.sp,
    )
}

@Composable
private fun DuplicateContent(state: ImportUiState.ConfirmDuplicate) {
    InkSectionTitle(stringResource(R.string.import_duplicate_title))
    Spacer(Modifier.height(inkDp(8.dp)))
    InkText(text = state.staged.displayName, size = 16.sp)
    Spacer(Modifier.height(inkDp(6.dp)))
    InkText(text = stringResource(R.string.import_duplicate_hint), size = 14.sp)
}

@Composable
private fun ImportingContent(state: ImportUiState.Importing) {
    InkSectionTitle(stringResource(R.string.import_importing))
    Spacer(Modifier.height(inkDp(8.dp)))
    InkText(text = stringResource(R.string.import_importing_hint), size = 18.sp)
    Spacer(Modifier.height(inkDp(6.dp)))
    InkText(
        text = state.stage.ifEmpty { stringResource(R.string.import_stage_waiting) },
        size = 15.sp,
    )
    Spacer(Modifier.height(inkDp(4.dp)))
    InkText(text = stringResource(R.string.import_elapsed, state.seconds), size = 14.sp)
}

@Composable
private fun DoneContent(state: ImportUiState.Done) {
    val result = state.result
    InkSectionTitle(stringResource(R.string.import_done))
    Spacer(Modifier.height(inkDp(8.dp)))
    if (result.changedNotes == 0) {
        InkText(
            text = stringResource(R.string.import_done_no_change),
            size = 18.sp,
            weight = FontWeight.Medium,
        )
        Spacer(Modifier.height(inkDp(6.dp)))
        InkText(
            text = if (result.removedEmptyDecks > 0) {
                stringResource(
                    R.string.import_done_skip_cleaned,
                    result.removedEmptyDecks,
                )
            } else {
                stringResource(R.string.import_done_skip)
            },
            size = 14.sp,
        )
    } else {
        InkText(
            text = stringResource(
                R.string.import_done_changed,
                result.newNotes,
                result.updatedNotes,
            ),
            size = 18.sp,
            weight = FontWeight.Medium,
        )
    }
    Spacer(Modifier.height(12.dp))
    InkKeyValueRow(
        label = stringResource(R.string.import_duplicate_skipped),
        value = stringResource(R.string.import_count_notes, result.duplicateNotes),
    )
    InkKeyValueRow(
        label = stringResource(R.string.import_conflicts),
        value = stringResource(R.string.import_count_notes, result.conflictingNotes),
    )
    InkKeyValueRow(
        label = stringResource(R.string.import_media),
        value = stringResource(R.string.import_count_files, result.mediaFiles),
    )
    if (result.removedEmptyDecks > 0) {
        InkKeyValueRow(
            label = stringResource(R.string.import_removed_decks),
            value = stringResource(R.string.import_count_files, result.removedEmptyDecks),
        )
    }
    InkKeyValueRow(
        label = stringResource(R.string.import_collection_cards),
        value = stringResource(R.string.import_count_cards, state.cards),
    )
}

@Composable
private fun FailedContent(state: ImportUiState.Failed) {
    InkSectionTitle(stringResource(R.string.import_failed))
    Spacer(Modifier.height(inkDp(8.dp)))
    InkText(text = state.message, size = 16.sp)
    Spacer(Modifier.height(inkDp(8.dp)))
    InkText(text = stringResource(R.string.import_failed_hint), size = 14.sp)
}

private fun Long.asSize(): String = when {
    this >= 1024L * 1024 * 1024 -> "%.1f GB".format(this / 1024.0 / 1024 / 1024)
    this >= 1024L * 1024 -> "%.1f MB".format(this / 1024.0 / 1024)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
