# rslib 运行时通道

实测时间：2026-11-17（T003）。本文记录「Kotlin ↔ JNI ↔ Anki Rust 后端」这条链的形状、
实测耗时、错误码映射与降级路径；构建侧见 [integration.md](integration.md)、
[build-troubleshooting.md](build-troubleshooting.md)。

## 通道形状

```
UI / 仓库层
   │  DynamicMessage（按 descriptor 动态编码，不生成代码）
AnkiBackend          open / openCollection / call / close，串行访问
   │
NativeBridge         System.loadLibrary 一次 + ensureLoaded 每个入口都校验
   │  三入口：openBackend(init) / runMethod(handle, service, method, args) / closeBackend(handle)
rslib_bridge.so      Rust：按 (服务名, 方法名) 查上游自己的索引表，再 run_service_method
   │
anki::backend::Backend（官方实现，调度/导入/同步同源）
```

要点：

- **单入口分发**：Kotlin 不生成每个 API 的 JNI 桩，只传「服务名 + 方法名 + protobuf 字节」；
  方法索引由 Rust 侧用 `anki_proto_gen::get_services` 在运行时算，与上游生成的
  `run_service_method` 分发表同源，升级 tag 不用改 Kotlin。
- **动态 protobuf**：`protoc --include_imports --include_source_info` 生成的 descriptor 集合
  打进 assets（本次 25 个文件、346 条消息），Kotlin 用 `DynamicMessage` 读写；
  升级 anki 只需重跑构建脚本重新生成 `anki_descriptors.bin`。
- **错误跨 FFI**：Rust 侧 `catch_unwind` 兜 panic，后端的 `BackendError` 解成
  `错误码: 可读文本` 抛 `RslibException`；错误码用 proto 枚举名（`as_str_name()`），
  Kotlin 解析成 `RslibException.Kind`。

## 实测结果

设备：AVD `milenote`（Android 14 / API 34，arm64-v8a），debug APK，`.so` 21.8 MB。

| 项 | 结果 |
|---|---|
| `System.loadLibrary`（冷进程） | **1 ms**（21.8 MB 是懒映射，不随体积线性增长） |
| 首次 `diagnostics()` | 8 ms（含 descriptor 池解析：25 文件 / 346 消息） |
| `openBackend` + 新建集合并 `OpenCollection` | **21 ms**（冷进程）/ 6 ms（同进程复用） |
| `DecksService.DeckTree` 往返 | 3 ms |
| 可分发后端服务 | 23 个 |
| 中文 i18n | 新集合默认牌组名返回「系统默认」，说明 zh-CN 的 ftl 已生效 |
| 产品 APK | 冷启动后首页显示「rslib 原生库：已加载」 |

instrumentation 测试（`app/src/androidTest/java/ink/inkdeck/rslib/RslibRuntimeTest.kt`，6 个用例全过）：

```sh
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.3.13750724"
./rslib/build-android.sh                      # 首次联网拉 crates：约 2m44s；增量约 1m40s
INKDECK_ALIYUN=1 ./gradlew :app:connectedDebugAndroidTest
```

单跑一个用例（看冷启动耗时时用，`am instrument` 不会复用 Gradle 的进程）：

```sh
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class 'ink.inkdeck.rslib.RslibRuntimeTest#openBackendAndRunDeckTreeRoundTrip' \
  ink.inkdeck.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep RslibRuntimeTest
```

## 错误码表

`RslibException.message` 形如 `IO_ERROR: ...`，前缀即 proto `anki.backend.BackendError.Kind`
的枚举名；`RslibException.kind` 是解析后的枚举，UI 按类型决定文案，不做字符串匹配。

| Kotlin `Kind` | proto `Kind` | 含义 | 本次实测 |
|---|---|---|---|
| `INVALID_INPUT` | `INVALID_INPUT` | 参数不合法 | 未构造 |
| `UNDO_EMPTY` | `UNDO_EMPTY` | 无撤销可执行 | 未构造 |
| `INTERRUPTED` | `INTERRUPTED` | 操作被中断 | 未构造 |
| `TEMPLATE_PARSE` | `TEMPLATE_PARSE` | 卡片模板解析失败 | 未构造 |
| `IO_ERROR` | `IO_ERROR` | 文件/目录不可读写 | 未构造 |
| `DB_ERROR` | `DB_ERROR` | SQLite 失败 | ✅ `DB_ERROR: DbError { info: "SqliteFailure(NotADatabase ...)" }`（把非数据库文件当集合打开） |
| `NETWORK_ERROR` | `NETWORK_ERROR` | 网络失败（二期同步） | 未构造 |
| `SYNC_AUTH_ERROR` | `SYNC_AUTH_ERROR` | 同步鉴权失败 | 未构造 |
| `SYNC_SERVER_MESSAGE` | `SYNC_SERVER_MESSAGE` | 服务端消息 | 未构造 |
| `SYNC_OTHER_ERROR` | `SYNC_OTHER_ERROR` | 其它同步错误 | 未构造 |
| `JSON_ERROR` | `JSON_ERROR` | JSON 解析失败 | 未构造 |
| `PROTO_ERROR` | `PROTO_ERROR` | protobuf 编解码失败；也用于「响应不是 BackendError」 | 未构造 |
| `NOT_FOUND_ERROR` | `NOT_FOUND_ERROR` | 对象不存在 | 未构造 |
| `EXISTS` | `EXISTS` | 对象已存在 | 未构造 |
| `FILTERED_DECK_ERROR` | `FILTERED_DECK_ERROR` | 过滤牌组用法错误 | 未构造 |
| `SEARCH_ERROR` | `SEARCH_ERROR` | 搜索语法错误 | 未构造 |
| `CUSTOM_STUDY_ERROR` | `CUSTOM_STUDY_ERROR` | 自定义学习错误 | 未构造 |
| `IMPORT_ERROR` | `IMPORT_ERROR` | 导入失败 | 未构造 |
| `DELETED` | `DELETED` | 对象已删除 | 未构造 |
| `CARD_TYPE_ERROR` | `CARD_TYPE_ERROR` | 卡片类型错误 | 未构造 |
| `ANKIDROID_PANIC_ERROR` | `ANKIDROID_PANIC_ERROR` | 上游 panic 兜底 | 未构造 |
| `OS_ERROR` | `OS_ERROR` | 系统错误 | 未构造 |
| `SCHEDULER_UPGRADE_REQUIRED` | `SCHEDULER_UPGRADE_REQUIRED` | 集合需要升级调度器 | 未构造 |
| `INVALID_CERTIFICATE_FORMAT` | `INVALID_CERTIFICATE_FORMAT` | 证书格式错误 | 未构造 |
| `INVALID_CHECKSUM` | `INVALID_CHECKSUM` | 校验和不匹配 | 未构造 |
| `UNKNOWN` | — | 桥自己产生的错误，没有后端错误码 | ✅ 见下表 |

桥自身（无后端错误码，`kind = UNKNOWN`）的实测文本：

| 场景 | 异常文本 |
|---|---|
| 句柄为 0 / 已关闭 | `后端尚未打开` |
| 服务名或方法名不存在 | `未知的后端方法：DecksService.NoSuchMethod` |
| 初始化字节无法解码 | `后端初始化失败：couldn't decode init request` |
| `.so` 未打包或加载失败 | `librslib_bridge.so 无法加载：UnsatisfiedLinkError: ...` |
| 后端返回空指针 | `<service>.<method> 没有返回数据` |

测试里断言的是「能解析出 kind」而不是具体字符串，后端升级措辞变化不会弄红测试。

## 降级路径

1. **未打包 .so**（只跑 Gradle 没跑 `build-android.sh`）：`NativeBridge.isAvailable == false`，
   首页静态显示「rslib 原生库：未打包」，不崩、不弹窗、不转圈（墨水屏规则：不用 Spinner）。
2. **调用失败**：一律 `RslibException`，调用方按 `kind` 选择静态文案；聚合操作（如关闭集合）
   用 `runCatching` 吞掉，避免二次失败盖住首个错误。
3. **加载时机**：`System.loadLibrary` 在第一次 `isAvailable` / 任何 native 调用时发生
   （`ensureLoaded` 保证顺序无关），冷启动开销 1 ms，可以留在首帧路径上。

## 已知限制与后续

1. **体积**：本次 `.so` 21.8 MB，T002 时只有 2.5 MB——当时只有 `init_backend` 可达，
   其余代码被链接器丢掉；现在 `run_service_method` 与 descriptor 反射全量可达，属预期。
   需要按 ABI 拆分 APK（T009）并评估是否对未用服务做裁剪。
2. **ABI/机型覆盖**：只验证 arm64-v8a / API 34 模拟器；armeabi-v7a（Kindle 成果会用）、
   16 KB 页对齐、真机长时运行都未验证。
3. **错误码覆盖**：只实测了 `DB_ERROR` 与桥自身错误；`INVALID_INPUT`、`IO_ERROR` 等
   需要在 T004（导入）里补真实场景。
4. **版本不匹配**：descriptor 与 `.so` 由同一次构建脚本产出，两者错配时目前只会在
   `dynamic message` 解码处报错；后续可在 `NativeBridge.diagnostics()` 里带上
   descriptor 版本号做启动自检。
5. **首次构建要联网**：crates 未全部缓存时 `--offline` 会缺包（本次缺 `option-ext`），
   干净机器需联网；网络不稳时用镜像或 `CARGO_NET_RETRY`。
