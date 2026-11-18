package ink.inkdeck.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.rslib.AnkiBackend
import ink.inkdeck.rslib.Proto
import ink.inkdeck.rslib.addMsg
import ink.inkdeck.rslib.addStr
import ink.inkdeck.rslib.int
import ink.inkdeck.rslib.num
import ink.inkdeck.rslib.nums
import ink.inkdeck.rslib.putBool
import ink.inkdeck.rslib.putBytes
import ink.inkdeck.rslib.putMsg
import ink.inkdeck.rslib.putNum
import ink.inkdeck.rslib.putStr
import ink.inkdeck.rslib.str
import ink.inkdeck.rslib.strs
import ink.inkdeck.rslib.sub
import ink.inkdeck.rslib.subs
import java.io.File
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportPackageTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File

    @Before
    fun setUp() {
        Proto.load(context)
        workDir = File(context.cacheDir, "import-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    fun importsPackageWithMediaAndMatchesSourceCounts() = kotlinx.coroutines.runBlocking {
        val fixture = buildFixture(workDir, notes = 3)

        val repo = AnkiRepository(context, File(workDir, "target"))
        try {
            val importer = newImporter(repo)
            val staged = importer.register(fixture.packageFile.copyTo(File(workDir, "copy1.apkg")))
            assertFalse("首次导入不应被判为重复", staged.alreadyImported)

            val result = importer.import(staged) { }
            assertEquals("新增笔记数应与源集合一致", 3, result.newNotes)
            assertEquals("不应有更新", 0, result.updatedNotes)

            assertEquals("3 张 Basic 笔记应产生 3 张卡片", 3, repo.cardCount())
            assertTrue("牌组树不应为空", repo.deckTree().children.isNotEmpty())

            // 媒体：文件落在 collection.media，且卡片字段引用了它
            val mediaFile = File(repo.mediaDir, fixture.mediaName)
            assertTrue("媒体文件应落到 collection.media：$mediaFile", mediaFile.isFile)
            assertEquals("媒体文件大小应与源一致", fixture.mediaSize, mediaFile.length())
            assertTrue("导入后的卡片字段应引用媒体文件", repo.noteFieldsContain(fixture.mediaName))
        } finally {
            repo.close()
        }
    }

    @Test
    fun reimportingSamePackageDoesNotDuplicateNotes() = kotlinx.coroutines.runBlocking {
        val fixture = buildFixture(workDir, notes = 2)
        val repo = AnkiRepository(context, File(workDir, "target-dup"))
        try {
            val importer = newImporter(repo)
            importer.import(importer.register(fixture.packageFile.copyTo(File(workDir, "dup1.apkg")))) { }
            assertEquals(2, repo.cardCount())

            val secondStaged = importer.register(fixture.packageFile.copyTo(File(workDir, "dup2.apkg")))
            assertTrue("同内容包应被识别为已导入", secondStaged.alreadyImported)

            val second = importer.import(secondStaged) { }
            assertEquals("重复导入不应新增笔记", 0, second.newNotes)
            assertEquals("重复导入后卡片数不变", 2, repo.cardCount())
            assertTrue(
                "重复的笔记应出现在 duplicate/first_field_match 计数里",
                second.duplicateNotes + second.firstFieldMatchNotes > 0,
            )
        } finally {
            repo.close()
        }
    }

    @Test
    fun rejectsNonPackageFile() = kotlinx.coroutines.runBlocking {
        val text = File(workDir, "not-a-package.apkg").apply { writeText("这不是 zip") }
        val repo = AnkiRepository(context, File(workDir, "target-nonpkg"))
        try {
            try {
                newImporter(repo).register(text)
                fail("非 apkg 文件应被拒绝")
            } catch (expected: ImportFailure.NotAPackage) {
                assertTrue(expected.message!!.contains("不是 Anki 牌组包"))
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun rejectsCorruptZip() = kotlinx.coroutines.runBlocking {
        val corrupt = File(workDir, "corrupt.apkg").apply {
            // 有 zip 魔数、后面是垃圾字节
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(256) { it.toByte() })
        }
        val repo = AnkiRepository(context, File(workDir, "target-corrupt"))
        try {
            try {
                newImporter(repo).register(corrupt)
                fail("损坏的 zip 应被拒绝")
            } catch (expected: ImportFailure.CorruptPackage) {
                assertTrue(expected.message!!.isNotBlank())
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun rejectsEmptyFile() = kotlinx.coroutines.runBlocking {
        val empty = File(workDir, "empty.apkg").apply { createNewFile() }
        val repo = AnkiRepository(context, File(workDir, "target-empty"))
        try {
            try {
                newImporter(repo).register(empty)
                fail("空文件应被拒绝")
            } catch (expected: ImportFailure.NotAPackage) {
                assertTrue(expected.message!!.contains("文件为空"))
            }
        } finally {
            repo.close()
        }
    }

    /**
     * 大包（>100MB）验收，默认跳过：
     *
     * adb shell am instrument -w -e large true \
     *   -e class ink.inkdeck.importing.ImportPackageTest#importsLargePackage \
     *   ink.inkdeck.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun importsLargePackage() = kotlinx.coroutines.runBlocking {
        assumeTrue(
            "默认跳过；用 -e large true 打开",
            InstrumentationRegistry.getArguments().getString("large") == "true",
        )
        val bigMedia = writeRandomFile(File(workDir, "big-media.bin"), LARGE_MEDIA_BYTES)
        val fixture = buildFixture(workDir, notes = 2, mediaFile = bigMedia)
        assertTrue("大包应超过 100MB", fixture.packageFile.length() > 100L * 1024 * 1024)

        val repo = AnkiRepository(context, File(workDir, "target-large"))
        try {
            val importer = newImporter(repo)
            val copy = fixture.packageFile.copyTo(File(workDir, "large-copy.apkg"))
            val stagedAt = System.currentTimeMillis()
            val staged = importer.register(copy)
            println("大包已复制：${copy.length() / 1024 / 1024}MB，耗时 ${System.currentTimeMillis() - stagedAt}ms")

            val importStarted = System.currentTimeMillis()
            val result = importer.import(staged) { }
            val importElapsed = System.currentTimeMillis() - importStarted

            assertEquals(2, result.newNotes)
            assertTrue(File(repo.mediaDir, fixture.mediaName).isFile)
            println("大包导入耗时 ${importElapsed}ms")
        } finally {
            repo.close()
        }
    }

    /**
     * 失败回滚：包本身是合法 zip，但集合文件内容坏掉，导入事务必须整体回滚。
     *
     * 等价于「进程被杀」时 SQLite 事务的效果：留下零张卡片，可以立即重试。
     */
    @Test
    fun failedImportRollsBackWithoutPartialCards() = kotlinx.coroutines.runBlocking {
        val fixture = buildFixture(workDir, notes = 3)
        val broken = File(workDir, "broken-collection.apkg")
        rewriteWithBrokenCollection(fixture.packageFile, broken)

        val repo = AnkiRepository(context, File(workDir, "target-broken"))
        try {
            val importer = newImporter(repo)
            val staged = importer.register(broken)
            var failure: Throwable? = null
            try {
                importer.import(staged) { }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue("损坏的集合应导入失败，实际：$failure", failure != null)
            assertEquals("失败后集合里不应留下卡片", 0, repo.cardCount())

            // 用完好包重试应成功，说明没有残留状态
            val retry = importer.import(
                importer.register(fixture.packageFile.copyTo(File(workDir, "retry.apkg"))),
            ) { }
            assertEquals("重试应导入全部笔记", 3, retry.newNotes)
        } finally {
            repo.close()
        }
    }

    // ---- fixture ----

    private data class Fixture(
        val packageFile: File,
        val mediaName: String,
        val mediaSize: Long,
    )

    private fun newImporter(repo: AnkiRepository): PackageImporter = PackageImporter(
        context = context,
        repository = repo,
        store = ImportedPackageStore(context, "test-store-${System.nanoTime()}"),
    )

    /** 用 rslib 造一个带媒体和 N 张卡片的集合，再导出成 .apkg。 */
    private fun buildFixture(
        dir: File,
        notes: Int,
        mediaBytes: ByteArray = TINY_PNG,
        mediaFile: File? = null,
    ): Fixture {
        val sourceDir = File(dir, "source").apply { mkdirs() }
        val outFile = File(dir, "fixture-${System.nanoTime()}.apkg")
        val backend = AnkiBackend(sourceDir)
        val storedMedia: String
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

            // 挑一个「普通（2 字段）」模板，保证 1 条笔记 = 1 张卡片；找不到就用第一个。
            val notetypeEntries = backend.callDecoding(
                "NotetypesService",
                "GetNotetypeNames",
                Proto.builder("anki.generic.Empty").build(),
                "anki.notetypes.NotetypeNames",
            ).subs("entries")
            fun notetypeOf(entry: com.google.protobuf.DynamicMessage) = backend.callDecoding(
                "NotetypesService",
                "GetNotetype",
                Proto.builder("anki.notetypes.NotetypeId").putNum("ntid", entry.num("id")).build(),
                "anki.notetypes.Notetype",
            )
            // 2 字段 + 普通（非填空）模板：新集合里第一个 2 字段模板是「填空题+」，不能用。
            val chosen = notetypeEntries.firstOrNull { entry ->
                val notetype = notetypeOf(entry)
                notetype.subs("fields").size == 2 && notetype.sub("config")?.int("kind") == 0
            } ?: notetypeEntries.first()
            val notetypeId = chosen.num("id")
            val fieldCount = notetypeOf(chosen).subs("fields").size

            storedMedia = if (mediaFile != null) {
                backend.callDecoding(
                    "MediaService",
                    "AddMediaFromPath",
                    Proto.builder("anki.media.AddMediaFromPathRequest")
                        .putStr("path", mediaFile.absolutePath)
                        .build(),
                    "anki.generic.String",
                ).str("val")
            } else {
                backend.callDecoding(
                    "MediaService",
                    "AddMediaFile",
                    Proto.builder("anki.media.AddMediaFileRequest")
                        .putStr("desired_name", "inkdeck-fixture.png")
                        .putBytes("data", mediaBytes)
                        .build(),
                    "anki.generic.String",
                ).str("val")
            }

            repeat(notes) { index ->
                val noteBuilder = Proto.builder("anki.notes.Note").putNum("notetype_id", notetypeId)
                val fields = (0 until fieldCount).map { fieldIndex ->
                    if (fieldIndex == 0) {
                        "第 ${index + 1} 张 <img src=\"$storedMedia\">"
                    } else {
                        "答案 ${index + 1} / 字段 ${fieldIndex + 1}"
                    }
                }
                fields.forEach { noteBuilder.addStr("fields", it) }
                val note = noteBuilder.build()
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

            val limit = Proto.builder("anki.import_export.ExportLimit")
                .putMsg("whole_collection", Proto.builder("anki.generic.Empty").build())
                .build()
            val options = Proto.builder("anki.import_export.ExportAnkiPackageOptions")
                .putBool("with_media", true)
                .putBool("with_scheduling", true)
                .putBool("with_deck_configs", true)
                .build()
            backend.callDecoding(
                "ImportExportService",
                "ExportAnkiPackage",
                Proto.builder("anki.import_export.ExportAnkiPackageRequest")
                    .putStr("out_path", outFile.absolutePath)
                    .putMsg("options", options)
                    .putMsg("limit", limit)
                    .build(),
                "anki.generic.UInt32",
            )
        }
        assertTrue("导出文件应存在", outFile.isFile)

        val mediaSize = mediaFile?.length() ?: mediaBytes.size.toLong()
        return Fixture(outFile, storedMedia, mediaSize)
    }

    /** 读取所有笔记字段，看是否引用某个媒体文件。 */
    private suspend fun AnkiRepository.noteFieldsContain(mediaName: String): Boolean = withBackend {
        val ids = callDecoding(
            "SearchService",
            "SearchNotes",
            Proto.builder("anki.search.SearchRequest").putStr("search", "").build(),
            "anki.search.SearchResponse",
        ).nums("ids")
        ids.any { noteId ->
            callDecoding(
                "NotesService",
                "GetNote",
                Proto.builder("anki.notes.NoteId").putNum("nid", noteId).build(),
                "anki.notes.Note",
            ).strs("fields").any { it.contains(mediaName) }
        }
    }



    /** 把包里的集合文件换成垃圾字节，其余条目原样保留：合法的 zip，坏的集合。 */
    private fun rewriteWithBrokenCollection(source: File, target: File) {
        java.util.zip.ZipFile(source).use { zip ->
            java.util.zip.ZipOutputStream(target.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val isCollection = entry.name.endsWith(".anki2") ||
                        entry.name.endsWith(".anki21") ||
                        entry.name.endsWith(".anki21b")
                    if (isCollection) {
                        out.write("这不是一个 SQLite 集合".toByteArray())
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    private fun writeRandomFile(file: File, size: Int): File {
        val random = Random(42)
        file.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            var written = 0
            while (written < size) {
                random.nextBytes(chunk)
                val toWrite = minOf(chunk.size, size - written)
                out.write(chunk, 0, toWrite)
                written += toWrite
            }
        }
        return file
    }

    private companion object {
        /** 1x1 透明 PNG，够小又确实是图片。 */
        val TINY_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        const val LARGE_MEDIA_BYTES = 110 * 1024 * 1024


    }
}
