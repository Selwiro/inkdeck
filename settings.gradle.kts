// Inkdeck（纸屏牌组）构建入口。
//
// 仓库/镜像策略：
// - 默认直连 Google Maven、Maven Central、Gradle Plugin Portal。
// - 中国大陆网络不稳时，设置环境变量 INKDECK_ALIYUN=1，阿里云镜像会被插到官方源之前：
//     INKDECK_ALIYUN=1 ./gradlew assembleDebug
//   镜像缺的 artifact 仍会回落到官方源，因此不必担心镜像不全。

pluginManagement {
    val useAliyun = !System.getenv("INKDECK_ALIYUN").isNullOrBlank()
    repositories {
        if (useAliyun) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useAliyun = !System.getenv("INKDECK_ALIYUN").isNullOrBlank()
    repositories {
        if (useAliyun) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "inkdeck"
include(":app")
