@file:Suppress("DEPRECATION")

// ============================================================================
//  composeApp · 应用模块（commonMain + desktopMain）
//  ---------------------------------------------------------------------------
//  「先搭框架，不写业务代码」：这里只声明插件、目标、source set 与依赖。
//  终端（Compose 原生渲染，BossTerm 裁剪版）已拆到 `:modules:terminal`；Markdown 渲染直接依赖
//  `:modules:markdown-one`（submodule）。见 .design/草案/07-decisions.md ADR-044 / ADR-053 / ADR-054。
//
//  为什么 @file:Suppress("DEPRECATION")：
//   Gradle 9.8 + CMP 1.12 起，`compose.material3` / `compose.desktop.currentOs` 等
//   便捷 DSL 被标记为 deprecated，而 Kotlin DSL 脚本把 deprecation 当**编译错误**。
//   这两个必须继续用 DSL：material3 在 CMP 1.12.2 没有同版本的显式坐标
//   （org.jetbrains.compose.material3:material3 稳定版止于 1.9.0），
//   desktop.currentOs 需要由插件按当前 OS 选 skiko 变体。
// ============================================================================

import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// 终端依赖不再走开关：已拆到 `:modules:terminal`（见 ADR-044），默认随应用构建。


// diagram-render（图表引擎）传递依赖 material3 1.10.0-alpha05：版本号高于本仓库的稳定线，
// 会赢下冲突并导致运行时崩溃。`modules/markdown-one` 已在自己的构建里 force 过一次，
// 但那是子项目配置，覆盖不到宿主的解析 —— 这里必须再 force 一次。
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.compose.material3:material3:1.9.0")
}

kotlin {
    // `expect class` 目前仍是 beta：显式接受，否则每个 actual 都会刷一条警告。
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // 目标字节码 25：Kotlin 2.4 的 JvmTarget 最高只到 25（没有 `JVM_27`），所以字节码仍出 25（major 69）；
    // 但**工具链**是 Temurin 27 —— 跑 Gradle、jpackage、jlink 与成品运行时（`rt\`）全用它，
    // 见 `.design/详细设计/工程与代码/04-构建环境与镜像.md`。
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
    }

    sourceSets {
        val desktopMain = getByName("desktopMain")
        val desktopTest = getByName("desktopTest")

        commonMain.dependencies {
            implementation(project(":modules:markdown-one"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatform.settings)

            implementation(libs.kotlin.logging)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            implementation(libs.adaptive.layout)
            implementation(libs.adaptive.navigation)

            implementation(libs.coil.compose)
            implementation(libs.coil.svg)
            implementation(libs.coil.network.ktor3)
        }

        desktopMain.dependencies {
            implementation(project(":modules:terminal"))

            implementation(compose.desktop.currentOs)
            implementation(libs.logback.classic)
            // Windows 原生通知（WinRT toast + 按钮）：dll 在 jar 里，`--enable-native-access=ALL-UNNAMED` 已配。
            implementation(libs.nucleus.notification.windows)

            // ViewModel 的 viewModelScope 依赖 Dispatchers.Main.immediate，desktop 默认没有
            implementation(libs.kotlinx.coroutines.swing)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // 显式坐标，避免 compose.uiTest 的 @ExperimentalComposeLibrary opt-in
            implementation(libs.compose.ui.test)
        }

        desktopTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "dev.shinglem.hermes.resources"
}

/**
 * 本机私有配置：优先 `.local/local.properties`，回退仓库根的 `local.properties`（老布局）。
 * 两者都不入库 —— `.local/` 整体在 `.gitignore` 里，根目录的 `local.properties` 同样忽略。
 * 模板见 `.local/README.md`（也不入库，只在本地存在）。
 */
val localProps: Properties by lazy {
    listOf(rootProject.file(".local/local.properties"), rootProject.file("local.properties"))
        .firstOrNull { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
        ?: Properties()
}

/**
 * JDK 来源：`-Phermes.rt.dir=…` → `.local/local.properties` 的 `hermes.rt.dir`。
 * 不做 toolchain 自动探测：本机的 JDK 不会被 Gradle 识别（installations.paths 只列了 Temurin）。
 *
 * 两处消费者：`compose.desktop.application.javaHome`（开发态 `run`）与 `syncReleaseRt`（成品 `rt/`）。
 * 让两者指向同一份 JDK —— 否则会出现"开发跑 27、成品跑 25"的偏差，
 * 而这类偏差只在成品暴露（native access、字体/IME、native 库），最难查。
 */
val hermesRtDir: String? =
    providers.gradleProperty("hermes.rt.dir").orNull?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("hermes.rt.dir")?.takeIf { it.isNotBlank() }

/**
 * jlink 的输入：含 `jmods/` 的 JDK。
 *
 * **官方 Temurin JDK 自带 `jmods/`**（JRE 包没有），想裁剪就必须拿 JDK 而不是 JRE；
 * 版本还要与 [hermesRtDir] 一致，否则 jlink 出来的是另一份运行时，
 * "和开发态同 JDK" 的保证就断了。
 *
 * 配置顺序：`-Phermes.jmods.dir=…` → `.local/local.properties` 的 `hermes.jmods.dir` →
 * [hermesRtDir] 自己。都没有就退回整目录拷贝。
 */
val hermesJmodsDir: File? = run {
    fun pick(path: String?): File? {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        return if (file.name == "jmods" && file.isDirectory) file else File(file, "jmods").takeIf { it.isDirectory }
    }

    pick(
        providers.gradleProperty("hermes.jmods.dir").orNull
            ?: localProps.getProperty("hermes.jmods.dir"),
    ) ?: pick(hermesRtDir)
}

/**
 * 裁剪运行时保留的模块。
 *
 * 前三项由 `jdeps --print-module-deps` 实测得出（`bin/App.jar` 加 `lib` 下全部 jar 的静态闭包）；
 * 其余是**反射/服务加载**才用到的，jdeps 看不见，少一个就是运行时 `NoClassDefFoundError`：
 *
 * | 模块 | 谁用 |
 * |---|---|
 * | `jdk.unsupported` | `sun.misc.Unsafe`（Kotlin 协程、Netty/CIO 都碰） |
 * | `jdk.unsupported.desktop` | Swing/AWT interop（Compose 的 `ComposeWindow` 就是 JFrame） |
 * | `jdk.crypto.ec` | TLS ECDHE 握手 —— ws/wss 连本机也要 |
 * | `jdk.crypto.mscapi` | Windows 证书存储（系统代理/企业根证书） |
 * | `jdk.charsets` | GBK 等非 UTF-8 编码（后端子进程输出、旧文件名） |
 * | `jdk.localedata` | 中文 locale/日期格式；配合 `--include-locales=en,zh` 只带这两份 |
 *
 * 基线是**官方 Temurin 27**，不再有 JBR 专有模块（浏览器 / OpenGL 那几个随 JBR 一起退役）。
 * 公式引擎 `ratex` 的 native dll 走普通 Maven 依赖（`ratex-native-*`），与 jmods 无关。
 */
val runtimeModules = listOf(
    "java.base",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.naming",
    "java.prefs",
    "jdk.unsupported",
    "jdk.unsupported.desktop",
    "jdk.crypto.ec",
    "jdk.crypto.mscapi",
    "jdk.charsets",
    "jdk.localedata",
)

/**
 * jdeps 静态分析的**已知误报**：静态引用了这些模块，但运行时永远不会加载。
 *
 * CI 拿 `jdeps --print-module-deps` 的结果与 [runtimeModules] 做差集（见
 * `.github/workflows/toolchain-windows.yml`），差集非空就失败 —— 这是防止"新依赖悄悄跑出
 * 裁剪范围"的护栏。护栏只认静态引用，看不出死代码，所以需要在这里显式记账。
 *
 * | 模块 | 来源 | 为什么不是真依赖 |
 * |---|---|---|
 * | `jdk.javadoc` | `icu4j-78.3.jar` 的 `com.ibm.icu.dev.tool.docs.ICUTaglet` | javadoc taglet（生成 API 文档的插件）；本仓库只用 icu4j 的 `BreakIterator` 做字形簇切分 |
 *
 * 实测代价：把 `jdk.javadoc` 加进 [runtimeModules] 会连锁拉进 `jdk.compiler` /
 * `java.compiler` / `jdk.internal.md` / `jdk.internal.opt` / `jdk.zipfs`，
 * rt/ 从 **60.2 MB 涨到 79.0 MB**（16 → 22 模块，232 → 266 文件）。
 * 反证：60.2 MB 的裁剪运行时已端到端跑通成品（窗口 + 后端 + 终端 PTY + 中文渲染）。
 *
 * 加新条目之前先确认"运行时真的碰不到"，并在本表补上来源与理由。
 */
val runtimeModulesJdepsExempt = listOf(
    "jdk.javadoc",
)

/**
 * 把模块清单打给 CI 用（`toolchain-windows.yml` 的 jdeps 校验）。
 *
 * 为什么不让 CI 用正则去 `build.gradle.kts` 里抠：那个正则一旦匹配漂移（比如列表尾部
 * 从 `) + if (…)` 改成纯 `)`），它会吞掉后面几十个字符串字面量，于是"声明集合"巨大、
 * 校验**假通过** —— 护栏失效比没护栏更糟（实测踩到）。
 */
val printRuntimeModules by tasks.registering {
    group = "verification"
    description = "打印 runtimeModules / runtimeModulesJdepsExempt 清单（供 CI 校验模块闭包）"
    doLast {
        println("RUNTIME_MODULES=" + runtimeModules.joinToString(","))
        println("RUNTIME_MODULES_EXEMPT=" + runtimeModulesJdepsExempt.joinToString(","))
    }
}

/** 主类与版本号：`compose.desktop` 与启动器 cfg 共用，避免两处漂移。 */
val appMainClass = "dev.shinglem.hermes.MainKt"

/**
 * 客户端版本：同时决定 jpackage 的 `packageVersion` / `-Djpackage.app-version`、
 * 启动器 cfg，以及**发布资产名**（`hermes-desktop-compose-<appVersion>-win32-x64-*.7z.001`）。
 * 与 Release tag（`v<桌面版本>-<hermes版本>`）是两套数字，互不推导，见 `CHANGELOG.md`。
 */
val appVersion = "0.4.0"

/**
 * 产物名：jpackage 的 `--name` / `packageName`，决定
 * `<appName>.exe`、`app\<appName>.cfg`、`<appName>-console.exe`、`image/<appName>`。
 *
 * ⚠️ 与 `Main.kt` 的 `APP_NAME` 必须一致（自启动要找 `<home>/<appName>.exe`）。
 * **不要**与单实例互斥量 / 计划任务前缀 `HermesCompose-<hash>` 混为一谈 —— 那是内部标识，
 * 改了会让已建的自启动任务变成无法识别的孤儿。
 */
val appName = "hermes-desktop-compose"

/**
 * 启动器图标。默认 `packaging/icon.ico`，可用 `-Phermes.icon=<path>` 覆盖（`.ico` 或 `.png`，
 * 相对路径按项目根解析）。
 *
 * 不传图标时 jpackage 会塞 JDK 自带的咖啡杯 —— 装完在开始菜单和任务栏看到的就是它。
 * 图标源是 `packaging/icon.svg`，改完跑 `pwsh packaging/make-icon.ps1` 重新生成 ico。
 */
val launcherIcon: File = providers.gradleProperty("hermes.icon")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("packaging/icon.ico"))
val launcherIconIsExplicit: Boolean = providers.gradleProperty("hermes.icon").isPresent

compose.desktop {
    application {
        mainClass = appMainClass
        // 开发态与成品同一份 JDK；没配 hermes.rt.dir 时退回 Gradle 自己的 JDK。
        hermesRtDir?.let { javaHome = it }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = appName
            packageVersion = appVersion
            description = "Hermes desktop client (Compose Multiplatform)"
            vendor = "shinglem"
            // compose 插件默认只按 jdeps 静态分析带 7 个模块（java.base/desktop/xml/prefs/logging/
            // datatransfer/crypto.ec），产物一启动就抛 `NoClassDefFoundError:
            // javax/naming/NamingException`（实测 2026-11-04）。显式声明与 `rt/` 同一套，
            // 官方 MSI/EXE 与 `createDistributable` 才能跑。
            modules(*runtimeModules.toTypedArray())
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                // MSI/EXE 安装包走的是 compose 的 nativeDistributions，图标单独指一次。
                iconFile.set(launcherIcon)
            }
        }
    }
}

// ============================================================================
//  release-test/ · 绿色版成品目录
//  ---------------------------------------------------------------------------
//  结构见 .design/详细设计/工程与代码/05-打包与配置.md：
//    release-test/
//      rt/          JDK / JBR（rt\bin\java.exe）
//      config/      config.json / options.json（模板，已存在则不覆盖）
//      lib/         全部运行时依赖 jar
//      bin/App.jar  主 jar
//      hermes-desktop-compose.exe   原生启动器 + app\hermes-desktop-compose.cfg
//
//  成品里**只有**原生启动器：`App.bat` / `App-console.bat` / `hermes-desktop-compose-console.exe`
//  是开发排障用的，留在 `packaging/`，不随成品下发。
//
//  这是**手动出成品**的动作，刻意不进 build / assemble 依赖链。
// ============================================================================

/** 成品根目录。 */
val releaseTestDir = rootProject.layout.projectDirectory.dir("release-test")

/** jlink 产物（中间目录，供 [syncReleaseRt] 拷进成品）。 */
val jlinkRuntimeDir = layout.buildDirectory.dir("jlink-runtime")

/**
 * 用 jmods 的 `jlink` 出一份裁剪运行时。
 *
 * 为什么值得做：整份 JBR 是 **169.6 MB / 470 文件**（含 javac、ct.sym、全部 locale、所有调试信息），
 * 而客户端只需要 13 个模块 —— 裁剪后 **60.2 MB / 232 文件**（-64%）。其中最大的一块
 * `bin/server/jvm.dll` 从 78.6 MB 掉到 14.9 MB：jbrsdk 的 jmods 里是 release 版 JVM，
 * 而 `jbr-*` 运行时包里那份带调试符号。
 *
 * 刻意**保留 keytool**（jlink 默认带），去掉 javac/jshell/jfr/jdb 等开发工具：这是运行时的正常形态。
 */
val jlinkReleaseRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "用 jlink 出裁剪运行时（JDK 有 jmods 用它，没有就走 JEP 493）"

    val jmods = hermesJmodsDir
    // jlink 自己来自 [hermesRtDir]（跑 Gradle 的那份 JDK，CI 里就是刚装的 Temurin）。
    val jlinkExe = hermesRtDir?.let { File(it, "bin/jlink.exe") }

    onlyIf {
        when {
            jlinkExe == null || !jlinkExe.isFile -> throw GradleException(
                "找不到 jlink：${hermesRtDir ?: "(未配置 hermes.rt.dir)"}\\bin\\jlink.exe。\n" +
                    "在 .local/local.properties 里写 hermes.rt.dir=<JDK 目录>，或用 -Phermes.rt.dir=<dir>。\n" +
                    "不配也能出成品：会退回整目录拷贝（体积大 2~3 倍）。",
            )
            else -> true
        }
    }

    // 只比路径 + 模块列表：jmods 有几十 MB，逐文件算 hash 得不偿失。
    inputs.property("jlink", jlinkExe?.absolutePath.orEmpty())
    inputs.property("jmods", jmods?.absolutePath.orEmpty())
    inputs.property("modules", runtimeModules)
    outputs.dir(jlinkRuntimeDir)

    doFirst {
        val out = jlinkRuntimeDir.get().asFile
        out.deleteRecursively()
        // JDK 24+ 的 JEP 493：发行版不带 jmods 时，jlink 直接从 JDK 自己的 lib/modules 裁剪
        // （`--module-path` 都不需要）。Adoptium 的 Temurin 27 Windows zip 就是这种（实测无 jmods）。
        val useJmods = jmods != null
        logger.lifecycle(
            "[release-test] jlink 裁剪运行时：${runtimeModules.size} 个模块 → $out" +
                if (useJmods) "（jmods 模式）" else "（JEP 493，无 jmods）",
        )
        commandLine(
            buildList {
                add(jlinkExe!!.absolutePath)
                if (useJmods) addAll(listOf("--module-path", jmods!!.absolutePath))
                addAll(
                    listOf(
                        "--add-modules", runtimeModules.joinToString(","),
                        "--output", out.absolutePath,
                        // 去掉 class 调试信息、C 头文件、man 页；zip-6 压缩 jimage。
                        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
                        // 只带中英文 locale 数据（jdk.localedata 默认全带，能省 10 MB+）。
                        "--include-locales=en,zh",
                    ),
                )
            },
        )
    }

    doLast {
        val out = jlinkRuntimeDir.get().asFile
        val size = out.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        logger.lifecycle("[release-test] 裁剪运行时：${"%.1f".format(size / 1024.0 / 1024.0)} MB")
    }
}

/**
 * 把运行时放进 `release-test/rt`。
 *
 * 两种模式：
 * - [hermesRtDir] 里有 `bin/jlink.exe` → 依赖 [jlinkReleaseRuntime]，用 jlink 产物**覆盖** `rt/`
 *   （每次重建，保证是裁剪版）。JDK 自带 jmods 就用 jmods，没有就走 JEP 493。
 * - 否则 → 整目录拷贝 JDK（体积大 2~3 倍），只在缺失或 `-Phermes.rt.refresh` 时执行。
 *
 * 后者保留"用户可整体替换 rt/"的行为：CI 的 desktop-release 传的就是已经准备好的 `rt/`。
 */
val syncReleaseRt by tasks.registering {
    group = "distribution"
    description = "准备 release-test/rt（能 jlink 就裁剪，否则整目录拷贝）"

    val jmods = hermesJmodsDir
    val rtSource = hermesRtDir
    val canJlink = rtSource?.let { File(it, "bin/jlink.exe").isFile } == true
    val target = releaseTestDir.dir("rt").asFile
    val refresh = providers.gradleProperty("hermes.rt.refresh").isPresent

    if (canJlink) dependsOn(jlinkReleaseRuntime)

    // 裁剪模式每次都重建（拷贝几十 MB，秒级）；拷贝模式沿用"缺失或 refresh 才动"。
    outputs.upToDateWhen { false }
    onlyIf {
        when {
            canJlink -> true
            rtSource == null -> throw GradleException(
                "未指定 JDK 目录。请在 .local/local.properties 里写 hermes.rt.dir=<path>，" +
                    "或用 -Phermes.rt.dir=<path>。",
            )
            else -> refresh || !File(target, "bin/java.exe").exists()
        }
    }

    doLast {
        target.deleteRecursively()
        target.mkdirs()
        if (canJlink) {
            copy {
                from(jlinkRuntimeDir)
                into(target)
            }
            logger.lifecycle("[release-test] rt/ ← jlink 裁剪产物")
        } else {
            copy {
                from(rtSource!!)
                // `jmods/` 是 jlink 的输入，不是运行时的一部分：源指到完整 SDK 时
                // 别把它拷进成品。
                exclude("jmods/**")
                into(target)
            }
            logger.lifecycle("[release-test] rt/ ← $rtSource（未裁剪：该目录里没有 bin/jlink.exe）")
        }
        val size = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val files = target.walkTopDown().count { it.isFile }
        logger.lifecycle(
            "[release-test] rt/ = ${"%.1f".format(size / 1024.0 / 1024.0)} MB / $files 文件",
        )
    }
}


/** config/ 模板：已存在则不覆盖（用户改过的文件不能被构建重置）；旧版下发的 jvm.args 会被清理。 */
val syncReleaseConfig by tasks.registering {
    group = "distribution"
    description = "写入 release-test/config 模板（已存在则不覆盖，旧版 jvm.args 会被清理）"

    val source = rootProject.file("packaging/config")
    inputs.dir(source)
    outputs.dir(releaseTestDir.dir("config"))
    // 旧版残留的 jvm.args 必须被清掉：只要它还在，就别把任务判成 up-to-date。
    outputs.upToDateWhen { !File(releaseTestDir.dir("config").asFile, "jvm.args").exists() }

    doLast {
        val target = releaseTestDir.dir("config").asFile.apply { mkdirs() }
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            // 只补缺：config.json / options.json 是**用户**的文件，构建不能覆盖。
            if (!dest.exists()) {
                file.copyTo(dest)
                logger.lifecycle("[release-test] 写入模板 config/${file.name}")
            }
        }
        // JVM 参数现在只有一份（app/hermes-desktop-compose.cfg）。旧版下发的 config/jvm.args 会误导用户
        // 以为改它有用 —— 直接清掉；用户自己的 jvm.args.local 不动。
        val legacy = File(target, "jvm.args")
        if (legacy.isFile && legacy.delete()) {
            logger.lifecycle("[release-test] 删除旧版 config/jvm.args（参数已合并进 app/hermes-desktop-compose.cfg）")
        }
    }
}

// ============================================================================
//  release-test/ · 原生启动器（jpackage，只产启动器）
//  ---------------------------------------------------------------------------
//  jpackage 的 app-image 默认会 jlink 一个约 120 MB 的 runtime；给 --runtime-image
//  一个**空目录**后实测它不校验内容、直接拷贝，于是产物只剩 exe + cfg：
//      hermes-desktop-compose.exe  约 490 KB        ← 原生启动器（随 JDK 的 jpackage 生成）
//      app/hermes-desktop-compose.cfg  约 500 B      ← classpath / mainclass / JVM 参数
//  JVM 由 cfg 的 app.runtime 外挂到 rt\，生成器与运行时版本无关
//  （实测 JDK 27 生成的启动器可驱动 JBR 25）。
//
//  结构是 jpackage launcher 的硬规则，改不了：
//      <exe 同级目录>\app\<exe 文件名>.cfg
//  所以 exe 放成品根、cfg 放 app\，其余目录（rt bin lib config logs）用 $ROOTDIR 引用。
// ============================================================================

/** 中间产物（App.jar 输入、空 runtime、console 变体属性、jpackage 输出）。 */
val launcherWorkDir = layout.buildDirectory.dir("jpackage-work")

/** 最终启动器产物：`hermes-desktop-compose.exe` + `app/hermes-desktop-compose.cfg`（console 变体只入库，不进成品）。 */
val releaseLauncherDir = layout.buildDirectory.dir("release-launcher")

/**
 * 启动器的落地目录（`packaging/launcher/`）。**这两个 exe 不入库**，只作为本机构建缓存：
 *
 *  - JBR 的 jpackage 在 `.rsrc` 版本信息里有约 0.5% 非确定字节 —— 同一份 JBR、同一份工程，
 *    两次生成的大小一致但 sha256 不同（实测 2,441 B / 526,848 B 差异集中在资源段）。
 *    入库只会制造无意义的二进制 diff。
 *  - 权威产物由 CI 发布到工具链 Release（tag `toolchain-v<N>`），见
 *    `.github/workflows/toolchain-windows.yml`。
 *
 * 生成/刷新：`./gradlew :composeApp:updateBundledLauncher`（需要 jpackage，通常来自
 * `hermes.rt.dir` 指向的 jbrsdk）。本机生成后会留在这里供后续构建复用，
 * 缺了也没关系 —— [syncReleaseLauncher] 会自动调 jpackage 现生成。
 */
val bundledLauncherDir = rootProject.layout.projectDirectory.dir("packaging/launcher")

/**
 * 是否复用入库的启动器。`-Phermes.launcher.refresh` 强制走 jpackage 重新生成
 * （换 JDK、或想确认生成链路仍然可用时用）。
 *
 * 只看主 exe：console 变体是开发排障用的，成品不需要它，缺了也不该逼着重新生成。
 */
val bundledLauncherExe = bundledLauncherDir.file("$appName.exe").asFile
val useBundledLauncher: Boolean = !providers.gradleProperty("hermes.launcher.refresh").isPresent &&
    bundledLauncherExe.isFile

/**
 * jpackage 必须与 [hermesRtDir] 同一个 JDK 发行版。
 *
 * 启动器 exe 只内嵌 jpackage 的 launcher 模板、与 runtime 解耦，但**不同发行版的模板不同**：
 * 实测同一份工程，OpenJDK 27 生成 7 sections / 512,512 B，JBR 25.0.4.1 生成 6 sections / 526,848 B。
 * 用不同 JDK 生成，就会出现「谁的机器生成就是谁的 exe」，CI 也无法复现入库产物。
 *
 * 顺序：`hermes.rt.dir`（或 `-Phermes.rt.dir`）里自带的 jpackage → Gradle 自己的 JDK（兜底）。
 */
val jpackageFromRt: File? = hermesRtDir?.let { File(it, "bin/jpackage.exe") }?.takeIf { it.isFile }
val jpackageExe: File = jpackageFromRt ?: File(System.getProperty("java.home"), "bin/jpackage.exe")

val prepareReleaseLauncher by tasks.registering {
    group = "distribution"
    description = "准备 jpackage 输入（App.jar + 空 runtime 目录 + console 变体属性）"

    val desktopJar = tasks.named<Jar>("desktopJar")
    dependsOn(desktopJar)

    doLast {
        val root = launcherWorkDir.get().asFile
        // 只重建输入侧；image\ 是 jpackage 的输出，动它会让下游永远重跑。
        File(root, "input").deleteRecursively()
        File(root, "input").mkdirs()
        // 空目录 = 不 jlink、不拷 runtime；jpackage 只把它当目录拷一遍。
        File(root, "empty-runtime").deleteRecursively()
        File(root, "empty-runtime").mkdirs()
        desktopJar.get().archiveFile.get().asFile
            .copyTo(File(root, "input/App.jar"), overwrite = true)
        File(root, "console.properties").writeText("win-console=true\n", Charsets.UTF_8)
    }
}

val jpackageReleaseLauncher by tasks.registering(Exec::class) {
    group = "distribution"
    description = "调 jpackage 生成 app-image（runtime 为空，只取 exe + cfg）"

    dependsOn(prepareReleaseLauncher)

    inputs.file(launcherWorkDir.map { it.file("input/App.jar") })
    inputs.file(launcherWorkDir.map { it.file("console.properties") })
    // 图标换了要重跑（否则 Gradle 认为 image/ 还是最新的，exe 里永远是旧图标）。
    inputs.files(launcherIcon).withPropertyName("launcherIcon").optional()
    outputs.dir(launcherWorkDir.map { it.dir("image") })

    doFirst {
        if (!jpackageExe.isFile) {
            throw GradleException("找不到 jpackage：$jpackageExe（需要 JDK 的 bin\\jpackage.exe）")
        }
        // 只在真的要生成时才提醒来源不同 —— 复用入库启动器时这条没意义。
        if (jpackageFromRt == null && hermesRtDir != null) {
            logger.warn(
                "[release-test] hermes.rt.dir 里没有 bin/jpackage.exe（jbr-* 是纯运行时），" +
                    "启动器改用 Gradle 的 JDK 生成 —— 换台机器就不保证字节一致。",
            )
        }
        val root = launcherWorkDir.get().asFile
        File(root, "image").deleteRecursively()

        if (launcherIconIsExplicit && !launcherIcon.isFile) {
            // 静默回落到 JDK 默认图标是最难发现的错：只有装完看到咖啡杯才知道没生效。
            throw GradleException(
                "hermes.icon 指向的图标不存在：${launcherIcon.absolutePath}\n" +
                    "可用 pwsh packaging/make-icon.ps1 生成 packaging/icon.ico",
            )
        }

        val args = mutableListOf(
            "--type", "app-image",
            "--name", appName,
            "--dest", File(root, "image").absolutePath,
            "--input", File(root, "input").absolutePath,
            "--main-jar", "App.jar",
            "--main-class", appMainClass,
            "--runtime-image", File(root, "empty-runtime").absolutePath,
            "--app-version", appVersion,
            "--vendor", "shinglem",
            "--description", "Hermes desktop client (Compose Multiplatform)",
            // 排障用的控制台变体：只随 updateBundledLauncher 入库到 packaging/launcher
            // （开发时手动拷进成品目录即可），**不进成品**。
            "--add-launcher", "$appName-console=${File(root, "console.properties").absolutePath}",
        )
        if (launcherIcon.isFile) {
            args += listOf("--icon", launcherIcon.absolutePath)
        } else {
            logger.warn(
                "[release-test] 没有启动器图标，exe 会是 JDK 默认图标（packaging/icon.ico 缺失；" +
                    "可用 pwsh packaging/make-icon.ps1 生成）",
            )
        }

        commandLine(listOf(jpackageExe.absolutePath) + args)
    }
}

val syncReleaseLauncher by tasks.registering {
    group = "distribution"
    description = "整理原生启动器：hermes-desktop-compose.exe + app/hermes-desktop-compose.cfg（默认复用 packaging/launcher）"

    // 复用入库启动器时不依赖 jpackage —— 没装 JDK 的机器也能出成品。
    if (!useBundledLauncher) dependsOn(jpackageReleaseLauncher)

    val jvmArgsFile = rootProject.file("packaging/jvm.args")
    inputs.file(jvmArgsFile)
    inputs.file(bundledLauncherExe).withPropertyName("bundledLauncher").optional()
    if (!useBundledLauncher) inputs.dir(launcherWorkDir)
    outputs.dir(releaseLauncherDir)

    doLast {
        val out = releaseLauncherDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        // 两个来源的输出布局不同：jpackage 在 image/<appName>/ 下，入库的是扁平目录。
        val sourceDir = if (useBundledLauncher) {
            bundledLauncherDir.asFile
        } else {
            File(launcherWorkDir.get().asFile, "image/$appName")
        }
        logger.lifecycle(
            "[release-test] 启动器来源：" +
                if (useBundledLauncher) "packaging/launcher（入库）" else "jpackage（本次生成）",
        )

        // cfg 由构建生成：packaging/jvm.args 是 JVM 参数的唯一事实源（成品只有 cfg 一份）。
        // 它随 appVersion / jvm.args 变化，所以**不入库**，每次重新写。
        val cfg = buildString {
            appendLine("[Application]")
            appendLine("app.runtime=\$ROOTDIR\\rt")
            appendLine("app.classpath=\$ROOTDIR\\bin\\App.jar")
            appendLine("app.classpath=\$ROOTDIR\\lib\\*")
            appendLine("app.mainclass=$appMainClass")
            appendLine()
            appendLine("[JavaOptions]")
            appendLine("java-options=-Djpackage.app-version=$appVersion")
            appendLine("java-options=-Dhdc.home=\$ROOTDIR")
            appendLine("java-options=-XX:ErrorFile=\$ROOTDIR\\logs\\hs_err_pid%p.log")
            jvmArgsFile.takeIf { it.isFile }?.readLines(Charsets.UTF_8)
                ?.map(String::trim)
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?.forEach { appendLine("java-options=$it") }
        }

        val appDir = File(out, "app").apply { mkdirs() }
        // 只出主启动器：console 变体（hermes-desktop-compose-console.exe / .cfg）是开发排障用的，不进成品。
        val exe = File(sourceDir, "$appName.exe")
        if (!exe.isFile) {
            throw GradleException(
                "找不到启动器：${exe.absolutePath}\n" +
                    "packaging/launcher 里没有就绪的 exe 时，用 " +
                    "`./gradlew :composeApp:updateBundledLauncher` 生成一份（需要 JDK 的 jpackage.exe）。",
            )
        }
        exe.copyTo(File(out, "$appName.exe"), overwrite = true)
        File(appDir, "$appName.cfg").writeText(cfg, Charsets.UTF_8)

        logger.lifecycle("[release-test] 启动器：$appName.exe + app\\$appName.cfg")
    }
}

/**
 * 把 jpackage 生成的启动器写回 `packaging/launcher/`（入库，供别的机器复用）。
 *
 * 只拷 exe：`app\*.cfg` 是构建产物（随 `appVersion` 与 `packaging/jvm.args` 变），
 * 入库一份旧的 cfg 只会造成两套参数。
 *
 * 两个 exe 都入库：`hermes-desktop-compose.exe` 给成品，`hermes-desktop-compose-console.exe` 留作开发排障
 * （成品里没有它，见 [syncReleaseLauncher]）。
 */
val updateBundledLauncher by tasks.registering(Copy::class) {
    group = "distribution"
    description = "用 jpackage 重新生成启动器并写入 packaging/launcher（改完记得提交）"

    dependsOn(jpackageReleaseLauncher)

    from(launcherWorkDir.map { it.file("image/$appName") }) {
        include("$appName.exe", "$appName-console.exe")
    }
    into(bundledLauncherDir)

    doLast {
        logger.lifecycle(
            "[release-test] 启动器已写入 packaging/launcher（$appName.exe / $appName-console.exe），" +
                "记得 git add 提交",
        )
    }
}

val syncReleaseTest by tasks.registering(Sync::class) {
    group = "distribution"
    description = "组装 release-test 成品目录（原生启动器 + bin/App.jar + lib/*.jar）"

    dependsOn("desktopJar", syncReleaseRt, syncReleaseConfig, syncReleaseLauncher)

    into(releaseTestDir)

    // App.jar 只含本模块的类与 Compose 资源；依赖全部在 lib\，由 app\hermes-desktop-compose.cfg 的
    // app.classpath 通配符加载。
    from(tasks.named("desktopJar")) {
        into("bin")
        rename { "App.jar" }
    }
    from(configurations.named("desktopRuntimeClasspath").map { config ->
        // 同一 jar 可能经由不同坐标/变体解析到同一文件，按路径去重；
        // EXCLUDE 只是兜底，避免"duplicate but no strategy"直接失败。
        config.filter { it.extension == "jar" }.distinct()
    }) {
        into("lib")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // 原生启动器：hermes-desktop-compose.exe 在根，cfg 在 app\（launcher 的定位规则）。
    // 刻意不含 packaging/*.bat 与 hermes-desktop-compose-console.exe —— 那是开发排障用的，不随成品下发。
    from(releaseLauncherDir)

    // Sync 默认会删掉目标里源集合未覆盖的文件 —— rt/ config/ 是外部放入的，必须保留，
    // 否则每次「出成品」都会把用户的 JDK 与配置清掉。
    preserve {
        include("rt/**")
        include("config/**")
        include("hermes/**")
        include("data/**")
        // 启动器的 -XX:ErrorFile 落在 logs\，成品里也会有用户日志。
        include("logs/**")
    }
}

/**
 * 内嵌 payload（bundle）→ `release-test/hermes`。
 *
 * 源默认 `hermes-core/win-bundle`（`hermes pm bundle` 的产物），可用 `-Phermes.payload.dir=<path>` 覆盖。
 * 源不是 sealed payload（缺 `manifest.json`）时**跳过而不是失败**：本机还没构建 payload 时，
 * 仍然要能出客户端壳来联调 remote 模式。
 *
 * 刻意不挂进 [syncReleaseTest]：payload 有数 GB，每次出包都复制一遍代价太大。
 * 出「可独立运行的绿色包」= `./gradlew :composeApp:syncReleaseTest :composeApp:syncReleasePayload`。
 */
val syncReleasePayload by tasks.registering(Sync::class) {
    group = "distribution"
    description = "把 Hermes payload（bundle）同步到 release-test/hermes"

    val source = rootProject.file(
        providers.gradleProperty("hermes.payload.dir").orNull ?: "hermes-core/win-bundle",
    )
    onlyIf {
        val sealed = source.isDirectory && source.resolve("manifest.json").isFile
        if (!sealed) {
            logger.lifecycle(
                "[release-test] 跳过 payload 同步：${source.absolutePath} 不是 sealed payload" +
                    "（缺 manifest.json）；用 -Phermes.payload.dir=<payload 目录> 指定。",
            )
        }
        sealed
    }
    from(source)
    into(releaseTestDir.dir("hermes"))
}

