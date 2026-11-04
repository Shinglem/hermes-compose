// ============================================================================
//  hermes-compose · Gradle 设置
//  ---------------------------------------------------------------------------
//  国内网络环境约定（详见 .design/详细设计/工程与代码/04-构建环境与镜像.md）：
//   · 仓库顺序：国内镜像在前，官方源兜底
//   · 腾讯云 maven-public 放第一：实测比阿里云完整
//     （阿里云缺 com.mikepenz:multiplatform-markdown-renderer-code-jvm 的 jar）
//   · Gradle 一旦在某仓库命中 metadata 就不会回退到下一个仓库，
//     所以"最完整"的镜像必须排第一，否则会出现"pom 有、jar 404"
//   · 代理不写死：需要时用 -Dhttps.proxyHost=... 或 gradle.properties 开启
// ============================================================================

rootProject.name = "hermes-compose"

pluginManagement {
    repositories {
        // 插件：阿里云 gradle-plugin 专门代理 Gradle Plugin Portal（实测稳定）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        maven("https://maven.aliyun.com/repository/public")
        // 兜底
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // 禁止各模块自带 repositories，统一在这里管
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // 1) 腾讯云 maven-public：nexus 代理 Central，实测构件最完整
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        // 2) 阿里云 public：速度快，作为腾讯镜像缺失时的补充
        maven("https://maven.aliyun.com/repository/public")
        // 3) 阿里云 google：AndroidX 与 JetBrains 的 androidx 移植件
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
                includeGroupByRegex("org\\.jetbrains\\.androidx\\..*")
            }
        }
        // 4) 官方源兜底
        mavenCentral()
        google()
    }
}

include(":composeApp")
include(":modules:terminal")
// Markdown 渲染实现的来源（git submodule，见 .gitmodules）。
// 它自带完整仓库结构，但主构建只把它当普通子项目：自身 settings 不参与。
include(":modules:markdown-one")

// 原生终端组件（BossTerm 的 Compose 实现，裁剪版，独立仓库 + 独立构建）。
// 通过 composite build 引用，不并入主项目的模块图。
includeBuild("modules/terminal-bossterm") {
    dependencySubstitution {
        substitute(module("ai.rever.bossterm:compose-ui")).using(project(":compose-ui"))
    }
}
