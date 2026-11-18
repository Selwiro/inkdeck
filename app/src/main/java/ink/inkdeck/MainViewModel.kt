package ink.inkdeck

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.inkdeck.R
import ink.inkdeck.data.AppLocale
import ink.inkdeck.data.DeckNode
import ink.inkdeck.data.ImportResult
import ink.inkdeck.importing.ImportFailure
import ink.inkdeck.importing.StagedPackage
import ink.inkdeck.rslib.RslibException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 顶层页面。放在 ViewModel 里，转屏/改字号等配置变更不会把用户踢回首页。 */
enum class Screen { DECKS, CARDS, CARD, REVIEW, IMPORT, STATS, SETTINGS }

/** 导入页状态。 */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Copying(val copied: Long, val total: Long) : ImportUiState
    data class ConfirmDuplicate(val staged: StagedPackage) : ImportUiState
    data class Importing(val stage: String, val seconds: Int) : ImportUiState
    data class Done(val result: ImportResult, val cards: Int) : ImportUiState
    data class Failed(val message: String) : ImportUiState
}

/**
 * 界面级状态：当前页面、牌组、分页位置与导入任务。
 *
 * 之前这些都放在 `remember` 里，转屏或系统字号变化会重建 Activity，直接掉回牌组列表。
 * ViewModel 让导航与导入任务跨配置变更存活；导入协程也放在 viewModelScope，
 * 转屏不会取消复制/入库。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as InkdeckApp
    private val repository get() = app.repository
    private val importer get() = app.importer

    var screen by mutableStateOf(Screen.DECKS)
        private set

    var deck by mutableStateOf<DeckNode?>(null)
        private set

    var cardId by mutableStateOf<Long?>(null)
        private set

    /** 牌组列表页码；从学习页返回时保持。 */
    var deckPage by mutableIntStateOf(1)

    /** 当前牌组卡片列表页码；换牌组时重置。 */
    var cardPage by mutableIntStateOf(1)

    var importState by mutableStateOf<ImportUiState>(ImportUiState.Idle)
        private set

    /** 从文件管理器分享/打开进来的 uri，进入导入页后由界面消费。 */
    var pendingImportUri by mutableStateOf<Uri?>(null)
        private set

    private var importJob: Job? = null

    val importBusy: Boolean
        get() = importState is ImportUiState.Copying || importState is ImportUiState.Importing

    fun openDeck(value: DeckNode) {
        deck = value
        cardPage = 1
        screen = Screen.REVIEW
    }

    fun openCards() {
        if (deck != null) screen = Screen.CARDS
    }

    fun openReview() {
        if (deck != null) screen = Screen.REVIEW
    }

    fun openCard(value: Long) {
        cardId = value
        screen = Screen.CARD
    }

    fun back() {
        screen = when (screen) {
            Screen.CARD -> Screen.CARDS
            Screen.CARDS -> Screen.REVIEW
            Screen.REVIEW -> Screen.DECKS
            Screen.SETTINGS -> Screen.DECKS
            // 导入/复制进行中不允许用返回键离开。
            Screen.IMPORT -> if (importBusy) screen else Screen.DECKS
            Screen.STATS -> Screen.DECKS
            Screen.DECKS -> Screen.DECKS
        }
    }

    fun openDecks() {
        screen = Screen.DECKS
    }

    fun openStats() {
        screen = Screen.STATS
    }

    fun openSettings() {
        screen = Screen.SETTINGS
    }

    fun openImport(uri: Uri? = null) {
        if (uri != null) pendingImportUri = uri
        screen = Screen.IMPORT
    }

    /** 取走一次待导入 uri（界面在进入导入页后调用）。 */
    fun takePendingImportUri(): Uri? = pendingImportUri.also { pendingImportUri = null }

    fun startImport(uri: Uri) {
        if (importBusy || importState is ImportUiState.ConfirmDuplicate) return
        importState = ImportUiState.Copying(0, 0)
        importJob = viewModelScope.launch {
            try {
                val staged = importer.stage(uri) { copied, total ->
                    importState = ImportUiState.Copying(copied, total)
                }
                if (staged.alreadyImported) {
                    importState = ImportUiState.ConfirmDuplicate(staged)
                } else {
                    performImport(staged)
                }
            } catch (cancelled: CancellationException) {
                importState = ImportUiState.Idle
                throw cancelled
            } catch (error: ImportFailure) {
                importState = ImportUiState.Failed(error.message.orEmpty())
            } catch (error: Exception) {
                importState = ImportUiState.Failed(
                    AppLocale.wrap(getApplication()).getString(
                        R.string.import_err_copy_failed_detail,
                        error.message ?: error::class.simpleName.orEmpty(),
                    ),
                )
            }
        }
    }

    fun confirmDuplicateImport() {
        if (importBusy) return
        val staged = (importState as? ImportUiState.ConfirmDuplicate)?.staged ?: return
        // 先切到 Importing，避免连点两次各起一个导入协程。
        importState = ImportUiState.Importing("", 0)
        importJob = viewModelScope.launch {
            performImport(staged)
        }
    }

    fun discardDuplicateImport() {
        val staged = (importState as? ImportUiState.ConfirmDuplicate)?.staged ?: return
        importer.discard(staged)
        importState = ImportUiState.Idle
    }

    fun cancelCopy() {
        importJob?.cancel()
        importJob = null
        importState = ImportUiState.Idle
    }

    fun cancelImport() {
        // 只请求后端中断；实际的状态变化等 import() 抛 Interrupted 后统一处理。
        viewModelScope.launch { importer.cancel() }
    }

    fun resetImport() {
        importJob?.cancel()
        importJob = null
        importState = ImportUiState.Idle
    }

    private suspend fun performImport(staged: StagedPackage) {
        importState = ImportUiState.Importing("", 0)
        val ticker = viewModelScope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                seconds++
                val current = importState
                if (current is ImportUiState.Importing) {
                    importState = current.copy(seconds = seconds)
                }
            }
        }
        try {
            val result = importer.import(staged) { stage ->
                val current = importState
                if (current is ImportUiState.Importing) {
                    importState = current.copy(stage = stage)
                }
            }
            val cards = runCatching { repository.cardCount() }.getOrDefault(0)
            importState = ImportUiState.Done(result, cards)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ImportFailure) {
            importState = ImportUiState.Failed(error.message.orEmpty())
        } catch (error: RslibException) {
            importState = ImportUiState.Failed(userMessage(error))
        } catch (error: Exception) {
            importState = ImportUiState.Failed(
                AppLocale.wrap(getApplication()).getString(
                    R.string.import_err_import_failed_detail,
                    error.message ?: error::class.simpleName.orEmpty(),
                ),
            )
        } finally {
            ticker.cancel()
        }
    }

    override fun onCleared() {
        importJob?.cancel()
        super.onCleared()
    }

    private fun userMessage(error: RslibException): String {
        val context = AppLocale.wrap(getApplication())
        return when (error.kind) {
            RslibException.Kind.IO_ERROR, RslibException.Kind.DB_ERROR ->
                context.getString(R.string.import_err_corrupt_detail, error.message)

            RslibException.Kind.INVALID_INPUT ->
                context.getString(R.string.import_err_invalid_detail, error.message)

            RslibException.Kind.IMPORT_ERROR ->
                context.getString(R.string.import_err_rejected_detail, error.message)

            else -> context.getString(R.string.import_err_import_failed_detail, error.message)
        }
    }
}
