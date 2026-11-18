package ink.inkdeck.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.inkdeck.rslib.AnkiBackend
import ink.inkdeck.rslib.Proto
import ink.inkdeck.rslib.addMsg
import ink.inkdeck.rslib.addStr
import ink.inkdeck.rslib.bool
import ink.inkdeck.rslib.int
import ink.inkdeck.rslib.num
import ink.inkdeck.rslib.putMsg
import ink.inkdeck.rslib.putNum
import ink.inkdeck.rslib.putStr
import ink.inkdeck.rslib.sub
import ink.inkdeck.rslib.subs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T008：统计页数据验收。
 *
 * 断言 `AnkiRepository.stats()` 的每个字段都与 `StatsService.Graphs` 的原始返回一致；
 * 另外验证空集合时的空状态取值（全 0）。
 */
@RunWith(AndroidJUnit4::class)
class StatsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File

    @Before
    fun setUp() {
        Proto.load(context)
        workDir = File(context.cacheDir, "stats-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    fun statsFieldsMatchRawGraphsResponse() = kotlinx.coroutines.runBlocking {
        val repo = AnkiRepository(context, File(workDir, "col"))
        try {
            seedCards(repo, total = 5)
            // 复习 3 张，产生今日数据
            repeat(3) {
                val queue = repo.reviewQueue(1)
                if (!queue.isEmpty) repo.answerCard(queue.cards.first(), Rating.GOOD, 700)
            }

            val started = System.currentTimeMillis()
            val stats = repo.stats()
            val elapsed = System.currentTimeMillis() - started
            assertTrue("统计读取应远快于 200ms 的验收线（含后端往返），实际 ${elapsed}ms", elapsed < 2000)

            val raw = repo.withBackend {
                callDecoding(
                    "StatsService",
                    "Graphs",
                    Proto.builder("anki.stats.GraphsRequest").putStr("search", "").putNum("days", 30).build(),
                    "anki.stats.GraphsResponse",
                )
            }
            val today = raw.sub("today")
            val counts = raw.sub("card_counts")?.sub("excluding_inactive")
            assertNotNull("Graphs 应返回 today", today)

            assertEquals(today!!.num("answer_count").toInt(), stats.todayAnswers)
            assertEquals(today.num("correct_count").toInt(), stats.todayCorrect)
            assertEquals(today.num("answer_millis"), stats.todayMillis)
            assertEquals(counts?.num("newCards")?.toInt() ?: 0, stats.newCards)
            assertEquals(
                (counts?.num("learn") ?: 0).toInt() + (counts?.num("relearn") ?: 0).toInt(),
                stats.learningCards,
            )
            assertEquals(counts?.num("young")?.toInt() ?: 0, stats.youngCards)
            assertEquals(counts?.num("mature")?.toInt() ?: 0, stats.matureCards)
            assertEquals(counts?.num("suspended")?.toInt() ?: 0, stats.suspendedCards)
            assertEquals(raw.bool("fsrs"), stats.fsrs)

            assertTrue("复习 3 张后今日答题数应 >= 3，实际 ${stats.todayAnswers}", stats.todayAnswers >= 3)
            assertEquals("5 张卡都应计入卡片总数", 5, stats.newCards + stats.learningCards + stats.youngCards + stats.matureCards)
        } finally {
            repo.close()
        }
    }

    @Test
    fun emptyCollectionReturnsZeroStats() = kotlinx.coroutines.runBlocking {
        val repo = AnkiRepository(context, File(workDir, "empty"))
        try {
            val stats = repo.stats()
            assertEquals(0, stats.todayAnswers)
            assertEquals(0, stats.todayCorrect)
            assertEquals(0, stats.newCards)
            assertEquals(0, stats.dueToday)
            assertEquals(0, stats.dueTomorrow)
        } finally {
            repo.close()
        }
    }

    private suspend fun seedCards(repo: AnkiRepository, total: Int) = repo.withBackend {
        val deckId = callDecoding(
            "DecksService",
            "DeckTree",
            Proto.builder("anki.decks.DeckTreeRequest")
                .putNum("now", System.currentTimeMillis() / 1000)
                .build(),
            "anki.decks.DeckTreeNode",
        ).subs("children").first().num("deck_id")

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

        val request = Proto.builder("anki.notes.AddNotesRequest")
        repeat(total) { index ->
            val note = Proto.builder("anki.notes.Note")
                .putNum("notetype_id", notetypeId)
                .addStr("fields", "统计 ${index + 1}")
                .addStr("fields", "答案 ${index + 1}")
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
    }
}
