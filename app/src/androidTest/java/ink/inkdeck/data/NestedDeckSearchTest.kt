package ink.inkdeck.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.inkdeck.rslib.Proto
import ink.inkdeck.rslib.addMsg
import ink.inkdeck.rslib.addStr
import ink.inkdeck.rslib.int
import ink.inkdeck.rslib.num
import ink.inkdeck.rslib.nums
import ink.inkdeck.rslib.putMsg
import ink.inkdeck.rslib.putNum
import ink.inkdeck.rslib.putStr
import ink.inkdeck.rslib.sub
import ink.inkdeck.rslib.subs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 嵌套牌组（父::子）的搜索回归：之前子牌组永远查出 0 张。 */
@RunWith(AndroidJUnit4::class)
class NestedDeckSearchTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File

    @Before
    fun setUp() {
        Proto.load(context)
        workDir = File(context.cacheDir, "nested-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    fun childDeckFindsItsCards() = kotlinx.coroutines.runBlocking {
        val repo = AnkiRepository(context, File(workDir, "col"))
        try {
            val (parentName, childName) = repo.withBackend {
                val parent = callDecoding(
                    "DecksService",
                    "AddDeck",
                    Proto.builder("anki.decks.Deck")
                        .putStr("name", "英语")
                        .putMsg("normal", Proto.builder("anki.decks.Deck.Normal").build())
                        .build(),
                    "anki.collection.OpChangesWithId",
                ).num("id")
                val child = callDecoding(
                    "DecksService",
                    "AddDeck",
                    Proto.builder("anki.decks.Deck")
                        .putStr("name", "英语::Charlotte's Web")
                        .putMsg("normal", Proto.builder("anki.decks.Deck.Normal").build())
                        .build(),
                    "anki.collection.OpChangesWithId",
                ).num("id")

                val notetypeId = callDecoding(
                    "NotetypesService",
                    "GetNotetypeNames",
                    Proto.builder("anki.generic.Empty").build(),
                    "anki.notetypes.NotetypeNames",
                ).subs("entries").firstOrNull { entry ->
                    val nt = callDecoding(
                        "NotetypesService",
                        "GetNotetype",
                        Proto.builder("anki.notetypes.NotetypeId").putNum("ntid", entry.num("id")).build(),
                        "anki.notetypes.Notetype",
                    )
                    nt.subs("fields").size == 2 && nt.sub("config")?.int("kind") == 0
                }!!.num("id")

                val note = Proto.builder("anki.notes.Note")
                    .putNum("notetype_id", notetypeId)
                    .addStr("fields", "子牌组卡片")
                    .addStr("fields", "答案")
                    .build()
                callDecoding(
                    "NotesService",
                    "AddNote",
                    Proto.builder("anki.notes.AddNoteRequest").putMsg("note", note).putNum("deck_id", child).build(),
                    "anki.notes.AddNoteResponse",
                )
                parent; child
                "英语" to "英语\u001fCharlotte's Web"
            }

            val rows = repo.deckRows()
            val child = rows.firstOrNull { it.fullName == "英语::Charlotte's Web" }
            assertTrue("应能从牌组树里找到子牌组：${rows.map { it.name }}", child != null)
            assertEquals(1, child!!.totalInDeck)

            println("父牌组=$parentName 子牌组=${child.name} 全名=${child.fullName}")
            val byHuman = repo.cardIdsInDeck("英语::Charlotte's Web")
            println("按 :: 搜索得到 ${byHuman.size} 张")
            val byNative = repo.cardIdsInDeck(childName)
            println("按 \\u001f 搜索得到 ${byNative.size} 张")
            val leaf = repo.cardIdsInDeck("Charlotte's Web")
            println("按叶子名搜索得到 ${leaf.size} 张")

            val page = repo.cardsPage(child.fullName, 1, 10)
            assertEquals("子牌组应有 1 张卡片", 1, page.total)
        } finally {
            repo.close()
        }
    }
}
