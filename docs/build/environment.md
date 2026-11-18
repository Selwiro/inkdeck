# 构建环境

本项目的构建工具链与已验证组合。改动本文件前先在实机上跑一遍验证步骤。

## 已验证组合

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17（Temurin, arm64） | Gradle 9.6 与 AGP 9.5 的最低要求 |
| Gradle | 9.6.0 | 由 wrapper 提供，勿低于 AGP 9.5 的要求 |
| Android Gradle Plugin | 9.5.0 | 9.0 起内置 Kotlin 支持 |
| Kotlin | 2.4.21 | 配合 `org.jetbrains.kotlin.plugin.compose` |
| compileSdk | 37 | 对应已安装的 `platforms;android-37.0` |
| build-tools | 36.0.0 | 必须在 `build.gradle.kts` 显式声明 |
| minSdk | 26 | 覆盖 Android 8.0+ 墨水屏设备 |
| targetSdk | 35 | 待 T001 核对后可能调整 |

验证方式：最小 Compose 工程执行 `assembleDebug` 通过，产出 APK 的 `aapt2 dump badging` 显示
`compileSdkVersion='37'`、`targetSdkVersion='35'`、`minSdkVersion:'26'`，`--warning-mode all` 下无版本相关警告。

## AGP 9 的坑（务必遵守）

1. **不要 apply `org.jetbrains.kotlin.android`**：AGP 9 内置 Kotlin 支持，再加会直接报
   `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`。
2. **Compose 仍需 `org.jetbrains.kotlin.plugin.compose`**，否则报
   `Starting in Kotlin 2.0, the Compose Compiler Gradle plugin is required`。
3. **`buildToolsVersion` 显式写 `36.0.0`**：依赖 AGP 默认值可能指向本机未安装的版本。

最小可用配置：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26; targetSdk = 35 }
    buildFeatures { compose = true }
}
```

## 还需安装（rslib 前置）

```sh
# 1. NDK（rslib 原生库交叉编译）
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.3.13750724"

# 2. Rust 工具链 + Android targets
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
. "$HOME/.cargo/env"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# 3. cargo-ndk（把 NDK 工具链交给 cargo）
cargo install cargo-ndk

# 4. protoc（rslib 的 .proto 编译）
brew install protobuf
```

安装完成后设置环境变量（写进 shell 配置，不要写进仓库文件）：

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
```

`bindgen` 相关依赖若报找不到 `libclang`，再执行 `brew install llvm`。

## 阿里云镜像

直连 Google Maven / Maven Central 不稳定时启用（默认关闭，海外网络与 CI 不要开）：

```sh
INKDECK_ALIYUN=1 ./gradlew assembleDebug
```

镜像配置在根 `settings.gradle.kts` 里：`pluginManagement` 与 `dependencyResolutionManagement` 两处
都会在检测到该环境变量时把阿里云镜像插到官方源之前，镜像缺失的 artifact 仍会回落到官方源。

注意 wrapper 由 `--no-validate-url` 生成（`validateDistributionUrl=false`）：本机到
`services.gradle.org` 的连通性不稳，跳过生成期的 URL 校验；发行版下载后仍按 Gradle 校验压缩包完整性。

## 新增依赖（2026-11-18）

- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0`（Apache-2.0，与已有 lifecycle 同版本）：
  把页面导航、分页位置和导入任务放进 `MainViewModel`，转屏/改系统字号不再重建状态。
  不含新原生库，APK 体积变化可忽略。

## 本机约束

- Gradle 需要写 `~/.gradle`（缓存、原生库、锁）与 `~/.android`（调试签名 keystore）；
  受限沙箱里跑构建会报 `Failed to load native library 'libnative-platform.dylib'` 或
  `Unable to create debug keystore`，属写权限问题而非工具链损坏。
- 模拟器（API 34 arm64）只适合功能冒烟；残影、刷新、对比度必须在墨水屏实机上验证。
- 目标设备通过 `adb devices` 确认为 `device` 状态后再安装。
