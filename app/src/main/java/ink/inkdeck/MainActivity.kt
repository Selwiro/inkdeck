package ink.inkdeck

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import ink.inkdeck.data.AppLocale
import ink.inkdeck.ui.CardDetailScreen
import ink.inkdeck.ui.CardListScreen
import ink.inkdeck.ui.DeckListScreen
import ink.inkdeck.ui.FullRefreshFlash
import ink.inkdeck.ui.FullRefresher
import ink.inkdeck.ui.HardwareKeys
import ink.inkdeck.ui.ImportScreen
import ink.inkdeck.ui.InkNavBar
import ink.inkdeck.ui.InkTab
import ink.inkdeck.ui.InkTheme
import ink.inkdeck.ui.ReviewScreen
import ink.inkdeck.ui.SettingsScreen
import ink.inkdeck.ui.StatsScreen
import ink.inkdeck.ui.defaultBodySizeSp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as InkdeckApp
        // 默认字号按窗口宽度分档（<600/600–839/840–1199/≥1200dp → 18/22/26/26sp）。
        // 必须在首帧前定好：先按 18sp 画一帧再跳到大字号，墨水屏会多闪一次整屏。
        app.settings.setDeviceDefaultFontSize(
            defaultBodySizeSp(resources.configuration.screenWidthDp),
        )
        // 自己处理系统栏 inset：不同设备/系统版本对状态栏的处理不一致，
        // 统一把内容放进 safeDrawing，避免标题钻到状态栏下面。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 只在冷启动时消费启动 intent；转屏重建 Activity 时不能把旧的
        // ACTION_VIEW/SEND 再导入一遍（否则每次旋转都会跳回导入页）。
        if (savedInstanceState == null) handleImportIntent(intent)

        setContent {
            val settings = app.settings
            val refresher = remember { FullRefresher() }
            val rootView = LocalView.current
            val screen = viewModel.screen
            val deck = viewModel.deck
            val cardId = viewModel.cardId

            // 固定白底黑字：系统栏只保持纸色/深色图标。
            LaunchedEffect(Unit) {
                applySystemBars()
            }

            BackHandler(enabled = screen != Screen.DECKS) {
                viewModel.back()
            }

            // 顶层页显示底部导航；学习/卡片页不显示；导入进行中暂时隐藏，避免切走丢任务。
            val showNav = when (screen) {
                Screen.DECKS, Screen.STATS -> true
                Screen.IMPORT -> !viewModel.importBusy
                Screen.SETTINGS -> true
                else -> false
            }

            InkTheme(
                bodySize = settings.fontSizeSp,
                contrast = settings.contrast,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (screen) {
                            Screen.DECKS -> DeckListScreen(
                                repository = app.repository,
                                page = viewModel.deckPage,
                                onPageChange = { viewModel.deckPage = it },
                                onOpenDeck = viewModel::openDeck,
                                onImport = { viewModel.openImport() },
                            )

                            Screen.CARDS -> {
                                if (deck == null) {
                                    LaunchedEffect(Unit) { viewModel.openDecks() }
                                } else {
                                    CardListScreen(
                                        repository = app.repository,
                                        deck = deck,
                                        page = viewModel.cardPage,
                                        onPageChange = { viewModel.cardPage = it },
                                        onBack = viewModel::openReview,
                                        onOpenCard = { viewModel.openCard(it.cardId) },
                                    )
                                }
                            }

                            Screen.CARD -> {
                                if (cardId == null || deck == null) {
                                    LaunchedEffect(Unit) { viewModel.openCards() }
                                } else {
                                    CardDetailScreen(
                                        repository = app.repository,
                                        cardId = cardId,
                                        deckName = deck.leafName,
                                        onBack = viewModel::openCards,
                                    )
                                }
                            }

                            Screen.REVIEW -> {
                                if (deck == null) {
                                    LaunchedEffect(Unit) { viewModel.openDecks() }
                                } else {
                                    ReviewScreen(
                                        repository = app.repository,
                                        sessionStore = app.reviewSession,
                                        deck = deck,
                                        onExit = viewModel::openDecks,
                                        onBrowseCards = viewModel::openCards,
                                    )
                                }
                            }

                            Screen.IMPORT -> ImportScreen(viewModel = viewModel)

                            Screen.STATS -> StatsScreen(repository = app.repository)

                            Screen.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onLanguageChange = {
                                    // 让后端 Fluent 文案也切到新语言，再重建 Activity。
                                    app.repository.restartBackend()
                                    recreate()
                                },
                                onFullRefresh = {
                                    refresher.refresh()
                                    // 标准 API：让整棵视图树失效并重绘全部像素（不碰厂商接口）。
                                    rootView.invalidate()
                                },
                                onBack = null,
                            )
                        }
                    }

                    if (showNav) {
                        InkNavBar(
                            current = when (screen) {
                                Screen.IMPORT -> InkTab.IMPORT
                                Screen.STATS -> InkTab.STATS
                                Screen.SETTINGS -> InkTab.SETTINGS
                                else -> InkTab.DECKS
                            },
                            onSelect = { tab ->
                                when (tab) {
                                    InkTab.DECKS -> viewModel.openDecks()
                                    InkTab.IMPORT -> viewModel.openImport()
                                    InkTab.STATS -> viewModel.openStats()
                                    InkTab.SETTINGS -> viewModel.openSettings()
                                }
                            },
                        )
                    }
                }

                FullRefreshFlash(
                    generation = refresher.generation,
                    flashColor = Color.Black,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    /** 接收文件管理器「分享/打开」进来的 .apkg，直接落到导入页并开始复制。 */
    private fun handleImportIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return
        @Suppress("DEPRECATION")
        val extra = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        // 常见文件管理器用 ClipData 传 uri 并附带读权限；优先 content uri。
        val clip = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val uri = intent.data ?: clip ?: extra
        if (uri != null) viewModel.openImport(uri)
    }

    private fun applySystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.White.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.White.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 去掉系统给三键导航加的灰色对比底，保持纯纸色/纯墨色。
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (HardwareKeys.hasHandler(keyCode)) {
                // 长按产生的重复事件只消费、不执行：否则按住音量键会连续评分。
                if ((event?.repeatCount ?: 0) > 0) return true
                HardwareKeys.handle(keyCode)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val TAG = "Inkdeck"
    }
}
