package ink.inkdeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AppLocale
import ink.inkdeck.data.SettingsStore

/**
 * 设置页：字号 / 反色 / 对比度 / 整屏刷新。
 *
 * 全部即时生效并持久化；选中档位是实心墨块，不再靠「●/○」这种小符号。
 * 顶层入口不带返回（底部导航负责切换）；从学习页/浏览页进来时带返回箭头。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onLanguageChange: () -> Unit,
    onFullRefresh: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    InkPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(inkDp(16.dp)))

        InkSectionTitle(stringResource(R.string.settings_font_size))
        Spacer(Modifier.height(inkDp(8.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsStore.FONT_SIZES.forEach { size ->
                InkChoice(
                    label = "$size",
                    selected = settings.fontSizeSp == size,
                    onClick = { settings.setFontSize(size) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(inkDp(20.dp)))
        InkSectionTitle(stringResource(R.string.settings_language))
        Spacer(Modifier.height(inkDp(8.dp)))
        val languageChoices = listOf(
            AppLocale.SYSTEM to R.string.language_system,
            AppLocale.CHINESE to R.string.language_zh,
            AppLocale.TRADITIONAL_CHINESE to R.string.language_zh_hant,
            AppLocale.ENGLISH to R.string.language_en,
        )
        Column(verticalArrangement = Arrangement.spacedBy(inkDp(8.dp))) {
            languageChoices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(inkDp(8.dp))) {
                    row.forEach { (tag, labelRes) ->
                        InkChoice(
                            label = stringResource(labelRes),
                            selected = settings.languageTag == tag,
                            onClick = {
                                if (settings.languageTag != tag) {
                                    settings.changeLanguage(tag)
                                    onLanguageChange()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(inkDp(20.dp)))
        InkSectionTitle(stringResource(R.string.settings_contrast))
        Spacer(Modifier.height(inkDp(8.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val contrastLabels = listOf(
                R.string.contrast_standard,
                R.string.contrast_soft,
                R.string.contrast_softer,
            )
            contrastLabels.forEachIndexed { index, labelRes ->
                InkChoice(
                    label = stringResource(labelRes),
                    selected = settings.contrast == index,
                    onClick = { settings.updateContrast(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(inkDp(20.dp)))
        InkSectionTitle(stringResource(R.string.settings_refresh))
        Spacer(Modifier.height(inkDp(8.dp)))
        InkOutlineButton(
            label = stringResource(R.string.settings_refresh_now),
            onClick = onFullRefresh,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
        InkText(
            text = stringResource(R.string.settings_persist_note),
            size = 14.sp,
            weight = FontWeight.Normal,
        )
        Spacer(Modifier.height(inkDp(16.dp)))
    }
}
