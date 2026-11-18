# 卡片正文渲染（HTML/CSS 子集）

更新：2026-11-18。触发原因：卡片正文此前用 `htmlToPreview()`（给列表预览用的去标签函数），
渲染出来是一整段纯文本——`<div>`、`<ul><li>`、`<br>`、加粗、边框全部丢失，和 AnkiDroid
的观感差距很大。本文记录现在的做法、边界和教训。

## 方案：自写解析 + Compose 绘制（不引 WebView）

| 文件 | 职责 |
|---|---|
| `ui/html/MiniHtml.kt` | 容错 HTML 解析成节点树；把树转成渲染块（段落 / 图片 / 分隔线 / 带样式容器） |
| `ui/html/CssParser.kt` | CSS 子集解析与选择器匹配（tag / .class / #id / 逗号分组） |
| `ui/html/HtmlContent.kt` | 渲染块 → Compose（BasicText + AnnotatedString，块级边框/背景/内边距） |
| `ui/MediaViews.kt` | 图片解码、音频播放按钮 |

为什么不用 WebView：内存和初始化成本高，动画/滚动/文字缩放都不受控，与墨水屏「零动画、
整页重绘」的约束冲突。自写渲染器的代价是 CSS 覆盖有限，但卡片模板 99% 用的是
`div/p/b/i/u/br/ul/ol/li/img/hr/style`。

## 支持的 HTML

- 块级：`html/body/div/p/section/article/header/footer/main/aside/ul/ol/li/h1-h6/blockquote/pre/table/tr/td/th/hr`
- 行内：`span/b/strong/i/em/u/ins/s/strike/del/br/img/code`
- 属性：`class`、`id`、`style`

## 支持的 CSS

| 类别 | 属性 |
|---|---|
| 行内文字 | `font-size`（px/pt/em/%/rem/命名档位）、`font-weight`、`font-style`、`text-decoration`、`color` |
| 块级 | `text-align`、`border` / `border-width` / `border-style` / `border-color`、`padding`、`margin`、`display:none`、`list-style-type` |
| 行内间距 | 行内元素的 `margin-left/right`、`padding` 会折算成一个空格（不画内边距），避免 `N-COUNTA hog` 这种粘连 |
| 选择器 | `tag`、`.class`、`#id`、`tag.class`、逗号分组；后代选择器只取最右一段匹配 |

**背景色一律不画**：卡片里所有 `background-color` / `background` 都忽略，只保留文字、边框、
分割线。墨水屏上色块会糊成灰带（`Vocabulary` 标签、`ul.sents` 的淡蓝底都验证过），
读起来比不画更差。

**明确不支持**（不假装支持）：浮动/定位/flex/grid、百分比宽度、渐变/阴影/变换、伪元素（仅用作分割线线索）、
`@media`。

## 版式与墨水屏约束

- 卡片页统一用 `ui/InkLayout.kt` 的 `InkPageScaffold`：顶栏 + **唯一的 weight 正文区** + 固定底部操作区。
  正文区占满剩余高度，内部单层纵向滚动（卡片可能超一屏）；底部不再参与高度平分。
- 卡片页不显示内部卡片编号，也不写「问题 / 答案」标题——正反面靠卡片内容本身区分，
  与 AnkiDroid 的观感一致，同时把纵向空间全部留给正文。
- 底部只有学习操作（`显示答案` / 四档评分），左右贯通、底部贴底，左右/底部不画线；
  四档评分只保留内部竖线。卡片浏览页对应 `显示答案 / 回到问题`。
  卡片列表入口在顶栏右上角，顶栏左侧是返回箭头。设置和字号只在顶层「设置」页。
- 颜色：固定白底黑字，浅色文字压到墨色（白底上看不见的白字会被纠正）；
  纯色块只用于边框/背景，不引入灰阶渐变。
- 图片作为独立块显示（最多 360dp 高，等比缩放）。
- 音频：`[sound:xxx]` 在出现的位置生成一个 **48dp 方块 + ▶** 的播放按钮，不写文件名、不占整行；
  找不到文件时显示「（缺音频：名字）」。音频块随正文一起解析，问题面/答案面各归各位，
  不再由页面层另行追加（此前是整行按钮 + 文件名，既占地方又与 AnkiDroid 不一致）。
- 字号跟随设置页的 18/22/26sp 作为基准：卡片内 `px` 按 `bodySize/18` 整体缩放
  （含字号、padding、margin、border），不会出现「设置 26sp 但模板 20px 把正文钉死」的情况。

## 分隔线（hr / ::before / border-bottom）

卡片模板里「内容之间的一条细线」有三种写法，现在都折算成同一种渲染：

| 写法 | 处理 |
|---|---|
| `<hr>` / `<hr id=answer>` | 1dp 墨色 55% 浅线 + 上下 12dp 留白（2026-11-18 从 2dp 实心调细调浅） |
| `.block::before { content:""; height:1px; background:... }` | 视为该块的**上分隔线** |
| `.block::after { ... }` | 视为**下分隔线** |
| `border-bottom` / `border-top` | 视为下/上分隔线 |

实现：`CssParser.pseudoDivider()` 判断伪元素规则里是否声明了 `height/background/border`；
`BlockStyle.dividerTop/dividerBottom` 带到渲染层，由 `DividerBlock()` 画线。

## 验证

- JVM 单测 `app/src/test/java/ink/inkdeck/ui/html/MiniHtmlTest.kt`：8 个用例（分段、行内合并、
  `<br>`、列表项目符号 + 容器边框成组、style 标签选择器、嵌套块、图片解码、HTML 实体）。
- 模拟器（AVD milenote / API 34）：结构化演示卡（`English::Charlotte's Web` 标题行、
  `hog /hɒɡ/ ★☆☆☆☆`、释义段落、带边框的例句列表、引用）逐行还原；
  复习页四档按钮改为两行等高等宽（`重来 / <1分`、`困难 / <6分`、`良好 / <10分`、`容易 / 3天`）。

## 踩到的坑

### 坑一：两个 `weight(1f)` 把卡片挤成半屏

卡片页原来在正文 `Column(weight(1f))` 之外，又放了 `Spacer(weight(1f))` 把底部按钮推到底。
两个权重子项会平分剩余高度：正文只拿到半屏，长答案看起来像「被截断，只有一半」，
实际上另外半屏是空白 Spacer。

修法：新增 `InkPageScaffold`，规定**正文是页面里唯一的权重子项**，底部区按内容高度测量。
教训：`weight` 是按比例分配剩余空间，不是「占满剩下的」；想推底就用固定底部区，不要再加一个 `weight`。

### 坑二：两个滚动区互相挤成 0 高

卡片详情页原来把「问题 + 答案」两段 `HtmlContent` 放在同一个 `Column(weight(1f))` 里，
两个都带 `verticalScroll` —— 第一个吃掉全部高度，第二个被挤成 0，于是「点了显示答案什么都没有」。
修法：翻面前只渲染问题面、翻面后只渲染答案面（与复习页一致，也只有一个滚动区）。
教训：`verticalScroll` 只能有一个可测量高度的父容器，不能并列。

### 坑三：正则里的字面 `}` 在 Android 上会崩

CSS 规则匹配用了 `([^{}]+)\{([^}]*)}`，JVM 的 `java.util.regex` 接受，但 Android 的 ICU 正则抛
`PatternSyntaxException: Syntax error near index 22`，release 包点开卡片直接闪退。
修法是把结尾的 `}` 也转义（`\}`）。教训：HTML/CSS 相关的正则不能只在 JVM 单测里跑，
必须在设备上真开一次卡片；现在的单测仍跑不到 ICU，所以这条只能靠模拟器验收兜住。

## HTML 实体

`&nbsp;` / `&amp;` / `&lt;` / `&gt;` / 数字实体在解析后的文本节点里统一解码（`MiniHtml.decodeEntities`）。
此前模板里的 `&nbsp;` 会原样显示成 5 个字符，答案里出现「pig.&nbsp; 猪」；现在显示为正常空格。

## 顺序与 TTS（2026-11-18 修复）

- 块内子节点按文档顺序处理：文字 → 子块/图片/音频 → 后续文字；不会再把块后的文字
  合并到块前。JVM 用例 `mixedTextAndBlockKeepsDocumentOrder`、`imageStaysBetweenSurroundingText`
  覆盖。
- `[anki:tts]...[/anki:tts]` 去掉标签、保留内部文字；一期不做语音合成，至少不再显示字面标签。

## 真机验证

- InkPad X（Android 8.1、1200×1600、212dpi）：26sp 下正文、列表、引用块均无裁切；
- 带图片卡显示黑框测试图（按比例、最大 360dp 高），带音频卡显示 48dp ▶ 且能出声；
- 混排/实体/TTS 的 JVM 用例在真机安装前已通过；真机未发现渲染顺序问题。
- 截图见 [device-inkpad-x.md](device-inkpad-x.md)。

## 已知限制

1. 表格按块级行渲染，不做网格对齐；合并单元格、`colspan` 忽略。
2. **所有底色都不画**（行内标签、块级浅色底一律忽略），只有边框与分割线保留。
3. 浮动/flex 布局退化为顺序排列：像 E-ink-Dict 的 `.card-header`
   （`justify-content: space-between`）会把「牌组名」和「Vocabulary/Definition」拆成两行，
   而不是左右对齐。
4. `<script>` 一律丢弃，依赖 JS 的交互（如 `toggleTrans()` 切换中英对照）不生效。
5. 自定义字体（`@font-face`）不支持，统一用系统字体。
6. 巨长卡片（>5000 字）未压测，解析在首次渲染时做一次并缓存。
