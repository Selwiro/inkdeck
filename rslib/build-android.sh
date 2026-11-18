#!/usr/bin/env bash
# 构建 rslib-bridge 并把 .so 放进 app 的 jniLibs。
#
# 用法：
#   ./rslib/build-android.sh                 # 默认 arm64-v8a、API 26
#   ABI=arm64-v8a,armeabi-v7a ./rslib/build-android.sh
#   ANKI_TAG=26.09.4 ./rslib/build-android.sh
#
# 前置：NDK（ANDROID_NDK_HOME）、Rust、cargo-ndk、protoc。见 docs/build/environment.md。
#
# 上游 anki 源码默认放在 rslib/anki/（不进版本库）：优先 git clone 固定 tag，
# 失败时回落到源码 tarball（本机到 github.com 的 TLS 连接不稳定时很有用）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RSLIB_DIR="$ROOT/rslib"
ANKI_DIR="$RSLIB_DIR/anki"
ANKI_TAG="${ANKI_TAG:-26.09.4}"
ABI="${ABI:-arm64-v8a}"
PLATFORM="${PLATFORM:-26}"

: "${ANDROID_NDK_HOME:?请先导出 ANDROID_NDK_HOME，例如 \$HOME/Library/Android/sdk/ndk/27.3.13750724}"

echo "==> anki tag: $ANKI_TAG"
if [ ! -d "$ANKI_DIR/rslib" ]; then
  echo "==> 拉取 anki 源码到 rslib/anki"
  mkdir -p "$RSLIB_DIR"
  if ! git clone --depth 1 --branch "$ANKI_TAG" --recurse-submodules \
      https://github.com/ankitects/anki.git "$ANKI_DIR"; then
    echo "==> git clone 失败，改用源码 tarball（无子模块，翻译会缺失）"
    rm -rf "$ANKI_DIR"
    tmp="$(mktemp -d)"
    curl -sSL --retry 3 --retry-delay 2 -o "$tmp/anki.tar.gz" \
      "https://codeload.github.com/ankitects/anki/tar.gz/refs/tags/$ANKI_TAG"
    tar xzf "$tmp/anki.tar.gz" -C "$tmp"
    mv "$tmp/anki-$ANKI_TAG" "$ANKI_DIR"
    rm -rf "$tmp"
  fi
fi

# 翻译子模块必须真实存在：早期用空目录占位虽然能让构建通过，但产物不含翻译，
# 也会掩盖克隆失败。这里改为明确校验并失败。
for sub in ftl/core-repo/core ftl/qt-repo/desktop; do
  if [ -z "$(ls -A "$ANKI_DIR/$sub" 2>/dev/null)" ]; then
    echo "错误：子模块 $sub 为空或缺失。" >&2
    echo "      修复：rm -rf \"$ANKI_DIR\" 后重跑本脚本（会带 --recurse-submodules 重新克隆）。" >&2
    exit 1
  fi
done

echo "==> 生成 protobuf descriptor 集合"
DESCRIPTORS_BIN="$RSLIB_DIR/anki_descriptors.bin"
# --include_source_info 是必需的：anki_proto_gen::get_services 会读注释，
# 没有 source info 时会在 unwrap 上 panic。
protoc -I "$ANKI_DIR/proto" --include_imports --include_source_info \
  --descriptor_set_out="$DESCRIPTORS_BIN" \
  $(find "$ANKI_DIR/proto" -name '*.proto' | sort)
export DESCRIPTORS_BIN
export ANKI_TAG

echo "==> cargo ndk 构建（$ABI, API $PLATFORM）"
cd "$RSLIB_DIR/bridge"
targets=()
for abi in $(echo "$ABI" | tr ',' ' '); do targets+=(-t "$abi"); done
cargo ndk "${targets[@]}" -P "$PLATFORM" build --release

echo "==> 拷贝 .so 到 app/src/main/jniLibs"
for abi in $(echo "$ABI" | tr ',' ' '); do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    armeabi-v7a) triple=armv7-linux-androideabi ;;
    x86_64) triple=x86_64-linux-android ;;
    x86) triple=i686-linux-android ;;
    *) echo "未知 ABI: $abi" >&2; exit 1 ;;
  esac
  out="$ROOT/app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  cp "target/$triple/release/librslib_bridge.so" "$out/"
  echo "    $out/librslib_bridge.so ($(du -h "$out/librslib_bridge.so" | cut -f1))"
done

echo "==> 同步 descriptor 到 app assets（Kotlin 侧动态解析 protobuf 用）"
mkdir -p "$ROOT/app/src/main/assets"
cp "$DESCRIPTORS_BIN" "$ROOT/app/src/main/assets/anki_descriptors.bin"

echo "==> 完成"
