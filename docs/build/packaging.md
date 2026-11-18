# 打包、ABI 与发布准备

实测：2026-11-18，本机（macOS arm64）、NDK 27.3、build-tools 36.0.0、AGP 9.5、R8。

## 构建矩阵与体积

| 产物 | 命令 | 体积 | 说明 |
|---|---|---|---|
| debug（arm64-v8a） | `./gradlew :app:assembleDebug` | **31 MB** | 未混淆；debug 只装 arm64，避免调试包涨到 48 MB |
| release（universal） | `./gradlew :app:assembleRelease` | **39 MB** | R8 + 资源压缩，含 arm64 + armv7 |
| release（arm64-v8a） | `-Pinkdeck.abiSplits=true :app:assembleRelease` | **22 MB** | 单 ABI，主力机型 |
| release（armeabi-v7a） | 同上 | **18 MB** | 老机型 / 后续 Kindle 成果 |

`.so` 本体：arm64-v8a 21.8 MB、armeabi-v7a 18.3 MB（全量 API 暴露的代价，见
[../rslib/integration.md](../rslib/integration.md) 的取舍记录）。

**取舍结论**：一期按「universal + ABI split 都能出」交付。多 ABI 保留 armeabi-v7a——
体积代价 18 MB，但覆盖 Kindle / 老设备这条成果二路线；正式上架时优先用 split（用户只下一次
ABI），商店分发用 AAB 时 AGP 会自动按 ABI 拆。

## 16 KB 页对齐

Android 15 起 arm64 设备可能使用 16 KB 页，.so 的 LOAD 段对齐必须是 0x4000。

```sh
READELF=$(ls $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-*/bin/llvm-readelf | head -1)
"$READELF" -l app/src/main/jniLibs/arm64-v8a/librslib_bridge.so | grep LOAD
```

实测（cargo-ndk 4.1 + NDK r27 默认开启 16 KB 对齐）：

```
LOAD ... R   0x4000
LOAD ... R E 0x4000
LOAD ... RW  0x4000
LOAD ... RW  0x4000
```

armeabi-v7a 是 32 位，仍为 `0x1000`（4 KB），符合预期——16 KB 页要求只针对 arm64。

APK 侧再校验一次（要求 .so 不压缩且 16 KB 对齐）：

```sh
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

`verification successful`（universal 与 arm64 split 包都通过）。

## R8 / ProGuard

release 打开 `isMinifyEnabled = true`、`isShrinkResources = true`，keep 规则在
`app/proguard-rules.pro`：

- `-keep class ink.inkdeck.rslib.NativeBridge { *; }`：JNI 按类名/方法名查找，改名即 `UnsatisfiedLinkError`
- `-keep class ink.inkdeck.rslib.RslibException { *; }`：Rust 侧用 `throw_new` 按类名构造异常
- `-keepclasseswithmembernames class * { native <methods>; }`：兜底
- protobuf 的 `DescriptorProtos` / `Descriptors` / `DynamicMessage` 保留（动态 descriptor 反射）

**验证方法**：release 包装到模拟器上跑真实流程——启动后牌组列表正常显示、导入 27 张笔记、
连续评 2 张卡，logcat 无 `UnsatisfiedLinkError` / `FATAL EXCEPTION`。

## 签名

### 本机现状（2026-11-18 已配置）

| 项 | 值 |
|---|---|
| 密钥库 | `~/.inkdeck/inkdeck-release.jks`（PKCS12，RSA 4096，有效期 10000 天，别名 `inkdeck`） |
| 库/别名口令 | `~/.inkdeck/keystore.properties`（权限 600），同一份值也写进 `~/.gradle/gradle.properties` |
| 证书 DN | `CN=Inkdeck, OU=Inkdeck, O=Inkdeck, L=Shanghai, ST=Shanghai, C=CN` |
| 证书 SHA-256 | `33:26:C2:21:67:3D:A5:1A:08:3E:51:DB:4A:07:B0:EC:F4:7B:4D:3E:F3:F6:72:B6:D3:72:24:EB:F2:81:8E:9B` |
| 签名方案 | v2 + v3（`enableV1Signing = false`，v1 对 minSdk 26 无意义；v3 留作将来密钥轮换） |

Gradle 从**环境变量或用户级 gradle 属性**读取，两者都不进仓库：

| 变量 / 属性 | 用途 |
|---|---|
| `INKDECK_RELEASE_KEYSTORE` / `inkdeck.releaseKeystore` | 密钥库路径 |
| `INKDECK_RELEASE_STORE_PASSWORD` / `inkdeck.releaseStorePassword` | 密钥库口令 |
| `INKDECK_RELEASE_KEY_ALIAS` / `inkdeck.releaseKeyAlias` | 别名 |
| `INKDECK_RELEASE_KEY_PASSWORD` / `inkdeck.releaseKeyPassword` | 别名口令 |

未配置时回退到 debug 签名（`~/.android/debug.keystore`），保证 `assembleRelease` 随时可验证；
`storePassword = "android"` 只是 Android 调试密钥的公共口令，不是秘密。

### 从零重建密钥库

```sh
mkdir -p ~/.inkdeck && chmod 700 ~/.inkdeck
PASS=$(openssl rand -hex 24)
keytool -genkeypair -v -keystore ~/.inkdeck/inkdeck-release.jks -storetype PKCS12 \
  -alias inkdeck -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Inkdeck, OU=Inkdeck, O=Inkdeck, L=Shanghai, ST=Shanghai, C=CN"
# 口令记录到 ~/.inkdeck/keystore.properties，并同步到 ~/.gradle/gradle.properties 的 inkdeck.release* 四行
```

**注意**：换密钥库等于换签名，已安装的旧包必须先卸载；上架后换签名需要走商店的密钥轮换流程。
密钥库和口令务必备份到密码管理器——丢了就没法给已发布应用发更新。

### 校验

```sh
./gradlew :app:assembleRelease                 # 或加 -Pinkdeck.abiSplits=true
$ANDROID_HOME/build-tools/36.0.0/apksigner verify -v --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

期望输出：`Verifies`、v2 = true、v3 = true、证书 DN 为 `CN=Inkdeck`。
2026-11-18 实测：release 包装机启动后牌组列表正常显示、导入 27 张笔记、复习 1 张，无 FATAL。
2026-11-18 InkPad X 实测：`app-armeabi-v7a-release.apk` 在 Android 8.1 安装运行，导入/复习/音频正常。

### 仓库自检

```sh
git ls-files | grep -iE "keystore|\.jks|\.p12|local\.properties"   # 应为空
grep -rn "/Users/" --include=*.kt --include=*.kts --include=*.sh .   # 应为空
```

`.gitignore` 已包含 `*.jks`、`*.keystore`；密钥库本体在 `~/.inkdeck/`，仓库里只有读取变量的代码。

## 图标

应用图标来自 `work/design/inkdeck-icon.svg`，以纯黑线条勾出两张闪卡，卡面留白，方形白底铺满画布。

- `res/drawable/ic_launcher.xml`：整枚图标（含 `#FFFFFF` 底）的 VectorDrawable，兼容旧启动器。
- `res/drawable/ic_launcher_foreground.xml` + `res/values/colors.xml` 的 `ink_icon_background`：
  自适应图标的前景/背景层。
- `res/mipmap-anydpi-v26/ic_launcher.xml`：`<adaptive-icon>`，`AndroidManifest` 的 `android:icon`
  指向 `@mipmap/ic_launcher`。

SVG 直接按路径转 VectorDrawable（`rect` → 矩形路径，`path` 的 `fill/stroke/stroke-width/stroke-linecap/stroke-linejoin`
→ 对应属性；负号前后补空格避免紧凑写法被解析器误读）。

**图案占比**：参考墨案桌面其它图标（浏览器/邮件/文件管理器等）图案占图标框约
71%–83% 宽，本图标把双卡图案放在原 65% 与放大 100% 的中间：**82.5%**。SVG 用
`<g transform="translate(64 64) scale(0.825) translate(-64 -64)">`，Android 用
`<group android:pivotX="64" android:pivotY="64" android:scaleX="0.825" android:scaleY="0.825">`；
满幅白底覆盖整个画布。图案约占框宽 63%，介于旧版 49% 与放大量 77% 之间。
线条粗细锁定为缩放后 **1.5 单位**（与旧版 0.65 的视觉线宽一致），不会随图案放大变粗。
1024 px PNG 由 SVG 导出为不透明的 1 位黑白图。识别度验证见[启动图标](../eink/launcher-icon.md)。

## AGPL-3.0 与源码可得性

- 本仓库整体 AGPL-3.0；分发的 APK 内含 rslib（AGPL）与自写 bridge，发布时必须同时提供：
  本仓库源码 + `rslib/build-android.sh` 取到的上游 anki 固定 tag（26.09.4）+ bridge 源码。
- 发布页需给出源码获取方式（仓库地址或与 APK 同版本的源码包）。
- 若将来提供网络服务（自建同步），AGPL 第 13 条要求向使用者提供源码获取方式。
- 商标：产品名、包名、商店标题不含 `Anki` / `E Ink`；「Anki」只出现在描述性副标题与免责声明。

## 发布检查清单

- [ ] `./rslib/build-android.sh`（`ABI=arm64-v8a,armeabi-v7a`）产物已更新，两个 ABI 都存在
- [ ] `llvm-readelf -l` 确认 arm64 的 LOAD 对齐为 `0x4000`
- [ ] `zipalign -c -P 16 -v 4` 对每个发布 APK 通过
- [ ] `./gradlew :app:assembleRelease`（可加 `-Pinkdeck.abiSplits=true`）成功，R8 无 keep 缺失
- [ ] release 包在模拟器/真机启动、导入 .apkg、复习评分、统计页均正常
- [ ] 签名来自环境变量；`git status` 干净，无密钥/本机路径
- [ ] docs 里记录的体积、耗时与实际产物一致
- [ ] 发布页附源码获取方式与 AGPL 声明；商店文案不含他人商标
- [ ] 真机项（T010）：墨水屏残影、硬件翻页键、音频出声

## 已知限制

1. 未做 AAB（`bundleRelease`）实测；商店分发前需补一次。
2. .so 体积 20 MB 上下，主要来自全量 API 暴露；若要瘦身需按服务裁剪上游（成本高，暂不做）。
3. 未开启 `abiFilters` 之外的 CPU 特性优化（如 armv8.2），保持兼容优先。
