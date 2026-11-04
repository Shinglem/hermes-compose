# hermes-compose

用 **Kotlin + Compose Multiplatform** 重写的 Hermes 桌面客户端。后端完全不动，只换前端外壳。

> **当前状态：v0.3.0 已验收，待发布。**
> 已落地：会话列表 / 流式对话 / composer / 能力三页 / 应用设置 / 打包与启动器 / 单实例与托盘 /
> 设置中心（配置档案 / 模型 / 安全 / 提供方）/ 终端 / 对话深度（分页、导出、分支、纠偏、子代理面板、命令面板、通知）。
> 终端自 v0.3.0 起是 **Compose 原生渲染**（自维护的 BossTerm 裁剪版），模块化为
> `modules/terminal` + `modules/terminal-bossterm` + `modules/markdown-one`。
> 上一个里程碑 tag 是 `v0.2.0`；变更明细见 [CHANGELOG.md](CHANGELOG.md)，分期与剩余缺口见
> [.design/草案/06-roadmap.md](.design/草案/06-roadmap.md)。
>
> 已知缺口（未接线 / 口径限定）：文件拖入终端、终端文本回放、消息附件图片缩略、终端「回看行数」只对新标签生效。

---

## 这是什么

Hermes 官方桌面端是 Electron + React 应用（生产代码约 45 万行）。本项目是它的**并行替代前端**：

- ✅ 复用同一套后端（gateway / dashboard，`127.0.0.1:9119`）与公开契约
- ✅ 原生 Compose 渲染，Windows 优先
- ❌ 不 fork、不 vendored 官方代码
- ❌ 不追求功能对等 —— 只做「会话主窗口 + 能力设置 + 终端」

## 功能范围

| 做 | 不做 |
|---|---|
| 会话列表（置顶 / 时间分组 / 搜索） | 前端插件生态 |
| 流式 Markdown 对话 | 文件树 / 内置编辑器（跳转 VSCode / IDEA） |
| 输入框（chip / @ 补全 / 粘贴图片） | Bot Mode / 群聊（后续独立窗口） |
| 能力页（技能与工具 / 消息平台 / 定时任务） | 远程屏幕（noVNC） |
| 内嵌终端（Compose 原生渲染，多标签 / 重启恢复 / agent 共享） | 多语言（只做中文） |
| 设置（模型 / 外链编辑器 / 代理） | 视觉 1:1 复刻（用 M3 原生） |

## 目录

```
├── AGENTS.md               # AI agent 的工作约定（先读这个）
├── .design/                # 设计事实源（草案 / 详细设计）
├── .ai/skill/              # 按需加载的技能包（Copilot 经 .github/skills 读取）
├── .github/                # Copilot 接入（规则指针 + 技能链接）+ workflows（CI 打包）
├── .gitattributes          # 行尾策略（文本 LF，*.bat CRLF）
├── settings.gradle.kts     # 仓库与镜像（国内镜像优先，见详细设计 04）
├── build.gradle.kts        # 根脚本（只声明插件）
├── gradle.properties       # 代理开关、终端依赖开关
├── gradle/libs.versions.toml   # 版本目录（唯一版本来源）
├── gradlew / gradlew.bat   # Wrapper（发行版走腾讯镜像）
├── composeApp/             # 应用模块（commonMain / desktopMain）
└── modules/                # 旁路模块（依赖方向：composeApp → modules）
    ├── terminal/           # 终端宿主：BossTerm 裁剪版（Compose 原生渲染，JVM-only）
    ├── terminal-bossterm/  # git submodule：BossTerm 的裁剪 fork（composite build 接入）
    └── markdown-one/       # git submodule：Markdown 渲染库本体（Apache-2.0）
```

> **克隆后要先拉子模块**：`git submodule update --init --recursive`。
> 终端的渲染实现（Compose Canvas 自绘 / PTY / IME）在 `modules/terminal-bossterm` —— 它是
> [Shinglem/BossTerm](https://github.com/Shinglem/BossTerm) 的 `hermes` 分支，**不要在主仓库里改它**。
> Markdown 的渲染实现（解析 / 块路由 / 高亮 / 图表 / 视口）全在 `modules/markdown-one`，
> 它是独立开源库 [Shinglem/markdown-one](https://github.com/Shinglem/markdown-one)。

## 文档索引

| 文档 | 内容 |
|---|---|
| [.design/草案/00-overview.md](.design/草案/00-overview.md) | 项目目标、范围、非目标 |
| [.design/草案/01-feasibility.md](.design/草案/01-feasibility.md) | 可行性评估与成本基线 |
| [.design/草案/02-selection.md](.design/草案/02-selection.md) | 技术选型（含坐标与坑） |
| [.design/草案/03-architecture.md](.design/草案/03-architecture.md) | 分层架构与数据层 |
| [.design/草案/04-ui-spec.md](.design/草案/04-ui-spec.md) | 布局与交互规格 |
| [.design/草案/05-acceptance.md](.design/草案/05-acceptance.md) | 验收口径 |
| [.design/草案/06-roadmap.md](.design/草案/06-roadmap.md) | 分期计划 |
| [.design/草案/07-decisions.md](.design/草案/07-decisions.md) | 决策记录（ADR） |
| [.design/草案/08-data-contract.md](.design/草案/08-data-contract.md) | REST + WebSocket 契约 |

详细设计（"怎么做"）：[工程结构](.design/详细设计/工程与代码/01-工程结构.md) ·
[依赖与版本](.design/详细设计/工程与代码/02-依赖与版本.md) ·
[编码规范与 Compose 约定](.design/详细设计/工程与代码/03-编码规范与Compose约定.md) ·
[构建环境与镜像](.design/详细设计/工程与代码/04-构建环境与镜像.md) ·
[功能域总览](.design/详细设计/功能/00-功能域总览.md) ·
[UI 视觉稿](.design/详细设计/UI设计/index.html)

## 开发环境

- JDK 25（产物是字节码 25；成品自带 `rt/`＝JBR 25，本机 Gradle 可用 27 运行）
- Gradle 9.8.0（已随 Wrapper 提供，无需本机安装）
- Kotlin / Compose 版本见 `gradle/libs.versions.toml`
- Windows 10/11（首要目标平台）

```powershell
.\gradlew.bat build                              # 全量构建（含测试）
.\gradlew.bat :composeApp:compileKotlinDesktop   # 只编译 desktop 目标
.\gradlew.bat :composeApp:run                    # 开发态运行（需先 git submodule update --init --recursive）
.\gradlew.bat :composeApp:packageDistributionForCurrentOS   # 打 MSI/exe 安装包（非发布资产）

# 发版前的本地组装与验证（壳 + payload 都在 release-test/，见下一节）
pwsh packaging\assemble-release-test.ps1
```

**国内网络不用配代理**：已用腾讯云 Gradle 镜像 + 腾讯/阿里云 Maven 镜像，
`build` 实测 12s（首次约 6 分钟，需下载 ≈870 MB 依赖）。
GitHub 操作仍需要代理 `127.0.0.1:7890`（见 `AGENTS.md` §5 与技能 `global-git-ops`）。
镜像与代理细节见 `.design/详细设计/工程与代码/04-构建环境与镜像.md`。

> 终端组件（BossTerm 裁剪 fork）以 **git submodule + composite build** 接入（`modules/terminal-bossterm`），
> 依赖始终参与构建，没有 `hermes.terminal` 开关了。克隆后先 `git submodule update --init --recursive`，
> 见 `.design/详细设计/工程与代码/04-构建环境与镜像.md` §7。

## 后端依赖

两种模式，由 `config/config.json` 的 `backend.mode` 选择：

| 模式 | 后端从哪来 | 适用 |
|---|---|---|
| `local` | 随包携带的 payload，客户端拉起 `hermes dashboard --port 0`（RPC/REST/Web UI 同端口） | 开箱可用，不需要本机装 Hermes |
| `remote` | 连本机已有的 Hermes dashboard（默认 `127.0.0.1:9119`） | 已跑着官方客户端 / CLI 时共用一套后端 |

本项目只读消费其契约；契约变更时靠生成器跟随。细节见
[`06-本地后端与双模式.md`](.design/详细设计/工程与代码/06-本地后端与双模式.md)。

## 本地组装与验证（`release-test/`）

`release-test/` 是**发版前的本地工作区**（已 gitignore）：壳和 payload 在这里拼好、
人工跑一遍确认，再去触发 `desktop-release` 发版。**不是**下载发布产物的地方。

```powershell
pwsh packaging/assemble-release-test.ps1             # 壳 + payload，缺什么补什么
pwsh packaging/assemble-release-test.ps1 -ShellOnly  # 只改客户端代码时用，约 1 分钟
pwsh packaging/assemble-release-test.ps1 -RefreshPayload   # 强制换 payload
```

脚本做两件事，都可单独跳过：

| 部分 | 来源 | 说明 |
|---|---|---|
| 壳 | `:composeApp:syncReleaseTest` | 启动器与 `rt/` 已就位时 Gradle 自动跳过 jpackage / jlink |
| payload | `Shinglem/hermes-bundle` 的 Release | 解压到 `release-test/hermes`；public 仓库，无需 token |

> **发版流程以 [`CHANGELOG.md`](CHANGELOG.md)「版本号说明」为准**：桌面版本升位（如
> `0.4.0` → `0.5.0`）走 CI 出完整包；之后的小版本只发**差异文件**（`...-patch.zip`）、
> **手工上传**、覆盖式更新、**不走 CI**。
> 具体步骤与坑见技能 [`global-release-patch`](.ai/skill/global-release-patch/SKILL.md)。

它的 `hermes/` 同时就是**开发时的后端**：

```powershell
$env:HDC_HOME = (Resolve-Path release-test).Path
.\gradlew.bat :composeApp:run
```

两种验证形态：

1. **跑成品**（与发出去的完全一致）：`release-test\hermes-desktop-compose.exe`
2. **开发态跑**（改代码即时生效，后端仍是这份 payload）：上面的 `HDC_HOME` 命令

## 发行产物（GitHub Actions）

两类 Release，版本号规则不同：

| Release | 内容 | 谁产出 | tag |
|---|---|---|---|
| **工具链** | 启动器 + 裁剪运行时（同一版本一起出） | `toolchain-windows` | `toolchain-v<N>`（v1、v2 …手工递增） |
| **桌面端** | **纯外壳**，不含 payload，默认 `local` 模式 | `desktop-release` | `v<桌面版本>-<hermes版本>`，例如 `v0.4.0-0.22.5` |

payload 不在本仓库：`Shinglem/hermes-bundle` 的 `v<hermes版本>` Release（约 1.5 GB）。
基线是**官方 Temurin 27**（启动器由它的 `jpackage` 生成，运行时由同一份 `jmods` 经 `jlink` 裁剪）。

`desktop-release` 只做「取件 + 编译外壳 + 组装」，不重跑 jpackage / jlink。

### 首次使用：自己补 payload

桌面 Release **不内置 payload**（所以约 150 MB，也不再分 `local` / `remote` 两套）；
默认就是 `local` 模式，按 Release 正文里的链接下一份对应版本的 payload 即可：

1. 打开该版本对应的 payload Release，例如
   <https://github.com/Shinglem/hermes-bundle/releases/tag/v0.22.5>
2. 下载**全部** `*.7z.001` 分卷（缺一卷解不开），用 7-Zip 解压
3. 得到 `payload\` 目录 → **重命名为 `hermes`** → 放进客户端根目录，与
   `hermes-desktop-compose.exe` 同级
4. 双击 `hermes-desktop-compose.exe`

只想当纯前端（`remote` 模式）就不用下 payload：把 `config/config.json` 的 `backend.mode`
改成 `remote`，客户端会连本机 `127.0.0.1:9119` 的 dashboard。

### 产物命名

```
hermes-desktop-compose-<桌面版本>-<hermes版本>-windows-x64.7z.001
```

Release 标题**不带平台** —— 同一个 Release 以后要能放多个桌面平台的产物，平台只体现在文件名里。
可执行文件名是 **`hermes-desktop-compose.exe`**（v0.3.0 起；此前是 `Hermes.exe`）。
客户端版本号在 `composeApp/build.gradle.kts` 的 `appVersion`，与 tag 的关系见
[CHANGELOG.md](CHANGELOG.md) 文末「版本号说明」。

**小版本不走 CI**：`<桌面版本>-<hermes版本>` 之后只打新 tag 并**手工上传**替换用文件
（`hermes-desktop-compose-<版本>-patch.zip` + `SHA256SUMS.txt`），下载后覆盖安装目录里的
`bin/App.jar` 即可。完整步骤见技能 [`global-release-patch`](.ai/skill/global-release-patch/SKILL.md)。

## 许可证

待定（设计阶段）。使用的第三方组件许可证各不相同，见 `.design/草案/02-selection.md`
（其中 BossTerm fork 取 Apache-2.0；上游为 LGPL-3.0 双许可，若将来闭源商业分发需合规评估）。
