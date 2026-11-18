# 基础统计页

实测：2026-11-18，AVD `milenote`（Android 14 / API 34）。数据全部来自
`StatsService.Graphs`（`anki.stats.GraphsRequest{search:"", days:30}`），本地不做聚合。

## 字段对应

| 页面字段 | GraphsResponse 路径 | 说明 |
|---|---|---|
| 今日复习 | `today.answer_count` | 今日答题次数 |
| 今日正确 | `today.correct_count` | 今日评分 >= 良好 的次数 |
| 今日用时 | `today.answer_millis / 60000` | 后端统计的答题毫秒数 |
| 今天到期 | `future_due.future_due[0]` | map<int32,uint32>，key 0 = 今天 |
| 明天到期 | `future_due.future_due[1]` | key 1 = 明天 |
| 日均负载 | `future_due.daily_load` | 后端算好的日均 |
| 新卡 | `card_counts.excluding_inactive.newCards` | 不含暂停/掩埋 |
| 学习中 | `card_counts.excluding_inactive.learn + relearn` | 学习 + 重学 |
| 年轻卡 / 成熟卡 | `...young` / `...mature` | |
| 已暂停 | `...suspended` | |
| 调度器 | `fsrs`（bool） | 显示 FSRS 或 SM-2 |

`Models.kt` 的 `StatsSnapshot` 与上表一一对应，页面只做展示。

## 版式

- 两页静态展示：第 1 页「今日 + 到期预测」，第 2 页「卡片构成 + 调度器」。
- 底部 `PagerBar` + 音量键翻页；没有图表、没有动画。
- 无数据时显示「还没有复习数据。先导入牌组、做完第一轮复习再来看。」（空集合实测）。
- 页脚显示「读取耗时 N ms」，直接作为验收证据。

## 实测

| 场景 | 结果 |
|---|---|
| 空集合打开统计 | 空状态文案；读取耗时 **5 ms** |
| 导入 27 张卡、复习 5 张后打开 | 今日复习 5 / 正确 5 / 今天到期 5 / 明天 0 / 日均 5；读取耗时 **6 ms** |
| 第 2 页卡片构成 | 新卡 22 · 学习中 5 · 年轻 0 · 成熟 0 · 暂停 0；调度器 SM-2 |
| 一致性 | 22 + 5 = 27 = 导入卡片数；今日 5 与复习页「本轮已评 良好 5」一致 |

自动化核对（`app/src/androidTest/java/ink/inkdeck/data/StatsTest.kt`）：

- 逐字段把 `AnkiRepository.stats()` 与直接调用 `StatsService.Graphs` 的原始返回比对（answer_count、
  correct_count、answer_millis、new/learn/young/mature/suspended、fsrs）。
- 空集合所有计数为 0。
- 读取耗时断言 < 2 秒（设备侧真实值见页脚 5–6 ms）。

## 设备与复现

- 设备：AVD `milenote`，Android 14（API 34），arm64-v8a，1080×2400。
- 构建：`INKDECK_ALIYUN=1 ./gradlew :app:assembleDebug`，debug 签名。
- 复现：导入任意 .apkg → 复习几张 → 首页「统计」→ 翻到第 2 页。
- 核对方法：`adb logcat` 无异常；页脚耗时可读；或在 instrumentation 里跑 `StatsTest`。

## 已知限制

1. 只有「当前状态」快照，没有历史曲线（复习量、保留率等图后续再做）。
2. `days` 参数目前固定 30，仅影响 future_due 窗口，未做可配置。
3. 今日用时按后端毫秒数取整到分钟，少于 1 分钟显示 0。
4. 牌组级统计（单牌组进度）未做，后续可在 DeckList 里按牌组展示。
