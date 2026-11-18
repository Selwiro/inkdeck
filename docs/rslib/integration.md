# rslib 接入方案

调研时间：2026-11-13（T001）。结论与理由在此，实施细节随进展补充。

## 调研结论

| 候选 | 现状 | 是否可用 |
|---|---|---|
| crates.io 的 `anki` | 仅有 2019 年的占位包 0.0.1；`anki_proto`、`anki_i18n`、`rsdroid` 均 404 | 否 |
| Maven Central 的 rsdroid | 无该坐标 | 否 |
| JitPack 的 Anki-Android-Backend | `maven-metadata.xml` 404，无可用构建产物 | 否 |
| AnkiDroid 官方 `Anki-Android-Backend` 仓库 | `rslib-bridge`（Rust cdylib）+ `rsdroid`（AAR）+ 测试模块，rslib 以 submodule 形式内嵌 | 是，但需自行构建 |

参考：[Anki-Android-Backend 架构说明](https://github.com/ankitects/Anki-Android-Backend/blob/main/docs/ARCHITECTURE.md)、[cargo-ndk](https://github.com/bbqsrc/cargo-ndk)。

官方桥的设计值得照搬：`rslib-bridge` 只向 Java 暴露三个函数——`openBackend()`、`closeBackend()`、`runMethodRaw()`；所有业务调用都是「方法名 + protobuf 二进制」经由这一个入口进出。这样 Kotlin 侧不需要为每个 Anki API 生成 JNI 桩，新增接口只改 proto。

## 选定路线：自建 rslib-bridge

```
work/app/
├─ rslib/                     # 上游 anki 源码（submodule 或 vendor，固定 tag）
│  └─ bridge/                 # 自写 Rust cdylib：open/close/runMethodRaw
├─ app/src/main/jniLibs/arm64-v8a/librslib_bridge.so   # 构建产物，不进版本库
└─ app/src/main/java/ink/inkdeck/rslib/                # Kotlin 侧封装
```

构建（环境已具备：NDK 27.3、Rust 1.100、cargo-ndk 4.1、protoc 36.2）：

```sh
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.3.13750724"
./rslib/build-android.sh          # 默认 arm64-v8a、API 26，产物拷到 app/src/main/jniLibs/
```

脚本做四件事：取上游源码到 `rslib/anki/`（固定 tag，git 失败回落 tarball）→ 生成 protobuf descriptor →
`cargo ndk` 构建 → 拷贝 `.so`。上游源码、descriptor、`.so` 都不进版本库。

- `-P 26` 必须显式给：cargo-ndk 默认 API 21，与本项目 minSdk 26 不一致。
- 踩坑与解决办法见 [build-troubleshooting.md](build-troubleshooting.md)。

## 实测结果（2026-11-13，tag 26.09.4）

| 项 | 结果 |
|---|---|
| 上游 tag | `26.09.4`（`rust-version = 1.80`） |
| 冷编译全量 | 1 分 51 秒（依赖 + `anki` + bridge，thin LTO + strip） |
| 产物 | `librslib_bridge.so` 2.5 MB，仅保留 `init_backend` 一条路径 |
| 架构校验 | ELF64 DYN AArch64，含 `.note.android.ident`，NEEDED 仅 `libc/libm/libdl` |
| JNI 符号 | `Java_ink_inkdeck_rslib_NativeBridge_externalOpenBackend` / `externalCloseBackend` |
| APK 打包 | `app-debug.apk` 内确认存在 `lib/arm64-v8a/librslib_bridge.so` |
| 未做 | 真机/模拟器运行时调用（沙箱内无法起 emulator；等 InkPad X 接上） |

体积说明：全量 API 暴露（AnkiDroid 的 rsdroid 约十几 MB）会显著大于 2.5 MB，属于预期。

## 备选方案与取舍

| 方案 | 优点 | 代价 |
|---|---|---|
| B. fork `Anki-Android-Backend` 作 submodule | 直接用官方 Kotlin 绑定与测试 | 多维护一份上游 fork；升级时要跟两个仓库 |
| C. 纯 Kotlin 调度（自研 SM-2/FSRS） | 无原生依赖，构建简单 | 无法保证与官方调度/同步一致，违背一期验收 |

一期按 A 推进；B 作为 A 受阻时的替代；C 只在原生构建彻底走不通时降级，并要在任务记录里写明「与官方不一致」。

## 许可证与合规

- rslib 为 **AGPL-3.0**，本项目整体采用 AGPL-3.0（仓库根 `LICENSE`）。
- 分发 APK（含 `.so`）时，必须同时提供完整对应源码：本项目代码 + 固定 tag 的上游 anki 源码 + 我们的 bridge。
- 保留上游版权与 NOTICE；若复用 AnkiDroid 的 Kotlin 绑定，保留其文件头与 AGPL 声明。
- AGPL 第 13 条：若将来提供网络服务（如自建同步），需向使用者提供源码获取方式。
- 商标：「Anki」只出现在描述性副标题与免责声明，不用于产品名、包名、应用商店标题。

## 待验证（下一任务）

1. ~~`init_backend` 的真实入参（`anki.backend.BackendInit`）与错误码约定；`runMethodRaw` 通道。~~
   已完成（T003）：入参是 `BackendInit{preferred_langs, locale_folder_path, server}`，
   错误码映射见 [runtime.md](runtime.md#错误码表)。
2. ~~真机运行时调用：加载 `.so`、开库、返回可读错误。~~ 模拟器（AVD milenote，API 34/arm64）
   上已打通，耗时与用例见 [runtime.md](runtime.md#实测结果)；真机（InkPad X）仍待 T009 前验证。
3. 是否开启 `rustls`（二期同步需要 TLS；一期离线可不开，产物更小）。
4. 多 ABI 支持（`ABI=arm64-v8a,armeabi-v7a`）与 16 KB 页对齐要求（targetSdk 35+ 设备）。
   当前 `.so` 21.8 MB（T002 的 2.5 MB 是只有 `init_backend` 可达时的裁剪结果），
   必须按 ABI 拆分。
5. ~~全量 API 暴露后的产物体积与打包策略。~~ 体积已实测 21.8 MB；打包策略（按 ABI 拆 APK/AAB）
   留到 T009。
