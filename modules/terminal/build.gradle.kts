@file:Suppress("DEPRECATION")

// ============================================================================
//  terminal · 终端子系统（Compose 原生渲染宿主，BossTerm 裁剪版）
//  ---------------------------------------------------------------------------
//  为什么单独成模块（见 .design/草案/07-decisions.md ADR-044 / ADR-054）：
//   · PTY、终端模拟内核与第三方依赖只在这里出现，composeApp 的 commonMain 永远碰不到；
//   · 终端文本缓冲的读取接口（选中文本 / 尾部回读）收敛在本模块对外暴露。
//
//  依赖方向（禁止反向）：composeApp → modules
// ============================================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    // 原生终端组件（BossTerm 裁剪版，经 composite build 引入，见 settings.gradle.kts）。
    // PTY 由它内部的 pty4j 负责，本模块不再直接依赖 pty4j / JediTerm。
    implementation("ai.rever.bossterm:compose-ui")

    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlin.logging)

    testImplementation(kotlin("test"))
}
