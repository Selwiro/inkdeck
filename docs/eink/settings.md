# 墨水屏显示与刷新设置

实测：2026-11-18，AVD `milenote`（Android 14 / API 34，1080×2400，420dpi）。
本文按「策略 → 所用 API → 预期效果 → 模拟器观察 → 待真机确认」组织，可直接被后续 eink 项目复用。

## 策略总表

| 策略 | 所用 API | 预期效果 | 模拟器观察 | 待真机确认（T010） |
|---|---|---|---|---|
| 字号三档 18/22/26sp | Compose `LocalInkBodySize` + `LocalInkScale`；`InkText`、标题、菜单、按钮、行高、留白统一按 body/18 缩放 | 18sp 保持原版式；26sp 下正文和所有菜单/顶栏/按钮一起放大，10 寸屏不显割裂 | 26sp 下同一张卡正文明显变大，顶栏/导航/按钮同步；牌组/卡片每屏从 6 条降为 5 条 | ✅ InkPad X：用户确认 26sp 合适；6 寸小屏待验证 |
| 默认字号按屏宽分档 | `ui/InkWidthClass.kt` 的 `defaultBodySizeSp(widthDp)`；`SettingsStore.setDeviceDefaultFontSize` 在 `MainActivity.onCreate` 首帧前写入 | `<600` / `600–839` / `840–1199` / `≥1200dp` → 18 / 22 / 26 / 26sp；用户选过后以选择为准 | 模拟器 360dp→18、720dp→22、906dp→26、1280dp→26；在 1280dp 选 22 后切到 360dp 仍为 22 | ⚠️ 真机 906dp 理论默认 26sp，待用户升级后确认 |
| 对比度三档 | 墨色常量 `Color(0xFF000000)` / `0xFF333333` / `0xFF555555`，纸色固定纯白 | 降低对比度以适配强光或低反光场景 | 三档可辨；柔和档为深灰字 | ⚠️ InkPad X 截图可辨；真机灰阶抖动网纹未专项量化 |
| 整屏刷新 | `View.invalidate()` + 全屏纯色遮罩 90ms（`Box(background)`），无厂商私有接口 | 让底层把整个窗口区域重绘一次，清残影 | 点「立即整屏刷新」无崩溃、无卡顿；模拟器刷新太快，看不出闪 | ✅ InkPad X：用户确认闪黑并清残影；screenrecord 捕获约 150ms 黑帧 |
| 分页整页重绘 | 页码是 Compose 状态，翻页整块替换内容 | 翻页时不留上一页残影 | 牌组/卡片翻页干净，无过渡动画 | 翻页是否自动触发局部刷新而非全刷 |
| 无动画/无涟漪/无 Spinner | `clickable(indication = null, interactionSource = ...)`；不使用任何 `animate*`/`AnimatedVisibility`/`ProgressIndicator` | 界面零动画 | 代码扫描无匹配（见下） | — |
| 设置持久化 | `SharedPreferences`（`inkdeck_settings`） | 重启后保持 | 26sp + 白字黑底 + 柔和，`am force-stop` 重启后仍在 | — |

## 关键实现位置

- 设置存储：`data/SettingsStore.kt`（字号、对比度；Compose 状态 + SharedPreferences 双写）
- 默认字号分档：`ui/InkWidthClass.kt`（`inkWidthClassOf(widthDp)` / `defaultBodySizeSp(widthDp)`）；
  `MainActivity.onCreate` 在 `setContent` 前调用 `SettingsStore.setDeviceDefaultFontSize`，
  保证首帧就是正确字号
- 主题与字号：`ui/InkTheme.kt`（`LocalInkBodySize`、`INK_ON_LIGHT/INK_ON_DARK` 对比度色板）
- 设置界面：`ui/SettingsScreen.kt`（字号/语言/对比度/刷新四节；选中项是 2dp 粗框 + 圆点 + 加粗，未选中是 1dp 细框）
- 整屏刷新：`ui/FullRefresh.kt`（`FullRefresher` + `FullRefreshFlash`），由 `MainActivity` 调 `invalidate()`
- 一屏条目数随字号伸缩：`DeckListScreen`/`CardListScreen` 里 `perPage = if (bodySize >= 26sp) 5 else 6`

## 代码扫描（验收第 5 条）

```sh
cd work/app
grep -rnE "animate[A-Za-z]*\(|AnimatedVisibility|Crossfade|rememberInfiniteTransition|InfiniteTransition|tween\(|spring\(" app/src/main/java --include=*.kt
grep -rnE "Ripple|ripple|CircularProgressIndicator|LinearProgressIndicator|SwipeRefresh|PullRefresh" app/src/main/java --include=*.kt
grep -rn "indication =" app/src/main/java --include=*.kt
```

2026-11-18 结果：前两条无匹配；`indication = null` 仅出现在 `InkComponents.kt` 与 `InkPager.kt`
（所有可点击元素都显式关掉涟漪）。

## 模拟器观察记录

1. 设置页在 26sp + 白字黑底 + 柔和档下所有控件仍在一屏内；顶层设置由底部导航返回，不再放整宽的「返回」按钮。
2. 牌组列表 26sp：每屏 5 个牌组，行文字实际放大；底部四个导航项（牌组/导入/统计/设置）固定贴底，不再被列表挤出屏幕。
3. 同一张 `hog` 卡在 18sp/26sp 下正文行高由 55px 变为 190px（三行），证明卡片 CSS 的 px 字号已按设置缩放。
3. 固定白底黑字；设置页选中项为粗框+圆点+加粗，不再黑底反白。
4. 多语言：UI 文案走资源，默认跟随系统；设置页可固定英文/简体中文/繁體中文，详见 [i18n.md](i18n.md)。
4. 整屏刷新按钮连点多次无崩溃；`logcat` 无 FATAL。
5. 默认字号分档（T027，2026-11-18，AVD `milenote`）：360dp→18、720dp→22、906dp→26、
   1280dp→26，设置页选中项与实际字号一致；在 1280dp 手选 22 后重启仍是 22，切到 360dp
   也不会被默认 18 覆盖。清空 `shared_prefs/inkdeck_settings.xml` 后再启动即可复现默认值。
5. `am force-stop` 重启后字号/对比度全部保持。

## 已知限制

1. **整屏刷新的效果依赖面板**：标准 API 只能强制重绘，无法真正命令 e-ink 控制器走「全刷」；
   真机上是否够用需要 T010 在 InkPad X 上确认。
2. **对比度本质是灰字**：墨水屏灰阶靠抖动，柔和档可能牺牲清晰度；若真机观感差，考虑只留纯黑一档。
3. 字号缩放牌组/卡片列表、卡片正文、统计正文，以及顶栏/导航/按钮/分页等菜单文字；18sp 系数为 1，小屏版式不变，26sp 按比例放大并按屏幕高度降行。
4. 没有「自动整屏刷新（每 N 页）」策略，只能手动点；后续可按 T010 的真机残影情况补自动策略。


## InkPad X 真机记录（2026-11-18）

- 26sp + 标准/柔和/更柔和均能切换，设置页一屏内不裁切。
- 整屏刷新：用户确认「闪黑且清残影」，screenrecord 在浅色模式下捕获约 3 帧纯黑（~150ms）。
- 2026-11-18 后续用户决定移除反色/配色，固定白底黑字；设置页选中项改为「粗框 + 圆点 + 加粗」，
  不再用黑底反白。26sp 下顶栏、导航、按钮、行高、分页区全部同步放大；18sp 小屏版式不变。
- 详细记录见 [device-inkpad-x.md](device-inkpad-x.md)。
