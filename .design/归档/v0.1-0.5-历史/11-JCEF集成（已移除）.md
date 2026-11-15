# 11 · JCEF 集成（已移除 · 历史决策）

> ⚠️ **本文件是历史决策记录：JCEF 已于 v0.4.1 整体移除。**
> 原始 HTML 块改由 markdown-one 的 `HtmlNativeAdapter` 原生渲染（ksoup 白名单 + Compose 块树），
> 图表走 `diagram-render`、公式走 `ratex`，都不再需要内嵌浏览器。
> 主仓库已删除 `jcefmaven` / `jcef-natives` 依赖、`extractCefBundle` 任务与打包/CI 里的 `cef/` 处理；
> markdown-one 删除了 `web/` 8 个实现文件、`WebFallback`、`MarkdownConfig` 的 5 个相关字段与
> `AdapterKind.Jcef`。下面内容仅用于理解"曾经怎么做的"与"为什么放弃"。

> **回答"怎么做"**：Temurin 没有 `jcef` 模块，怎么让 markdown-one 的 JCEF 回落渲染跑起来。
> 结论先行：**Java 侧走 Maven 依赖，native 走另一个 Maven 依赖 + 构建期解压，运行时目录随便放**
> —— 既不要求 `java.home` 可写，也不要求 `java.library.path` 指向它。
>
> 相关：[`10-Windows触屏输入.md`](10-Windows触屏输入.md)（为什么从 JBR 换到 Temurin）、
> [`02-依赖与版本.md`](02-依赖与版本.md)、[`05-打包与配置.md`](05-打包与配置.md)。

---

## §1 为什么不能只加一个 Gradle 依赖

JCEF 是**三层**，不是一层：

| 层 | 内容 | 依赖形式 |
|---|---|---|
| Java API | `org.cef.*`（`CefApp` / `CefClient` / `CefBrowser`…） | ✅ jar 就够 |
| 加载桥 | `jcef.dll` | ❌ 由 CEF 自己的 `SystemBootstrap.loadLibrary` 加载 |
| Chromium 本体 | `libcef.dll`（222 MB）、`jcef_helper.exe`、`chrome_elf.dll`、`resources.pak`、`locales/`、`*.dat`、swiftshader/vulkan | ❌ 必须是磁盘上的真实文件 |

第三层为什么不能塞进 classpath：

- `jcef_helper.exe` 要能被 `CreateProcess` 拉起（GPU / renderer / utility 子进程宿主）——
  jar 里的条目不是可执行文件；
- `libcef.dll` 是被 `jcef.dll` **静态导入**的，由 Windows 加载器解析，跟 classpath 无关；
- `resources.pak` / `locales/` 是**目录布局**契约（`CefSettings.resources_dir_path` /
  `locales_dir_path`）。

## §2 两个 Maven 坐标（版本必须配套）

```toml
# gradle/libs.versions.toml
jcefmaven   = "135.0.20"
jcef-natives = "jcef-ca49ada+cef-135.0.20+ge7de5c3+chromium-135.0.7049.85"

jcefmaven            = { module = "me.friwi:jcefmaven", version.ref = "jcefmaven" }               # → 传递 jcef-api（Java API）
jcef-natives-windows = { module = "me.friwi:jcef-natives-windows-amd64", version.ref = "jcef-natives" }  # → jar 里包着一份 tar.gz
```

- 核对时间 2026-11-04，来源 `repo1.maven.org`（`me/friwi/jcef-natives-windows-amd64/maven-metadata.xml` 里有上面那串版本）。
- **`jcef-api` 与 native 是同一个 CEF 构建**（`jcef-ca49ada+cef-135.0.20+…`）。混版本是 JNI 崩溃级别的错误，
  改一个必须同时改另一个。
- 注意 `jcef-natives-*` 的 `<latest>` 是 `cef-132.3.1`（更旧），**不要用 `latest`**，要写死上面那串。
- native jar 里只有一个 `*.tar.gz` + `META-INF/`，所以构建期要**两层解压**（见 §4）。

## §3 运行时目录约定

| 场景 | 目录 | 谁决定 |
|---|---|---|
| 开发态 `gradlew run` | `composeApp/build/cef/bin` | `extractCefBundle` 任务 + `-Dmarkdown.cef.dir` |
| 成品（绿色版 / 安装包） | `<安装目录>/cef` | `syncReleaseTest` 拷过去 + 启动时探测 |
| JBR with-JCEF（旧路径/兼容） | `java.home/bin` | `CefNativeLocator` 的最后兜底 |

`markdown-one` 侧的解析顺序（`CefNativeLocator`）：

1. `MarkdownConfig.cefNativeDir`（显式，最高优先）
2. `-Dmarkdown.cef.dir=<dir>`
3. 环境变量 `MARKDOWN_CEF_DIR`
4. `java.home/bin`（仅作兼容兜底）

**前三项都与 JVM 安装目录无关** —— CEF 可以放在应用自己的目录里，`java.home` 只读也没问题。

## §4 构建期：从 Maven 依赖解压出 native

`composeApp/build.gradle.kts`：

```kotlin
val cefNatives: Configuration by configurations.creating { isTransitive = false; isCanBeConsumed = false }
dependencies { cefNatives(libs.jcef.natives.windows) }

val extractCefBundle = tasks.register("extractCefBundle") {
    inputs.files(cefNatives)
    outputs.dir(cefBinDir)                 // build/cef/bin
    doLast {
        copy { from(zipTree(jar)) { include("*.tar.gz") } into(stage) }   // 第一层：jar
        copy { from(tarTree(resources.gzip(bundle))) into(target) }        // 第二层：tar.gz
    }
}
```

`run` 任务 `dependsOn(extractCefBundle)`，并在 `doFirst` 里 `systemProperty("markdown.cef.dir", …)`。
`syncReleaseTest` 把 `cefBinDir` 拷进成品的 `cef/`。

> **不要**再写"下载 tar.gz 的脚本"：`jcef-natives-windows-amd64` 在 Maven Central 上就有，
> 走依赖解析能复用镜像与缓存，也不需要代理绕 GitHub Releases（早先那版脚本的 URL 命名还是错的，已删）。

解压后 `build/cef/bin` 约 **310 MB / 21 项**（`libcef.dll` 222.7 MB 最大）。

## §5 运行时加载链（实测，java-cef 135）

```
CefApp.startup(args)          ← Windows 上是空操作（直接 return true），只在 Linux/macOS 载库
CefApp.getInstance(args, settings)
   └─ 构造器里 SystemBootstrap.loadLibrary(jawt) → (chrome_elf) → (libcef) → (jcef)
   └─ 结束后 setState(NEW)     ← 此时 CEF **还没初始化**
CefApp.createClient()          ← state == NEW 时才真正跑 N_PreInitialize + N_Initialize
   └─ setState(INITIALIZED)    ← 之后才能建浏览器
```

三条由此而来、踩过的结论：

1. **只调 `getInstance()` 会永远停在 `NEW`**（轮询 20 s 也等不到）。必须 `createClient()`。
   → `CefBootstrap.ensureInitializing()`：state 还是 `NEW` 才补一次 `createClient()`
   （JBR 的补丁版可能已由 `startup` 推进，所以先看状态；多一个空闲 client 无害）。
2. **`no_sandbox` 字段是 JBR 打的补丁**，`jcefmaven` 的 `jcef-api` 没有。
   → 用 `getInstance(String[]{"--no-sandbox"}, settings)` 这个重载传命令行开关，
   字段设置只作 `runCatching` 兜底。
3. **`CefApp` 是进程级单例**：目录、`CefSettings`、初始化都只生效一次。
   `MarkdownConfig.cefNativeDir` 只在首次初始化之前有意义。

## §6 为什么能脱离 `java.library.path`

`SystemBootstrap.loadLibrary(name)` 走的是 `ClassLoader.loadLibrary`：**先在 `java.library.path`
里按名字找文件，找不到直接抛 `UnsatisfiedLinkError`**（不会因为已经 `System.load` 过就放过）。
所以：

| 尝试 | 结果 |
|---|---|
| `System.load(绝对路径)` 预加载 libcef / jcef | ❌ `no chrome_elf in java.library.path` |
| `SetDllDirectoryW(dir)`（FFM）后再 `System.load` | ❌ 同样失败（JVM 用 `LoadLibraryEx` 的 `LOAD_LIBRARY_SEARCH_*`，与它互斥） |
| `LoadLibraryExW(libcef, 0, LOAD_WITH_ALTERED_SEARCH_PATH)` | ❌ 报 "Can't find dependent libraries" |
| `AddDllDirectory` + `System.load(jcef)` | ❌ 仍缺 `jawt.dll`（`jcef.dll` 静态导入它） |
| **`SystemBootstrap.setLoader(自定义 Loader)`** | ✅ CEF 按我们给的绝对路径加载四个库 |

最后那条是正解：`org.cef.SystemBootstrap` 有一个可替换的 `Loader` 接口（单方法
`loadLibrary(String)`），`CefBootstrap.installAbsoluteLoader()` 用 `java.lang.reflect.Proxy`
注入一个实现，按 `System.mapLibraryName(name)` 在 CEF 目录里找，找不到再回退 `java.home/bin`
（`jawt.dll` 在那儿）。**这样 `java.library.path` 完全不用动，CEF 目录也不用放进 `java.home`。**

兜底路径（`SystemBootstrap` 不可用时）按依赖顺序 `System.load`：
`jawt` → `chrome_elf` → `libcef` → `jcef`（`CefNativeLocator.preloadOrder`）。
但这条要求 `java.library.path` 含该目录，所以只是降级方案。

## §7 三个"不设就不出图"的 CefSettings

| 字段 | 不设的后果 |
|---|---|
| `browser_subprocess_path` = `<cef>/jcef_helper.exe` | CEF 把 `--type=gpu-process` 丢给 `java.exe`，子进程 "Unrecognized option" 后退出，GPU/网络进程全崩 |
| `resources_dir_path` = `<cef>` | 找不到 `resources.pak`，白屏 |
| `locales_dir_path` = `<cef>/locales` | 缺 locale 资源，初始化报错 |

外加 `windowless_rendering_enabled = true`（OSR 全局开关，必须在 `N_Initialize` 之前）与
`cache_path` 按 PID 隔离（两个进程共用同一 user data 目录时，后启动的会把请求转给已有实例，
自己永远停在 `NEW`）。

`jvm.args` 里必须有两个 `--add-opens`（JCEF 用 AWT peer）：
`java.desktop/sun.awt`、`java.desktop/java.awt.peer`。

## §8 验证

```powershell
./gradlew :composeApp:extractCefBundle        # → composeApp/build/cef/bin（310 MB）

$env:MARKDOWN_CEF_DIR = 'composeApp/build/cef/bin'
./gradlew :composeApp:desktopTest --tests '*CefBootstrapSmokeTest*'
```

`CefBootstrapSmokeTest` 断言 `CefApp.getState() == INITIALIZED`，覆盖的正是
「目录不在 `java.home` 下、`java.library.path` 里也没有它」这条链。没设 `MARKDOWN_CEF_DIR`
时它直接跳过（不会去下载 300 MB）。

## §9 启用回落渲染

库的默认是 `WebFallback.Source`（HTML 块显示源码）。要真正渲染需要**项目侧显式开**：

```kotlin
MarkdownConfig(
    webFallback = WebFallback.Jcef,
    webFallbackHeight = 320.dp,
    cefNativeDir = <CEF 目录>,      // 或走 -Dmarkdown.cef.dir / 环境变量
    webDocument = { block -> … },   // 为块生成 HTML；返回 null = 该块仍走源码
    // 数学 / mermaid 需要 KaTeX / mermaid.js 资源，库不打包脚本
)
```

`AGENTS.md` 硬约束 8 的口径不变：**mermaid 走纯 Kotlin 引擎、公式走 latex-renderer**，
JCEF 只是原始 HTML 的**可选**回落，不是默认路径。

## §10 坑位清单（现象 / 原因 / 应对）

| 现象 | 原因 | 应对 |
|---|---|---|
| `state` 永远 `NEW` | `getInstance` 不触发初始化 | `createClient()`（§5） |
| `no chrome_elf in java.library.path` | `loadLibrary` 按名字找文件，不看已加载模块 | 换 `SystemBootstrap.setLoader`（§6） |
| `Can't find dependent libraries` 且不说缺谁 | `libcef` 缺 `chrome_elf`，或 `jcef` 缺 `jawt` | 用 pefile 看导入表；或 `preloadOrder` 按序加载 |
| 进程退出后 CEF 线程还在 | CEF 的线程不是 daemon | 应用退出走 `CefApp.dispose()`；测试里也要 dispose |
| `Unrecognized option: --type=gpu-process`（子进程） | 没设 `browser_subprocess_path` | §7 |
| `Opening in existing browser session.` | `cache_path` 共用 | 按 PID 隔离（§7） |
| 换 JDK / 换 CEF 版本后崩溃 | `jcef-api` 与 native 不配套 | 两个版本号一起改（§2） |
