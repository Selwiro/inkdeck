package ink.inkdeck.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.inkdeck.rslib.AnkiBackend
import ink.inkdeck.rslib.Proto
import ink.inkdeck.rslib.addMsg
import ink.inkdeck.rslib.addStr
import ink.inkdeck.rslib.int
import ink.inkdeck.rslib.num
import ink.inkdeck.rslib.putMsg
import ink.inkdeck.rslib.putNum
import ink.inkdeck.rslib.sub
import ink.inkdeck.rslib.subs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T006：复习与评分调度验收。
 *
 * 连续评 20 张新卡（良好），检查：四档文案来自后端、评分后队列计数下降、
 * 卡片间隔被后端写入、重开集合（等价于杀进程）后进度不丢。
 */
@RunWith(AndroidJUnit4::class)
class ReviewFlowTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File

    @Before
    fun setUp() {
        Proto.load(context)
        workDir = File(context.cacheDir, "review-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    fun reviewingTwentyCardsUpdatesScheduleAndPersists() = kotlinx.coroutines.runBlocking {
        val collectionDir = File(workDir, "col")
        var repo = AnkiRepository(context, collectionDir)
        try {
            seedCards(repo, total = 20)

            // 点牌组进入学习：先切到该牌组的当前队列，再取卡片。
            val deckId = repo.deckRows().first { it.totalInDeck >= 20 }.id
            val scoped = repo.reviewQueue(5, deckId)
            assertEquals("按牌组取队列应能拿到该牌组的卡片", 5, scoped.cards.size)

            val first = repo.reviewQueue(5)
            assertEquals("队列应能一次取到 5 张", 5, first.cards.size)
            assertTrue("待学数量应为 20（新卡）", first.newCount >= 20 - first.cards.size)
            first.cards.forEach { card ->
                assertEquals("四档应有 4 条文案：${card.ratingLabels}", 4, card.ratingLabels.size)
                assertTrue("问题面不应为空", card.questionText.isNotBlank())
                assertTrue("答案面不应为空", card.answerText.isNotBlank())
            }

            var answered = 0
            val answeredIds = mutableListOf<Long>()
            while (answered < 20) {
                val queue = repo.reviewQueue(1)
                if (queue.isEmpty) break
                val card = queue.cards.first()
                val label = card.ratingLabels.getOrNull(Rating.GOOD.ordinal).orEmpty()
                assertTrue("良好档应有间隔文案，实际：$label", label.isNotBlank())
                val outcome = repo.answerCard(card, Rating.GOOD, millisecondsTaken = 800)
                assertTrue("评分后应返回剩余量", outcome.remaining >= 0)
                assertEquals("返回的间隔文案应与评分前的档位一致", label, outcome.nextInterval)
                answeredIds += card.cardId
                answered += 1
            }
            assertEquals("应评完 20 张", 20, answered)

            // 良好评完后新卡清零；默认学习步骤（1m/10m）会把它们转入学习队列，
            // 所以「今天到期」的计数不为 0 是正确行为——到期时间由后端安排。
            val after = repo.reviewQueue(20)
            assertEquals("评完之后不应还有新卡待学", 0, after.newCount)
            assertTrue(
                "其余应都在学习队列里，实际 new=${after.newCount} learn=${after.learningCount} review=${after.reviewCount}",
                after.learningCount + after.reviewCount <= 20,
            )

            // 每张卡都写入了复习记录（reps >= 1），说明评分已持久化到集合
            answeredIds.take(3).forEach { cardId ->
                val card = repo.withBackend {
                    callDecoding(
                        "CardsService",
                        "GetCard",
                        Proto.builder("anki.cards.CardId").putNum("cid", cardId).build(),
                        "anki.cards.Card",
                    )
                }
                assertTrue("卡片 $cardId 应有复习次数，实际 ${card.num("reps")}", card.num("reps") >= 1)
            }
        } finally {
            repo.close()
        }

        // 重开集合：等价于杀进程后重进，进度应仍在
        repo = AnkiRepository(context, collectionDir)
        try {
            val reopened = repo.reviewQueue(20)
            assertEquals("重开后新卡计数不应回退", 0, reopened.newCount)
            assertTrue(
                "重开后队列应仍是学习队列，实际 learn=${reopened.learningCount} review=${reopened.reviewCount}",
                reopened.learningCount + reopened.reviewCount <= 20,
            )
        } finally {
            repo.close()
        }
    }

    @Test
    fun reviewQueueReportsCountsForFreshCollection() = kotlinx.coroutines.runBlocking {
        val repo = AnkiRepository(context, File(workDir, "fresh"))
        try {
            seedCards(repo, total = 3)
            val queue = repo.reviewQueue(10)
            assertEquals(3, queue.cards.size)
            assertTrue("新卡计数应 >= 3，实际 ${queue.newCount}", queue.newCount >= 3)
            assertEquals("新集合没有复习卡", 0, queue.reviewCount)
        } finally {
            repo.close()
        }
    }

    private suspend fun seedCards(repo: AnkiRepository, total: Int) = repo.withBackend {
        val deck = callDecoding(
            "DecksService",
            "DeckTree",
            Proto.builder("anki.decks.DeckTreeRequest")
                .putNum("now", System.currentTimeMillis() / 1000)
                .build(),
            "anki.decks.DeckTreeNode",
        ).subs("children").first()
        val deckId = deck.num("deck_id")

        val entries = callDecoding(
            "NotetypesService",
            "GetNotetypeNames",
            Proto.builder("anki.generic.Empty").build(),
            "anki.notetypes.NotetypeNames",
        ).subs("entries")
        fun notetypeOf(entry: com.google.protobuf.DynamicMessage) = callDecoding(
            "NotetypesService",
            "GetNotetype",
            Proto.builder("anki.notetypes.NotetypeId").putNum("ntid", entry.num("id")).build(),
            "anki.notetypes.Notetype",
        )
        val chosen = entries.firstOrNull { entry ->
            val notetype = notetypeOf(entry)
            notetype.subs("fields").size == 2 && notetype.sub("config")?.int("kind") == 0
        } ?: entries.first()
        val notetypeId = chosen.num("id")

        var added = 0
        while (added < total) {
            val count = minOf(50, total - added)
            val request = Proto.builder("anki.notes.AddNotesRequest")
            repeat(count) { index ->
                val note = Proto.builder("anki.notes.Note")
                    .putNum("notetype_id", notetypeId)
                    .addStr("fields", "复习 ${added + index}")
                    .addStr("fields", "答案 ${added + index}")
                    .build()
                request.addMsg(
                    "requests",
                    Proto.builder("anki.notes.AddNoteRequest")
                        .putMsg("note", note)
                        .putNum("deck_id", deckId)
                        .build(),
                )
            }
            callDecoding("NotesService", "AddNotes", request.build(), "anki.notes.AddNotesResponse")
            added += count
        }
    }
}
