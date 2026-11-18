# rslib 独立构建踩坑记录

记录「脱离上游 ninja 构建系统、把 rslib 单独当 crate 编到 Android」时遇到的全部问题。
上游 anki 仓库默认用 `./ninja` 驱动整套构建（含 protoc 生成、i18n 抽取、descriptor 打包），
单独用 cargo 构建会缺三样东西。

## 前置

- 上游源码：`ankitects/anki`，本次实测 tag **26.09.4**（`rust-version = 1.80`，实际用 Rust 1.100 编过）
- 工具链：NDK 27.3.13750724、cargo-ndk 4.1.2、protoc 36.2
- 源码头文件里的 `ftl/core-repo`、`ftl/qt-repo` 是 **git submodule**，用源码压缩包（tarball）构建时是空的

## 坑 1：i18n 子模块缺失 → 构建脚本 panic

```
thread 'main' panicked at rslib/i18n/gather.rs:78
called `Result::unwrap()` on an `Err` value: Os { code: 2, kind: NotFound }
```

`anki_i18n` 的构建脚本会读 `ftl/core-repo/core` 与 `ftl/qt-repo/desktop`，这两个目录来自 submodule。
解决办法（二选一）：

```sh
# 正规做法：克隆时带上子模块
git clone --depth 1 --branch 26.09.4 --recurse-submodules https://github.com/ankitects/anki.git
```

```sh
# 临时做法（本次验证用）：空目录占位，跳过翻译抽取
mkdir -p ftl/core-repo/core ftl/qt-repo/desktop
```

空目录不会让 `check()` 失败，但产物里不含翻译资源；正式接入必须用真子模块。

## 坑 2：缺 protobuf descriptor 集合

```
error: failed to run custom build command for `anki`
Error: No such file or directory (os error 2)
```

`rslib/build.rs` 读 `anki_descriptors.bin`（地址来自 `anki_proto_gen::descriptors_path()`，
默认在 `OUT_DIR/../../`）。上游由 ninja 用 protoc 生成。单独构建时自己生成并用环境变量指过去：

```sh
protoc -I proto --include_imports --descriptor_set_out="$PWD/anki_descriptors.bin" \
  $(find proto -name '*.proto' | sort)
export DESCRIPTORS_BIN="$PWD/anki_descriptors.bin"
```

`--include_imports` 不能省，否则 descriptor 池里缺依赖类型，`DescriptorPool::decode` 之后的代码生成会失败。

## 坑 3：tokio 的 `io-util` 特性没有打开

```
error[E0432]: unresolved import `tokio::io::AsyncReadExt`
error[E0599]: no method named `write_all` found for struct `tokio::fs::File`
```

上游工作区里 `tokio` 只声明了 `fs / rt-multi-thread / macros / signal`，`io-util` 是靠其它成员
（二进制 crate、dev-dependencies）在 feature 统一时补上的。单独构建时没有这些成员，就缺特性。
在自己的 bridge crate 里补一条依赖即可（feature 统一会传递到 `anki`）：

```toml
[dependencies]
tokio = { version = "1", features = ["io-util", "fs"] }
```

## 坑 4：rustls / native-tls 二选一

`anki` 的 `reqwest` 是 `default-features = false`，TLS 能力要显式选：

```toml
anki = { path = "../anki/rslib", default-features = false, features = ["rustls"] }
```

Android 上选 `rustls`：`native-tls` 会去找 OpenSSL，交叉编译需要额外配置。一期离线功能不用 TLS，
可以先不开特性；二期同步前必须定下来。

## 可用命令

```sh
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.3.13750724"
export DESCRIPTORS_BIN="$PWD/anki_descriptors.bin"

# 主机侧先验证：只编 rslib 本体，不链接 Android
cargo build

# Android arm64
cargo ndk -t arm64-v8a -P 26 build --release
```

`-P 26` 与 app 的 minSdk 对齐（cargo-ndk 默认 21）。

## 与上游的取舍

| 方案 | 优点 | 缺点 |
|---|---|---|
| submodule + 本地 protoc 生成 descriptor（本次验证） | 不改上游代码 | 需要在构建脚本里维护 descriptor/i18n 两步 |
| fork 上游并提交生成物 | 构建简单 | 每次跟上游都要重新生成，diff 噪音大 |
| 用上游 ninja 全量构建 | 与官方一致 | 需要 Python/Node/ninja 全套，产物面向桌面 |

选定第一种；具体脚本化在下一个任务里做。
