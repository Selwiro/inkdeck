package ink.inkdeck.data

import android.content.Context
import android.util.Log
import com.google.protobuf.DynamicMessage
import ink.inkdeck.rslib.AnkiBackend
import ink.inkdeck.rslib.Proto
import ink.inkdeck.rslib.addNum
import ink.inkdeck.rslib.bool
import ink.inkdeck.rslib.enumName
import ink.inkdeck.rslib.int
import ink.inkdeck.rslib.num
import ink.inkdeck.rslib.nums
import ink.inkdeck.rslib.putEnum
import ink.inkdeck.rslib.putMsg
import ink.inkdeck.rslib.putNum
import ink.inkdeck.rslib.putStr
import ink.inkdeck.rslib.str
import ink.inkdeck.rslib.strs
import ink.inkdeck.rslib.sub
import ink.inkdeck.rslib.subs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * 应用的数据入口：一个后端句柄 + 一条后端线程。
 *
 * rslib 的集合操作必须在同一线程串行（后端自己也有锁，但把调用集中到单线程
 * 更省心，也让「导入中」这类长操作天然互斥）。唯一的例外是导入期间的进度轮询
 * 与取消：它们只碰后端的 progress 状态，不走集合锁，见 [progressStage] / [requestAbort]。
 */
class AnkiRepository(
    context: Context,
    rootDir: File = File(context.filesDir, "anki"),
) : Closeable {

    private val backend = AnkiBackend(
        rootDir = rootDir,
        tempDir = File(context.cacheDir, "rslib-tmp").apply { mkdirs() },
    )
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anki-backend")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    init {
        // descriptor 集合随 APK 打包，进程内只解析一次。
        Proto.load(context.applicationContext)
    }

    val collectionFile: File get() = backend.collectionFile
    val mediaDir: File get() = backend.mediaDir

    /** 关闭并重新打开后端；语言切换后让 rslib 的 preferred_langs 生效。 */
    fun restartBackend() {
        executor.execute {
            runCatching { backend.close() }
        }
    }

    /** 打开集合后在后台线程执行；[block] 里可以随意调用后端。 */
    suspend fun <T> withBackend(block: AnkiBackend.() -> T): T = withContext(dispatcher) {
        backend.open()
        backend.openCollection()
        block(backend)
    }

    /** 牌组树（含各队列余量）。调用方按需缓存，不要每次重组都请求。 */
    suspend fun deckTree(): DeckNode = withBackend { deckTreeInBackend() }

    private fun AnkiBackend.deckTreeInBackend(): DeckNode {
        val request = Proto.builder("anki.decks.DeckTreeRequest")
            .putNum("now", System.currentTimeMillis() / 1000)
            .build()
        val response = callDecoding(
            "DecksService",
            "DeckTree",
            request,
            "anki.decks.DeckTreeNode",
        )
        return response.toDeckNode(parentFullName = null)
    }

    /** 牌组树拍平成带缩进层级的行，顺序与后端返回一致（父在前、子紧跟）。 */
    suspend fun deckRows(): List<DeckNode> = deckTree().children.flatMap { it.flatten() }

    /**
     * 某个牌组的卡片 id 列表。
     *
     * 用 `BuildSearchString(SearchNode{deck: 名字})` 而不是手拼 `deck:"..."`；
     * 注意后端存的名字用 `\x1f` 分层级，而搜索语法用 `::`，这里要先转换，
     * 否则只有顶级牌组能搜到卡片、子牌组永远 0 张。
     */
    suspend fun cardIdsInDeck(deckName: String): List<Long> = withBackend {
        val search = callDecoding(
            "SearchService",
            "BuildSearchString",
            Proto.builder("anki.search.SearchNode").putStr("deck", deckName.toSearchName()).build(),
            "anki.generic.String",
        ).str("val")
        callDecoding(
            "SearchService",
            "SearchCards",
            Proto.builder("anki.search.SearchRequest").putStr("search", search).build(),
            "anki.search.SearchResponse",
        ).nums("ids")
    }

    /** 取一页卡片（含预览文本）。页码从 1 开始，越界会被夹到有效范围。 */
    suspend fun cardsPage(deckName: String, page: Int, pageSize: Int): CardPage = withBackend {
        val ids = cardIdsInDeckInBackend(deckName)
        val pageCount = if (ids.isEmpty()) 0 else (ids.size + pageSize - 1) / pageSize
        val clamped = page.coerceIn(1, maxOf(1, pageCount))
        val slice = if (ids.isEmpty()) {
            emptyList()
        } else {
            ids.drop((clamped - 1) * pageSize).take(pageSize)
        }
        CardPage(
            deckName = deckName,
            cards = slice.map { cardId ->
                val card = callDecoding(
                    "CardsService",
                    "GetCard",
                    Proto.builder("anki.cards.CardId").putNum("cid", cardId).build(),
                    "anki.cards.Card",
                )
                val noteId = card.num("note_id")
                CardRow(
                    cardId = cardId,
                    noteId = noteId,
                    title = noteTitleInBackend(noteId).ifBlank { "（空字段）" },
                )
            },
            page = clamped,
            pageCount = pageCount,
            total = ids.size,
        )
    }

    /** 渲染一张已存在的卡片（问题面/答案面 HTML + CSS）。 */
    suspend fun renderCard(cardId: Long): RenderedCard = withBackend { renderCardInBackend(cardId) }

    /** 媒体文件路径；不存在（或被引用但没导入）时返回 null。 */
    fun mediaFile(name: String): File? {
        val file = File(mediaDir, name)
        return file.takeIf { it.isFile }
    }

    private fun AnkiBackend.cardIdsInDeckInBackend(deckName: String): List<Long> {
        val search = callDecoding(
            "SearchService",
            "BuildSearchString",
            Proto.builder("anki.search.SearchNode").putStr("deck", deckName.toSearchName()).build(),
            "anki.generic.String",
        ).str("val")
        return callDecoding(
            "SearchService",
            "SearchCards",
            Proto.builder("anki.search.SearchRequest").putStr("search", search).build(),
            "anki.search.SearchResponse",
        ).nums("ids")
    }

    /** 笔记第一个字段的纯文本，作为卡片列表的一行标题（避免每行重复牌组名）。 */
    private fun AnkiBackend.noteTitleInBackend(noteId: Long): String {
        val note = callDecoding(
            "NotesService",
            "GetNote",
            Proto.builder("anki.notes.NoteId").putNum("nid", noteId).build(),
            "anki.notes.Note",
        )
        return note.strs("fields").firstOrNull()?.let { htmlToPreview(it, 120) }.orEmpty()
    }

    private fun AnkiBackend.renderCardInBackend(cardId: Long): RenderedCard {
        val response = callDecoding(
            "CardRenderingService",
            "RenderExistingCard",
            Proto.builder("anki.card_rendering.RenderExistingCardRequest")
                .putNum("card_id", cardId)
                .build(),
            "anki.card_rendering.RenderCardResponse",
        )
        val questionHtml = response.subs("question_nodes").joinToString("") { it.str("text") }
        val answerHtml = response.subs("answer_nodes").joinToString("") { it.str("text") }
        return RenderedCard(
            cardId = cardId,
            questionHtml = questionHtml,
            answerHtml = answerHtml,
            css = response.str("css"),
            questionImages = htmlImages(questionHtml),
            answerImages = htmlImages(answerHtml),
            questionSounds = htmlSounds(questionHtml),
            answerSounds = htmlSounds(answerHtml),
            broken = isBrokenCard(questionHtml, answerHtml),
        )
    }

    /**
     * 取复习队列（按后端调度顺序），每张卡都渲染好并带上四档文案。
     *
     * [deckId] 非空时先 `SetCurrentDeck`：rslib 的队列是「当前牌组」的队列，
     * 不先切牌组的话，点任何牌组拿到的都是 Default 的 0 张。
     */
    suspend fun reviewQueue(limit: Int = 20, deckId: Long? = null): ReviewQueue = withBackend {
        if (deckId != null) {
            call(
                "DecksService",
                "SetCurrentDeck",
                Proto.builder("anki.decks.DeckId").putNum("did", deckId).build(),
            )
        }
        val response = callDecoding(
            "SchedulerService",
            "GetQueuedCards",
            Proto.builder("anki.scheduler.GetQueuedCardsRequest")
                .putNum("fetch_limit", limit.toLong())
                .build(),
            "anki.scheduler.QueuedCards",
        )
        val cards = response.subs("cards").mapNotNull { queued ->
            val card = queued.sub("card") ?: return@mapNotNull null
            val cardId = card.num("id")
            val states = queued.sub("states") ?: return@mapNotNull null
            val labels = callDecoding(
                "SchedulerService",
                "DescribeNextStates",
                states,
                "anki.generic.StringList",
            ).strs("vals")
            val rendered = renderCardInBackend(cardId)
            ReviewCard(
                cardId = cardId,
                queue = queued.enumName("queue"),
                questionHtml = rendered.questionHtml,
                answerHtml = rendered.answerHtml,
                css = rendered.css,
                questionImages = rendered.questionImages,
                answerImages = rendered.answerImages,
                questionSounds = rendered.questionSounds,
                answerSounds = rendered.answerSounds,
                statesBytes = states.toByteArray(),
                ratingLabels = labels,
                broken = rendered.broken,
            )
        }
        ReviewQueue(
            cards = cards,
            newCount = response.num("new_count").toInt(),
            learningCount = response.num("learning_count").toInt(),
            reviewCount = response.num("review_count").toInt(),
        )
    }

    /**
     * 评分：把四档里选中的那档原样回传（`new_state`），调度与间隔计算全部由后端完成。
     * 返回的 [AnswerOutcome.nextInterval] 是评分前后端给该档的文案（如「1 天」）。
     */
    suspend fun answerCard(
        card: ReviewCard,
        rating: Rating,
        millisecondsTaken: Long,
    ): AnswerOutcome = withBackend {
        val states = Proto.decode("anki.scheduler.SchedulingStates", card.statesBytes)
        val current = states.sub("current") ?: error("调度状态缺少 current")
        val chosen = states.sub(rating.name.lowercase()) ?: error("调度状态缺少 ${rating.name}")
        val nextInterval = card.ratingLabels.getOrNull(rating.ordinal) ?: ""

        val answer = Proto.builder("anki.scheduler.CardAnswer")
            .putNum("card_id", card.cardId)
            .putMsg("current_state", current)
            .putMsg("new_state", chosen)
            .putEnum("rating", rating.name)
            .putNum("answered_at_millis", System.currentTimeMillis())
            .putNum("milliseconds_taken", millisecondsTaken)
            .build()
        call("SchedulerService", "AnswerCard", answer)

        // 评分后立刻问一次剩余量（只读计数，不再渲染卡片）。
        val queues = callDecoding(
            "SchedulerService",
            "GetQueuedCards",
            Proto.builder("anki.scheduler.GetQueuedCardsRequest")
                .putNum("fetch_limit", 1)
                .build(),
            "anki.scheduler.QueuedCards",
        )
        val remaining = queues.num("new_count").toInt() +
            queues.num("learning_count").toInt() +
            queues.num("review_count").toInt()
        AnswerOutcome(nextInterval = nextInterval, remaining = remaining)
    }

    /**
     * 一期统计快照：今日复习、卡片构成、到期预测，全部来自 `StatsService.Graphs`。
     * 不做任何本地聚合，字段一一对应后端返回值。
     */
    suspend fun stats(days: Int = 30): StatsSnapshot = withBackend {
        val graphs = callDecoding(
            "StatsService",
            "Graphs",
            Proto.builder("anki.stats.GraphsRequest")
                .putStr("search", "")
                .putNum("days", days.toLong())
                .build(),
            "anki.stats.GraphsResponse",
        )
        val today = graphs.sub("today")
        val counts = graphs.sub("card_counts")?.sub("excluding_inactive")
        val futureDue = graphs.sub("future_due")
        StatsSnapshot(
            todayAnswers = today?.num("answer_count")?.toInt() ?: 0,
            todayCorrect = today?.num("correct_count")?.toInt() ?: 0,
            todayMillis = today?.num("answer_millis") ?: 0L,
            newCards = counts?.num("newCards")?.toInt() ?: 0,
            learningCards = (counts?.num("learn") ?: 0L).toInt() +
                (counts?.num("relearn") ?: 0L).toInt(),
            youngCards = counts?.num("young")?.toInt() ?: 0,
            matureCards = counts?.num("mature")?.toInt() ?: 0,
            suspendedCards = counts?.num("suspended")?.toInt() ?: 0,
            dueToday = futureDue.mapValue("future_due", 0),
            dueTomorrow = futureDue.mapValue("future_due", 1),
            dailyLoad = futureDue?.num("daily_load")?.toInt() ?: 0,
            fsrs = graphs.bool("fsrs"),
        )
    }

    /** 整个集合的卡片数（含子牌组）。 */
    suspend fun cardCount(): Int = deckTree().totalInDeck

    /**
     * 导入 .apkg。整个导入在上游是一个 SQLite 事务：失败或被 Abort 时自动回滚，
     * 不会留下半成品卡片。
     */
    suspend fun importPackage(packagePath: String): ImportResult = withBackend {
        val deckIdsBefore = deckTreeInBackend().flatten().map { it.id }.toSet()
        val mediaBefore = countMediaFiles()
        val presets = callDecoding(
            "ImportExportService",
            "GetImportAnkiPackagePresets",
            Proto.builder("anki.generic.Empty").build(),
            "anki.import_export.ImportAnkiPackageOptions",
        )
        val request = Proto.builder("anki.import_export.ImportAnkiPackageRequest")
            .putStr("package_path", packagePath)
            .putMsg("options", presets)
            .build()
        val response = callDecoding(
            "ImportExportService",
            "ImportAnkiPackage",
            request,
            "anki.import_export.ImportResponse",
        )
        val log = response.sub("log")
        val result = ImportResult(
            newNotes = log?.subs("new")?.size ?: 0,
            updatedNotes = log?.subs("updated")?.size ?: 0,
            duplicateNotes = log?.subs("duplicate")?.size ?: 0,
            firstFieldMatchNotes = log?.subs("first_field_match")?.size ?: 0,
            conflictingNotes = log?.subs("conflicting")?.size ?: 0,
            missingNotetypeNotes = log?.subs("missing_notetype")?.size ?: 0,
            foundNotes = log?.num("found_notes")?.toInt() ?: 0,
            // 媒体是本次增量，不是 collection.media 的总数。
            mediaFiles = (countMediaFiles() - mediaBefore).coerceAtLeast(0),
        )

        // 全部笔记都冲突（0 新增/更新）时，上游仍会把包内牌组建出来，留下空牌组。
        // 这时只删除「本次新建且一张卡都没有」的牌组；已有牌组和成功的导入不动。
        var removedEmptyDecks = 0
        if (result.changedNotes == 0) {
            val newEmptyIds = deckTreeInBackend().flatten()
                .filter { it.id !in deckIdsBefore && it.totalInDeck == 0 }
                .map { it.id }
            if (newEmptyIds.isNotEmpty()) {
                val ids = Proto.builder("anki.decks.DeckIds")
                newEmptyIds.forEach { ids.addNum("dids", it) }
                val removed = runCatching {
                    call("DecksService", "RemoveDecks", ids.build())
                }
                if (removed.isSuccess) {
                    removedEmptyDecks = newEmptyIds.size
                } else {
                    Log.w(TAG, "清理空牌组失败：${removed.exceptionOrNull()?.message}")
                }
            }
        }
        result.copy(removedEmptyDecks = removedEmptyDecks)
    }

    /** 空卡无法评分：暂停（suspend），避免它一直卡在队首。 */
    suspend fun suspendCard(cardId: Long) = withBackend {
        val request = Proto.builder("anki.scheduler.BuryOrSuspendCardsRequest")
            .addNum("card_ids", cardId)
            .putEnum("mode", "SUSPEND")
            .build()
        call("SchedulerService", "BuryOrSuspendCards", request)
        Unit
    }

    /** collection.media 里的文件数（不含 media.db2），用于导入结果提示与验收。 */
    suspend fun mediaFileCount(): Int = withContext(dispatcher) { countMediaFiles() }

    private fun countMediaFiles(): Int = backend.mediaDir.listFiles()
        ?.count { it.isFile && !it.name.startsWith("collection.media") }
        ?: 0

    /**
     * 导入进度文案（后端给的是 `importing: <阶段文本>`）。
     *
     * 走 [AnkiBackend.callConcurrent]：这个方法只读后端的 progress 状态，不拿集合锁，
     * 因此可以在导入进行中从别的线程调用。返回 null 表示当前没有进行中的进度。
     */
    suspend fun progressStage(): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val progress = Proto.decode(
                "anki.collection.Progress",
                backend.callConcurrent("CollectionService", "LatestProgress"),
            )
            progress.str("importing").ifEmpty { null }
        }.onFailure {
            Log.w(TAG, "LatestProgress 调用失败：${it.message}")
        }.getOrNull()
    }

    /** 请求中断当前长操作；后端在下一次进度上报时抛 Interrupted，事务回滚。 */
    suspend fun requestAbort() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { backend.callConcurrent("CollectionService", "SetWantsAbort") }
            .onFailure { Log.w(TAG, "SetWantsAbort 调用失败：${it.message}") }
        Unit
    }

    private companion object {
        const val TAG = "AnkiRepository"
    }

    override fun close() {
        runCatching { backend.close() }
        executor.shutdown()
    }
}

/** protobuf map 字段取某个 key 的整数值（map 在消息里是 repeated entry{key,value}）。 */
/** 牌组内部名（\x1f 分层）→ 搜索语法名（:: 分层）。 */
internal fun String.toSearchName(): String = replace("\u001f", "::")

private fun DynamicMessage?.mapValue(field: String, key: Long): Int {
    val self = this ?: return 0
    return self.subs(field).firstOrNull { it.num("key") == key }?.num("value")?.toInt() ?: 0
}

private fun DynamicMessage.toDeckNode(parentFullName: String?): DeckNode {
    val leaf = str("name")
    val fullName = if (parentFullName.isNullOrEmpty()) leaf else "$parentFullName::$leaf"
    return DeckNode(
        id = num("deck_id"),
        name = leaf,
        fullName = fullName,
        level = int("level"),
        newCount = int("new_count"),
        learnCount = int("learn_count"),
        reviewCount = int("review_count"),
        totalInDeck = int("total_including_children"),
        children = subs("children").map { it.toDeckNode(fullName) },
    )
}
