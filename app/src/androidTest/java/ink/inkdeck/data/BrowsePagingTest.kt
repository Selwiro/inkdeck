package ink.inkdeck.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.inkdeck.rslib.AnkiBackend
import ink.inkdeck.rslib.Proto
import ink.inkdeck.rslib.addMsg
import ink.inkdeck.rslib.addStr
import ink.inkdeck.rslib.int
import ink.inkdeck.rslib.num
import ink.inkdeck.rslib.putBool
import ink.inkdeck.rslib.putBytes
import ink.inkdeck.rslib.putMsg
import ink.inkdeck.rslib.putNum
import ink.inkdeck.rslib.putStr
import ink.inkdeck.rslib.str
import ink.inkdeck.rslib.sub
import ink.inkdeck.rslib.subs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T005：分页浏览的数据侧验收。
 *
 * 520 张卡片的牌组：第 1 页 6 张、最后一页 4 张、总数与牌组统计一致；
 * 每页只取 6 张卡片的首字段，不把整副牌读进内存（内存平稳靠这一点保证）。
 */
@RunWith(AndroidJUnit4::class)
class BrowsePagingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File

    @Before
    fun setUp() {
        Proto.load(context)
        workDir = File(context.cacheDir, "browse-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    fun pagesThroughFiveHundredCards() = kotlinx.coroutines.runBlocking {
        val repo = AnkiRepository(context, File(workDir, "col"))
        try {
            val seeded = seed(repo, total = 520)
            val deckName = seeded.deckName

            val first = repo.cardsPage(deckName, page = 1, pageSize = 6)
            assertEquals("第一页应有 6 张", 6, first.cards.size)
            assertEquals("总数应与写入的卡片数一致", 520, first.total)
            assertEquals("页数应为 ceil(520/6)", 87, first.pageCount)
            assertTrue("第一页标题（笔记首字段）不应为空", first.cards.all { it.title.isNotBlank() })

            val middle = repo.cardsPage(deckName, page = 40, pageSize = 6)
            assertEquals(40, middle.page)
            assertEquals(6, middle.cards.size)

            val last = repo.cardsPage(deckName, page = 87, pageSize = 6)
            assertEquals("最后一页应有 520-86*6=4 张", 4, last.cards.size)

            val clamped = repo.cardsPage(deckName, page = 999, pageSize = 6)
            assertEquals("越界页码应夹到最后一页", 87, clamped.page)

            val rendered = repo.renderCard(first.cards.first().cardId)
            assertTrue("渲染出的问题面不应为空", rendered.questionText.isNotBlank())
            assertTrue("渲染出的答案面不应为空", rendered.answerText.isNotBlank())

            val deckRow = repo.deckRows().first { it.id == seeded.deckId }
            assertEquals("牌组统计的卡片数应与分页总数一致", 520, deckRow.totalInDeck)
        } finally {
            repo.close()
        }
    }

    /**
     * 造一个可直接用 SAF 导入的演示包（1 张带图、1 张带音频），
     * 写到 cache 供 adb pull 后手工走 UI 验证渲染。
     */
    @Test
    fun writesDemoPackageForUiCheck() = kotlinx.coroutines.runBlocking {
        val sourceDir = File(workDir, "demo-source").apply { mkdirs() }
        val outFile = File(context.cacheDir, "inkdeck-demo.apkg")
        val backend = AnkiBackend(sourceDir)
        backend.use {
            backend.open()
            backend.openCollection()
            val deckId = backend.callDecoding(
                "DecksService",
                "DeckTree",
                Proto.builder("anki.decks.DeckTreeRequest")
                    .putNum("now", System.currentTimeMillis() / 1000)
                    .build(),
                "anki.decks.DeckTreeNode",
            ).subs("children").first().num("deck_id")
            val notetypeId = backend.twoFieldNotetypeId()

            val imageName = backend.callDecoding(
                "MediaService",
                "AddMediaFile",
                Proto.builder("anki.media.AddMediaFileRequest")
                    .putStr("desired_name", "inkdeck-demo.png")
                    .putBytes("data", visiblePng())
                    .build(),
                "anki.generic.String",
            ).str("val")
            val soundName = backend.callDecoding(
                "MediaService",
                "AddMediaFile",
                Proto.builder("anki.media.AddMediaFileRequest")
                    .putStr("desired_name", "inkdeck-demo.wav")
                    .putBytes("data", DEMO_WAV)
                    .build(),
                "anki.generic.String",
            ).str("val")

            // 再造 7 个牌组，各放 1 张卡：牌组列表因此有两页可翻，且牌组计数不为 0。
            val extraDecks = (1..7).map { index ->
                val created = backend.callDecoding(
                    "DecksService",
                    "AddDeck",
                    Proto.builder("anki.decks.Deck")
                        .putStr("name", "演示牌组 $index")
                        .putMsg("normal", Proto.builder("anki.decks.Deck.Normal").build())
                        .build(),
                    "anki.collection.OpChangesWithId",
                )
                created.num("id")
            }
            extraDecks.forEachIndexed { index, id ->
                val note = Proto.builder("anki.notes.Note")
                    .putNum("notetype_id", notetypeId)
                    .addStr("fields", "牌组 $index 的第一张卡")
                    .addStr("fields", "答案 $index")
                    .build()
                backend.callDecoding(
                    "NotesService",
                    "AddNote",
                    Proto.builder("anki.notes.AddNoteRequest")
                        .putMsg("note", note)
                        .putNum("deck_id", id)
                        .build(),
                    "anki.notes.AddNoteResponse",
                )
            }

            val fronts = buildList {
                add("带图片的卡片 <img src=\"$imageName\">")
                add("带音频的卡片 [sound:$soundName]")
                // 结构化排版样例：div 分行、粗体字号、带边框的例句列表、引用块。
                add(
                    """
                    <div style="font-weight:bold">English::Charlotte's Web</div>
                    <div><b style="font-size:24px">hog</b> /hɒɡ/ ★☆☆☆☆</div>
                    <div>N-COUNT A hog is a pig. In British English, hog usually refers to a large
                    male pig that has been castrated, but in American English it can refer to any kind of pig.</div>
                    <ul style="border:1px solid #000000;padding:8px">
                      <li>We picked the corn by hand and we fed it to the hogs and the cows…</li>
                      <li>Johnny Warrick has a hog farm in Alabama.</li>
                    </ul>
                    <blockquote>"Out to the hog house," replied Mrs.</blockquote>
                    """.trimIndent(),
                )
                repeat(17) { add("普通卡片 ${it + 1}") }
            }
            fronts.forEachIndexed { index, front ->
                val note = Proto.builder("anki.notes.Note")
                    .putNum("notetype_id", notetypeId)
                    .addStr("fields", front)
                    .addStr("fields", "答案 ${index + 1}")
                    .build()
                backend.callDecoding(
                    "NotesService",
                    "AddNote",
                    Proto.builder("anki.notes.AddNoteRequest")
                        .putMsg("note", note)
                        .putNum("deck_id", deckId)
                        .build(),
                    "anki.notes.AddNoteResponse",
                )
            }

            backend.callDecoding(
                "ImportExportService",
                "ExportAnkiPackage",
                Proto.builder("anki.import_export.ExportAnkiPackageRequest")
                    .putStr("out_path", outFile.absolutePath)
                    .putMsg(
                        "options",
                        Proto.builder("anki.import_export.ExportAnkiPackageOptions")
                            .putBool("with_media", true)
                            .build(),
                    )
                    .putMsg(
                        "limit",
                        Proto.builder("anki.import_export.ExportLimit")
                            .putMsg("whole_collection", Proto.builder("anki.generic.Empty").build())
                            .build(),
                    )
                    .build(),
                "anki.generic.UInt32",
            )
        }
        assertTrue("演示包应生成", outFile.isFile)
        println("DEMO_PACKAGE=${outFile.absolutePath}")
    }

    // ---- helpers ----

    /** 默认模板里挑一个 2 字段的（"普通"），保证 1 条笔记 = 1 张卡片。 */
    private fun AnkiBackend.twoFieldNotetypeId(): Long {
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
        return chosen.num("id")
    }

    private data class Seeded(val deckId: Long, val deckName: String)

    private suspend fun seed(repo: AnkiRepository, total: Int): Seeded = repo.withBackend {
        val root = callDecoding(
            "DecksService",
            "DeckTree",
            Proto.builder("anki.decks.DeckTreeRequest")
                .putNum("now", System.currentTimeMillis() / 1000)
                .build(),
            "anki.decks.DeckTreeNode",
        )
        val deck = root.subs("children").first()
        val deckId = deck.num("deck_id")
        val deckName = deck.str("name")
        val notetypeId = twoFieldNotetypeId()

        var added = 0
        while (added < total) {
            val count = minOf(200, total - added)
            val request = Proto.builder("anki.notes.AddNotesRequest")
            repeat(count) { index ->
                val note = Proto.builder("anki.notes.Note")
                    .putNum("notetype_id", notetypeId)
                    .addStr("fields", "浏览测试卡片 ${added + index}")
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
        Seeded(deckId, deckName)
    }

    /** 生成一张看得见的 PNG：白底 + 黑框 + 黑色横条，墨水屏上一眼能认出来。 */
    private fun visiblePng(width: Int = 240, height: Int = 120): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRect(4f, 4f, width - 4f, height - 4f, paint)
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawRect(24f, height / 2f - 14f, width - 24f, height / 2f + 14f, paint)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private companion object {
        /** 1x1 透明 PNG（备用）。 */
        val DEMO_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        /** 0.2 秒 8kHz 单声道静音 WAV（头 44 字节 + 1600 个采样）。 */
        val DEMO_WAV: ByteArray = run {
            val sampleRate = 8000
            val samples = sampleRate / 5
            val dataSize = samples * 2
            val bytes = ByteArray(44 + dataSize)
            fun putAscii(offset: Int, value: String) {
                value.forEachIndexed { index, c -> bytes[offset + index] = c.code.toByte() }
            }
            fun putInt(offset: Int, value: Int) {
                bytes[offset] = (value and 0xFF).toByte()
                bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            putAscii(0, "RIFF")
            putInt(4, 36 + dataSize)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putInt(16, 16)
            bytes[20] = 1 // PCM
            bytes[22] = 1 // mono
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2)
            bytes[32] = 2
            bytes[34] = 16
            putAscii(36, "data")
            putInt(40, dataSize)
            bytes
        }
    }
}
