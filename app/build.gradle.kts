plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起内置 Kotlin 支持：不要 apply org.jetbrains.kotlin.android，
    // 否则构建会报 "no longer required for Kotlin support since AGP 9.0"。
    // Compose 仍需编译器插件。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ink.inkdeck"
    compileSdk = 37
    // 本机只装了 build-tools 36.0.0，显式声明，避免回落到 AGP 默认的未安装版本。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ink.inkdeck"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 运行时通道的验收靠 instrumentation 测试（模拟器上真加载 .so、真跑一次 protobuf 往返）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名：release 的密钥库与口令只从环境变量或用户级 gradle 属性（~/.gradle/gradle.properties）
    // 读取，绝不写进仓库。本地没有配置 release 密钥时回落到 debug 签名，保证 assembleRelease 可验证。
    fun secret(envName: String, propertyName: String): String? =
        System.getenv(envName) ?: providers.gradleProperty(propertyName).orNull

    val releaseKeystore = secret("INKDECK_RELEASE_KEYSTORE", "inkdeck.releaseKeystore")
    val releaseStorePassword = secret("INKDECK_RELEASE_STORE_PASSWORD", "inkdeck.releaseStorePassword")
    val releaseKeyAlias = secret("INKDECK_RELEASE_KEY_ALIAS", "inkdeck.releaseKeyAlias")
    val releaseKeyPassword = secret("INKDECK_RELEASE_KEY_PASSWORD", "inkdeck.releaseKeyPassword")

    signingConfigs {
        getByName("debug") {
            providers.gradleProperty("inkdeck.debugKeystore").orNull?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 26 起 v2 足够；额外开 v3 以便将来密钥轮换（v1 已无必要）。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 日常调试只装 arm64（模拟器/主力机），避免两个 ABI 的 .so 让 debug 包涨到 48MB；
            // 发布包保留两个 ABI，见 docs/build/packaging.md。
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        release {
            // R8 打开：JNI 类与异常类在 proguard-rules.pro 里显式 keep。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    // ABI split 默认关闭：日常调试/测试仍装同一个 universal 包。
    // 打发布矩阵时用 -Pinkdeck.abiSplits=true 打开（arm64-v8a + armeabi-v7a + universal）。
    splits {
        abi {
            isEnable = providers.gradleProperty("inkdeck.abiSplits").orNull == "true"
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    // 动态解析 anki 的 descriptor 集合：只读 protobuf，不生成代码。
    implementation(libs.protobuf.java)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
