# 复习流程与评分调度

实测：2026-11-18，AVD `milenote`（Android 14 / API 34）。调度全部来自 rslib，
Kotlin 侧不含任何间隔/到期算法。

## 与后端的字段对应

| 界面元素 | 来源 |
|---|---|
| 题干/答案 HTML | `CardRenderingService.RenderExistingCard{card_id}` → `question_nodes` / `answer_nodes` |
| 四档按钮文案（如「良好 10 分」） | `SchedulerService.DescribeNextStates(SchedulingStates)` → `StringList[again, hard, good, easy]` |
| 「剩余 新/学/复」计数 | `GetQueuedCards{fetch_limit}.new_count / learning_count / review_count` |
| 下一张卡 | `GetQueuedCards{fetch_limit=1}.cards[0]`，`queue` 字段是 `NEW/LEARNING/REVIEW` |
| 评分 | `AnswerCard(CardAnswer)`：`card_id`、`current_state = states.current`、`new_state = states.<档位>`、`rating = AGAIN/HARD/GOOD/EASY`、`answered_at_millis`、`milliseconds_taken` |
| 下次间隔 | 评分前该档的文案（`DescribeNextStates` 的第 N 项），评分后原样显示 |
| 小结 | 本地 `ReviewSessionStore` 的四档计数；到期的真实状态以后端队列为准 |

评分时**原样回传后端给的 `SchedulingState`**，不重新计算：`states.again/hard/good/easy`
就是「如果按这一档，卡片会变成什么状态」，客户端把它选中的那个放进 `new_state` 即可。

## 硬件键

| 键 | 未翻面 | 已翻面 |
|---|---|---|
| 音量下 | 翻面（显示答案） | 良好（GOOD） |
| 音量上 | 无 | 重来（AGAIN） |

界面上有一行提示「音量下 = 良好，音量上 = 重来；四档都可在屏幕上点」，
其余两档（困难/容易）用屏幕按钮，四档都显示后端给的间隔文案。

## 会话恢复

- **恢复到哪张卡**：不需要本地保存——每次评分立即落库，后端 `GetQueuedCards`
  自然从下一张到期卡开始。所以「退出再进来」= 接着上次的位置。
- **本轮小结**：`ReviewSessionStore`（SharedPreferences）持久化四档计数，
  退出再进仍能看到本轮累计；换牌组或跨天自动清零，结束页返回牌组不清统计。

## 模拟器实测

| 操作 | 结果 |
|---|---|
| 新集合 20 张卡，连续按「良好」 | 队列从 `新 20` 逐张递减，评完为 `新 0 · 学 20 · 复 0` |
| 用音量下翻面 → 音量下评分 | 第一次按翻面、第二次按评分，均生效 |
| `am force-stop` 后重进复习 | `新 18`、`本轮已评 良好 2` 都保持（评了两张后重启验证） |
| 把学习卡按「容易」清完 | 进入结束页：「这一轮结束了。共 40 张：重来 0 · 困难 0 · 良好 29 · 容易 11」 |

**一个容易误判的点**：默认学习步骤是 `1m 10m`，新卡按「良好」后进入学习队列（10 分钟后再到期），
所以「新卡清零」不等于「今天没卡了」——`学 20` 是正确状态，不是 bug。自动化用例一开始
断言「剩余为 0」就栽在这里，已改成断言「新卡为 0、其余落在学习/复习队列」。

## 自动化用例

`app/src/androidTest/java/ink/inkdeck/data/ReviewFlowTest.kt`：

1. 连续评 20 张新卡（良好），断言：四档文案各有 4 条、问题/答案非空、
   每次评分返回的间隔文案与按钮文案一致、评完后 `newCount == 0`、卡片 `reps >= 1`。
2. 关闭集合并重开（等价于杀进程），断言新卡计数不回退、队列状态不变。
3. 新集合计数用例：3 张新卡 → 队列 3 张、`reviewCount == 0`。

## 已知限制

1. 只做「全集合复习」，没有按牌组/按标签的过滤复习。
2. 没有撤销（`Undo`）与「稍后再说」（bury/suspend）入口。
3. `milliseconds_taken` 用「从显示到评分」的毫秒数，未区分翻面前后的时间。
4. 学习卡提前学（study ahead）没有开关，队列给什么就显示什么。
