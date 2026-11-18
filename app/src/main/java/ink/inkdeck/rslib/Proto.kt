package ink.inkdeck.rslib

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message

/**
 * 动态 protobuf：不做代码生成，直接读 assets 里的 descriptor 集合。
 *
 * 这样升级 anki tag 只需要重新生成 `anki_descriptors.bin`，Kotlin 侧不用跟着改；
 * 代价是字段按名字访问、没有编译期类型检查，因此这里集中提供带默认值的读取器。
 */
object Proto {
    private const val TAG = "Proto"
    private const val ASSET = "anki_descriptors.bin"

    private var filesByName: Map<String, Descriptors.FileDescriptor> = emptyMap()

    @Volatile
    private var messagesByFullName: Map<String, Descriptors.Descriptor> = emptyMap()

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val set = DescriptorProtos.FileDescriptorSet.parseFrom(bytes)
            val protos = set.fileList.associateBy { it.name }
            val built = HashMap<String, Descriptors.FileDescriptor>()

            fun build(name: String): Descriptors.FileDescriptor {
                built[name]?.let { return it }
                val proto = protos[name] ?: error("descriptor 缺少依赖：$name")
                val deps = proto.dependencyList.map(::build)
                return Descriptors.FileDescriptor.buildFrom(proto, deps.toTypedArray())
                    .also { built[name] = it }
            }
            protos.keys.forEach(::build)
            filesByName = built

            // 索引按「全名」建：FileDescriptor.findMessageTypeByName 只认文件内的相对名，
            // 传入 anki.backend.BackendInit 这种全名会查不到，也会漏掉嵌套消息。
            val index = HashMap<String, Descriptors.Descriptor>()
            fun indexMessage(descriptor: Descriptors.Descriptor) {
                index[descriptor.fullName] = descriptor
                descriptor.nestedTypes.forEach(::indexMessage)
            }
            built.values.forEach { file -> file.messageTypes.forEach(::indexMessage) }
            messagesByFullName = index
            loaded = true
            Log.i(TAG, "已加载 ${built.size} 个 proto 文件、${index.size} 条消息")
        }
    }

    fun descriptor(fullName: String): Descriptors.Descriptor =
        messagesByFullName[fullName] ?: error("descriptor 里没有消息 $fullName")

    fun builder(fullName: String): DynamicMessage.Builder =
        DynamicMessage.newBuilder(descriptor(fullName))

    fun encode(message: Message): ByteArray = message.toByteArray()

    fun decode(fullName: String, bytes: ByteArray): DynamicMessage =
        DynamicMessage.parseFrom(descriptor(fullName), bytes)
}

// ---- 读取器：字段缺失或类型不符时给默认值，避免 UI 因 schema 变动崩溃 ----

fun DynamicMessage.field(name: String): Descriptors.FieldDescriptor =
    descriptorForType.findFieldByName(name) ?: error("${descriptorForType.fullName} 没有字段 $name")

fun DynamicMessage.has(name: String): Boolean {
    val fd = descriptorForType.findFieldByName(name) ?: return false
    return fd.isRepeated || hasField(fd)
}

fun DynamicMessage.str(name: String): String =
    descriptorForType.findFieldByName(name)?.let { getField(it) as? String } ?: ""

fun DynamicMessage.num(name: String): Long =
    descriptorForType.findFieldByName(name)?.let {
        when (val value = getField(it)) {
            is Number -> value.toLong()
            // 枚举字段取出来是 EnumValueDescriptor，不是 Number；不处理会一律读成 0。
            is Descriptors.EnumValueDescriptor -> value.number.toLong()
            else -> null
        }
    } ?: 0L

fun DynamicMessage.int(name: String): Int = num(name).toInt()

fun DynamicMessage.bool(name: String): Boolean =
    descriptorForType.findFieldByName(name)?.let { getField(it) as? Boolean } ?: false

fun DynamicMessage.bytesField(name: String): ByteArray =
    (descriptorForType.findFieldByName(name)?.let { getField(it) } as? ByteString)?.toByteArray()
        ?: ByteArray(0)

fun DynamicMessage.sub(name: String): DynamicMessage? =
    descriptorForType.findFieldByName(name)?.let { getField(it) as? DynamicMessage }

fun DynamicMessage.subs(name: String): List<DynamicMessage> {
    val fd = descriptorForType.findFieldByName(name) ?: return emptyList()
    if (!fd.isRepeated) return listOfNotNull(getField(fd) as? DynamicMessage)
    return getField(fd).let { value ->
        @Suppress("UNCHECKED_CAST")
        (value as? List<Any?>).orEmpty().filterIsInstance<DynamicMessage>()
    }
}

/** 读 repeated 数值字段（如 SearchResponse.ids）；标量也返回单元素列表。 */
fun DynamicMessage.nums(name: String): List<Long> {
    val fd = descriptorForType.findFieldByName(name) ?: return emptyList()
    val value = getField(fd)
    return if (fd.isRepeated) {
        (value as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList()
    } else {
        listOfNotNull((value as? Number)?.toLong())
    }
}

/** 读 repeated 字符串字段（如 Note.fields）。 */
fun DynamicMessage.strs(name: String): List<String> {
    val fd = descriptorForType.findFieldByName(name) ?: return emptyList()
    val value = getField(fd)
    return if (fd.isRepeated) {
        (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    } else {
        listOfNotNull(value as? String)
    }
}

fun DynamicMessage.enumName(name: String): String =
    descriptorForType.findFieldByName(name)?.let {
        (getField(it) as? Descriptors.EnumValueDescriptor)?.name
    } ?: ""

/** 小工具：把可空字符串写进 builder（空串等于不写，proto3 语义一致）。 */
fun DynamicMessage.Builder.putStr(name: String, value: String): DynamicMessage.Builder {
    if (value.isNotEmpty()) {
        setField(protoField(name), value)
    }
    return this
}

fun DynamicMessage.Builder.putNum(name: String, value: Long): DynamicMessage.Builder {
    val fd = protoField(name)
    return when (fd.javaType) {
        Descriptors.FieldDescriptor.JavaType.INT -> setField(fd, value.toInt())
        Descriptors.FieldDescriptor.JavaType.LONG -> setField(fd, value)
        Descriptors.FieldDescriptor.JavaType.FLOAT -> setField(fd, value.toFloat())
        Descriptors.FieldDescriptor.JavaType.DOUBLE -> setField(fd, value.toDouble())
        else -> this
    }
}

fun DynamicMessage.Builder.putBytes(name: String, value: ByteArray): DynamicMessage.Builder =
    setField(protoField(name), ByteString.copyFrom(value))

fun DynamicMessage.Builder.addStr(name: String, value: String): DynamicMessage.Builder =
    addRepeatedField(protoField(name), value)

fun DynamicMessage.Builder.addNum(name: String, value: Long): DynamicMessage.Builder {
    val fd = protoField(name)
    val converted: Any = when (fd.javaType) {
        Descriptors.FieldDescriptor.JavaType.INT -> value.toInt()
        Descriptors.FieldDescriptor.JavaType.LONG -> value
        Descriptors.FieldDescriptor.JavaType.FLOAT -> value.toFloat()
        Descriptors.FieldDescriptor.JavaType.DOUBLE -> value.toDouble()
        else -> error("${fd.fullName} 不是数值字段")
    }
    return addRepeatedField(fd, converted)
}

fun DynamicMessage.Builder.putBool(name: String, value: Boolean): DynamicMessage.Builder =
    setField(protoField(name), value)

fun DynamicMessage.Builder.putMsg(name: String, value: Message): DynamicMessage.Builder =
    setField(protoField(name), value)

fun DynamicMessage.Builder.addMsg(name: String, value: Message): DynamicMessage.Builder =
    addRepeatedField(protoField(name), value)

fun DynamicMessage.Builder.putEnum(name: String, valueName: String): DynamicMessage.Builder {
    val fd = protoField(name)
    val value = fd.enumType.findValueByName(valueName)
        ?: error("${fd.enumType.fullName} 没有枚举值 $valueName")
    return setField(fd, value)
}

private fun DynamicMessage.Builder.protoField(name: String): Descriptors.FieldDescriptor =
    descriptorForType.findFieldByName(name) ?: error("${descriptorForType.fullName} 没有字段 $name")
