package ink.inkdeck.data

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * 复习会话的本地记录：本轮四档计数。
 *
 * 「恢复到哪张卡」不需要存——评分立即写库，后端队列天然接着上次的位置；
 * 这里只记小结计数，退出再进来还能看到本轮累计。
 */
class ReviewSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("review_session", Context.MODE_PRIVATE)

    fun summary(): ReviewSummary = ReviewSummary(
        again = prefs.getInt(KEY_AGAIN, 0),
        hard = prefs.getInt(KEY_HARD, 0),
        good = prefs.getInt(KEY_GOOD, 0),
        easy = prefs.getInt(KEY_EASY, 0),
    )

    /**
     * 进入某个牌组的学习轮次：牌组变了就清零，同一牌组重进保留本轮累计。
     *
     * 不加这一层的话，从 A 牌组学一半切到 B，B 的小结会带上 A 的次数。
     */
    fun begin(deckFullName: String): ReviewSummary {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DECK, null) != deckFullName ||
            prefs.getString(KEY_DATE, null) != today
        ) {
            // 牌组换了或跨天：上一轮的统计不能带到今天。
            prefs.edit {
                clear()
                putString(KEY_DECK, deckFullName)
                putString(KEY_DATE, today)
            }
        }
        return summary()
    }

    fun record(rating: Rating): ReviewSummary {
        val next = when (rating) {
            Rating.AGAIN -> summary().copy(again = summary().again + 1)
            Rating.HARD -> summary().copy(hard = summary().hard + 1)
            Rating.GOOD -> summary().copy(good = summary().good + 1)
            Rating.EASY -> summary().copy(easy = summary().easy + 1)
        }
        save(next)
        return next
    }

    fun clear() = prefs.edit { clear() }

    private fun save(summary: ReviewSummary) = prefs.edit {
        putInt(KEY_AGAIN, summary.again)
        putInt(KEY_HARD, summary.hard)
        putInt(KEY_GOOD, summary.good)
        putInt(KEY_EASY, summary.easy)
        putString(KEY_DATE, LocalDate.now().toString())
    }

    private companion object {
        const val KEY_DECK = "deck"
        const val KEY_DATE = "date"
        const val KEY_AGAIN = "again"
        const val KEY_HARD = "hard"
        const val KEY_GOOD = "good"
        const val KEY_EASY = "easy"
    }
}
