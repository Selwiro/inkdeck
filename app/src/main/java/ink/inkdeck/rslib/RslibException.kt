package ink.inkdeck.rslib

/**
 * 原生后端（rslib）返回的错误，message 已是可读文本。
 *
 * 文本形如 `IO_ERROR: could not open collection`，前缀是 protobuf `BackendError.Kind`
 * 的枚举名（由 Rust 侧 `as_str_name()` 生成，与 proto 定义逐字一致）；
 * [kind] 把它解析成枚举，UI 可以按类型决定「重试 / 提示路径 / 引导重装」，
 * 而不用去字符串里找关键字。解析不出来时是 [Kind.UNKNOWN]（例如「后端尚未打开」这类
 * 桥自己产生的错误，本就没有后端错误码）。
 */
class RslibException(message: String, val kind: Kind) : RuntimeException(message) {

    constructor(message: String) : this(message, Kind.parse(message))

    /** protobuf `anki.backend.BackendError.Kind` 的镜像；新增枚举值时要同步。 */
    enum class Kind {
        INVALID_INPUT,
        UNDO_EMPTY,
        INTERRUPTED,
        TEMPLATE_PARSE,
        IO_ERROR,
        DB_ERROR,
        NETWORK_ERROR,
        SYNC_AUTH_ERROR,
        SYNC_SERVER_MESSAGE,
        SYNC_OTHER_ERROR,
        JSON_ERROR,
        PROTO_ERROR,
        NOT_FOUND_ERROR,
        EXISTS,
        FILTERED_DECK_ERROR,
        SEARCH_ERROR,
        CUSTOM_STUDY_ERROR,
        IMPORT_ERROR,
        DELETED,
        CARD_TYPE_ERROR,
        ANKIDROID_PANIC_ERROR,
        OS_ERROR,
        SCHEDULER_UPGRADE_REQUIRED,
        INVALID_CERTIFICATE_FORMAT,
        INVALID_CHECKSUM,
        UNKNOWN;

        companion object {
            /** `IO_ERROR: detail` → [IO_ERROR]；不带错误码前缀的消息 → [UNKNOWN]。 */
            fun parse(message: String): Kind {
                val prefix = message.substringBefore(':', missingDelimiterValue = "")
                if (prefix.isEmpty()) return UNKNOWN
                return entries.firstOrNull { it.name == prefix.trim() } ?: UNKNOWN
            }
        }
    }
}
