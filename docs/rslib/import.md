# .apkg 导入与媒体入库

实测时间：2026-11-17（T004）。设备：AVD `milenote`（Android 14 / API 34，arm64-v8a）。
本文记录导入链路的形状、实测数据、错误与清理语义；运行时通道本身见 [runtime.md](runtime.md)。

## 流程

```
SAF 选择 .apkg
   │  OpenDocument（任意 MIME，apkg 没有稳定 MIME）
PackageImporter.stage()
   │  1. 查 DISPLAY_NAME / SIZE，按 StatFs 预检空间（预留 32MB）
   │  2. 流式复制到 cache/imports/incoming-<ts>.apkg（每 256KB 回调一次进度）
   │  3. 校验：zip 魔数 → 能打开 → 包内必须有 collection.anki21b / anki21 / anki2
   │  4. 算 SHA-256，查是否导入过（重复则先让用户确认）
PackageImporter.import()
   │  rslib：ImportExportService.GetImportAnkiPackagePresets → ImportAnkiPackage
   │  整个导入在上游是一个 SQLite 事务（rslib import_apkg → transact(Op::Import)）
   │  期间每 400ms 轮询 CollectionService.LatestProgress 取阶段文本（静态文案）
   │  成功：记下 SHA-256；无论成败都删掉临时包
结果页：新增/更新/重复/冲突 笔记数 + 本次新增媒体数 + 集合现有卡片数
   │  若本次 0 新增/更新：自动删除本次新建的空牌组，结果页显示清理数量
```

关键设计：

- **进度只有文案**：复制阶段显示「已复制 43.2 / 110.0 MB」，导入阶段显示后端给的阶段文本
  与「已用时 N 秒」，没有 Spinner、没有动画（墨水屏规则）。
- **并发只放开两个方法**：`LatestProgress` / `SetWantsAbort` 只碰后端 progress 状态、不拿集合锁，
  因此走 `AnkiBackend.callConcurrent` 绕过单线程串行；其余调用仍严格串行，导入期间天然互斥。
- **重复判定按内容哈希**：同一个包改个文件名仍会被识别；换内容则视为新包。
- **媒体数是增量**：导入前后各数一次 `collection.media`，结果页显示本次新增，不再把历史媒体算进来。
- **空牌组清理**：全部笔记冲突时上游仍会创建包内牌组；应用只删除「本次新建且总卡片数为 0」
  的牌组，已有牌组和成功导入不动。
- **分享/打开入口**：Manifest 注册了 `ACTION_SEND`（octet-stream/zip）与 `ACTION_VIEW`
  （content/file + octet-stream/zip）；文件管理器分享或「打开方式」选 Inkdeck 会直接进入导入页。

## 实测数据

| 场景 | 结果 |
|---|---|
| 115 MB 包（110 MB 随机媒体 + 2 条笔记）复制 | **64 ms**（cache 目录在 /data，顺序写） |
| 同一包导入（含媒体落盘） | **150–650 ms**（首次冷 647 ms，之后 150 ms） |
| 导入后 UI 读数 | 新增 2 条笔记 / 本次新增媒体 1 个 / 集合现有卡片 2 张 |
| 同一包再导入 | 新增 0、重复跳过 2、卡片数仍 2；导入前弹出「这个包之前导入过」确认 |
| 3 条笔记 + 1 张图片的小包 | 新增 3、卡片 3、媒体文件大小与源一致、笔记字段引用该媒体名 |
| 3000 个媒体文件的包 | 导入 **32 ms**（媒体走 reflink/快速写入，快到无法打断） |
| 新牌组但笔记全部冲突（2026-11-18 合成包） | 新增 0、冲突 27；自动清理 1 个本次新建空牌组，牌组数不变 |
| 集合文件损坏的包（合法 zip、坏 SQLite） | 导入失败，**集合里 0 张卡片**；随后用完好包重试成功 |

复现：

```sh
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.3.13750724"
./rslib/build-android.sh
INKDECK_ALIYUN=1 ./gradlew :app:connectedDebugAndroidTest    # 含 T003/T004 全部用例

# 大包用例默认跳过，需要时单跑：
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e large true \
  -e class ink.inkdeck.importing.ImportPackageTest#importsLargePackage \
  ink.inkdeck.test/androidx.test.runner.AndroidJUnitRunner
```

真机/UI 手工路径（本次已走通一遍）：

```sh
adb push big.apkg /sdcard/Download/
adb shell am start -n ink.inkdeck/.MainActivity   # 首页 → 导入牌组包（.apkg）→ 选择 .apkg 文件
```

## 错误矩阵

| 情况 | 判定点 | 用户看到的文案 | 集合状态 |
|---|---|---|---|
| 选中的不是 zip | `stage()` 校验魔数 | 这不是 Anki 牌组包（.apkg）：不是 zip 压缩包 | 未打开，无变化 |
| zip 损坏 / 复制中断 | `stage()` 打开 zip | 这不是 Anki 牌组包（.apkg）：<zip 错误> | 无变化 |
| 空文件 | `stage()` | 这不是 Anki 牌组包（.apkg）：文件为空 | 无变化 |
| zip 里没有集合文件 | `stage()` | 这不是 Anki 牌组包（.apkg）：包内没有 collection.anki2/anki21/anki21b | 无变化 |
| 空间不足 | `stage()` 复制前 | 存储空间不足：需要 X MB，可用 Y MB | 无变化 |
| 集合内容损坏 | `ImportAnkiPackage` | 包文件损坏或无法读取。<br>DB_ERROR: … NotADatabase … | **事务回滚，0 张卡片** |
| 包格式不被后端支持 | `ImportAnkiPackage` | 包内容不合法。/ Anki 拒绝了这次导入。 | 事务回滚 |
| 用户点取消 | `SetWantsAbort` → Interrupted | 已取消导入，没有写入任何卡片 | 事务回滚（见下节） |

错误码到文案的映射在 `ImportScreen.userMessage()`，错误码本身来自 `RslibException.kind`。

## 取消与中断的清理语义

- **取消**：导入期间界面有「取消导入」（复制期间是「取消复制」）。取消 → `SetWantsAbort` →
  后端在下一个进度检查点返回 Interrupted → 上游事务回滚 → 我们映射成
  `ImportFailure.Cancelled`，界面显示「已取消导入，没有写入任何卡片」。
- **进程被杀**：事务未提交时 SQLite 的 rollback journal 会回滚，集合回到导入前状态。
  这一点没有用 kill 进程的方式实测（时序不可控），但由「坏集合导入失败后 0 张卡片」
  用例间接覆盖了同一条回滚路径。
- **临时包**：复制失败/取消立即删除；导入结束（成功或失败）删除；进程被杀留下的
  `cache/imports/incoming-*.apkg` 在下次导入时按 24 小时清理。
- **媒体孤儿**：媒体文件在事务之外写入 `collection.media`。导入失败时卡片与媒体库记录会回滚，
  但已落盘的媒体文件可能留下孤儿；它们不会被任何卡片引用，可用 `MediaService.CheckMedia`
  清理。这是上游行为，我们不做额外删除（避免误删同名被引用的文件）。

**未在设备上验证的点**：自动化取消用例跑不起来——模拟器上 115MB 包 150ms、3000 个媒体文件的包
32ms 就导完了，取消窗口小于 50ms。手工验证步骤：真机上导入一个 >5 万张卡片或 >1GB 媒体的包，
在「正在导入」出现后立刻点取消，预期界面显示「已取消导入」，随后浏览集合卡片数不变。

## 验收对照

| 验收项 | 结果 |
|---|---|
| 1) 导入含媒体 .apkg 后牌组/卡片数与源一致 | ✅ 用同一 rslib 造的包：源 3 笔记 3 卡片 → 导入后 3/3；115MB 包 2 笔记 2 卡片 |
| 2) 媒体落到 collection.media 且被卡片引用 | ✅ 文件存在、大小与源一致；笔记字段里含媒体名（`SearchNotes` + `GetNote` 实测） |
| 3) 取消或进程被杀不留半成品集合 | ✅ 代码路径 + 事务回滚；失败回滚用例实测 0 张卡片并可立即重试；取消窗口未能在模拟器上命中（见上） |
| 4) 重复导入有确认与结果提示 | ✅ 内容哈希命中后先弹确认；确认后结果里「重复跳过 N 条」，卡片数不变 |
| 5) 非 apkg / 损坏包 / 空间不足有可读提示且不崩 | ✅ 前两类有自动化用例；空间不足走同一 `stage()` 预检分支，未实测（模拟器剩余 9GB） |
| 6) 实测数据写入文档 | ✅ 本文 |

## 真机修复：Android 临时目录

Android 8.1 真机首次导入报：

```
IO_ERROR: Failed to create file in '/data/local/tmp':
Permission denied (os error 13) at path "/data/local/tmp/.tmpXxhpjU"
```

原因是 rslib 走 Rust `std::env::temp_dir()`，Android 上默认 `/data/local/tmp`，普通应用不可写；
模拟器此前是 debuggable/可写环境，未暴露。修复：JNI 新增
`NativeBridge.setTempDir()`，在 `AnkiBackend.open()`（后端线程）里把
`TMPDIR`/`TMP`/`TEMP` 指到 `cache/rslib-tmp` 后再 `openBackend`。
修复后 InkPad X 导入 27 条笔记/27 张卡片/2 个媒体成功，重复导入正常。

复现：未修复版分享任意 `.apkg` 到 InkPad X；修复版重新导入同一包即成功。代码位置：
`rslib/bridge/src/lib.rs`、`rslib/NativeBridge.kt`、`rslib/AnkiBackend.kt`。

## 已知限制与后续

1. **没有导入队列**：一次只能导入一个包（后端单线程），重复点击会被状态机挡住，但没有排队语义。
2. **大包内存**：复制是流式的，媒体数据不经过 Kotlin 堆；但 3 万张卡片的包在 `ImportResponse`
   里会带完整日志（每条笔记的字段），内存与耗时都会上去，未压测。
3. **.colpkg**（整库备份）没接：需求是一期导卡，整库替换的确认语义更重，留给后续版本。
4. **媒体引用只验证到字段文本**：真正的「浏览时不缺图不缺音」要等 T005 的卡片渲染页。
5. **空间预检依赖 `OpenableColumns.SIZE`**：某些文件管理器不提供大小，这种情况只能靠导入失败兜底。
