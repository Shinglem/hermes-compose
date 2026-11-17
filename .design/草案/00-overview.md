# 00 · 项目概览

> **基线 v0.6.0（2026-11-15）。** 本篇回答「做什么、不做什么」。
> 架构怎么做看 [`03-architecture.md`](03-architecture.md)；历史里程碑见 [`归档/`](../归档/)。

---

## 一句话

用 Kotlin + Compose Multiplatform 重写 Hermes 桌面客户端，**后端完全不动**，目标平台 Windows 优先。

## 为什么要重写

| 动机 | 说明 |
|---|---|
| 体积与启动 | Electron 运行时 + React 生态（生产代码 ≈ 45 万行）带来体积与冷启动成本 |
| 原生手感 | 滚动、输入法、快捷键、窗口管理在 Web 壳里始终隔一层 |
| 可控性 | 不需要跟随上游 React 生态的依赖升级节奏 |
| 精简 | 官方客户端里约一半能力（插件市场、文件树、群聊、远程屏幕）本项目不用 |

## 当前形态

```
┌────┬──────────────────┬────────────────────────────────┐
│    │  会话列表         │  对话 + 终端                    │
│ 头 │  （置顶/时间分组） │                                │
│ 像 │                  │                                │
│ 条 │                  │                                │
├────┴──────────────────┴────────────────────────────────┤
│ 状态条（连接 / 后端 / 网关探活 / 版本）                    │
└────────────────────────────────────────────────────────┘
```

三种模式（左 rail 切换）：**会话**（默认）/ **能力** / **设置**。
设置页是「一次性去处」——重启回会话；能力页会持久化。

### 已交付的功能域

| 域 | 内容 | 状态 |
|---|---|---|
| 会话 | 列表（置顶 / 时间分组 / 折叠 / 搜索 / 归档 / 重命名 / 删除 / 复制 / 导出）、profile rail、命令面板 | ✅ |
| 对话 | 流式 Markdown、代码高亮、mermaid/PlantUML/DOT 图表、数学公式、工具调用卡片、思考块、用量明细、回退 / 分支 | ✅ |
| 输入框 | Markdown 源码输入、`@` / `/` 补全、粘贴图片、附件 chip、草稿、历史（↑）、Enter 发送、IME 组合态 | ✅ |
| 审批与澄清 | 卡片 + 系统通知就地应答（跨会话点通知不报 4001） | ✅ |
| 终端 | BossTerm 裁剪版（Compose 原生渲染），多 tab、引用选中内容到对话 | ✅ |
| 能力 | 技能与工具 / 消息平台 / 定时任务（cron）三分类 | ✅ |
| 设置 | 模型 / 提供方密钥 / MoA / 自定义端点 / 档案 / 记忆 / 维护 / 安全 / 检查点 / 通知 / 编辑器 / 外观 / 关于 | ✅ |
| 桌面集成 | 单实例、托权启动（runas）、计划任务自启、托盘常驻、原生 toast、命令面板、快捷键 | ✅ |
| 子代理 | 面板（roster / 输出 / steer / interrupt） | ✅ |

逐项功能映射见 [`详细设计/功能/00-功能域总览.md`](../详细设计/功能/00-功能域总览.md)。

## 技术栈（版本以 `gradle/libs.versions.toml` 为准）

| 层 | 选型 | 备注 |
|---|---|---|
| 语言 / 运行时 | Kotlin 2.4.x + **JDK 27（Temurin，带 jmods）** | 基线从 JBR 换到官方 JDK |
| UI | Compose Multiplatform 1.12.x + Material 3 | |
| 架构 | Decompose（Component）+ MVIKotlin（Store）+ Koin（DI） | v0.6.0 引入 |
| 窗口壳 | Nucleus Tao（`nucleus-application` + `decorated-window-tao`） | 无 AWT 依赖 |
| 托盘 | `composenativetray` | 原生菜单（解决中文乱码） |
| 网络 | Ktor client（CIO）+ WebSockets | |
| 序列化 | kotlinx-serialization-json | |
| 本地偏好 | multiplatform-settings | |
| Markdown / 高亮 / 图表 / 公式 | `:modules:markdown-one`（submodule） | 本仓库只接线 |
| 终端 | BossTerm 裁剪 fork（submodule）+ Pty4J | |
| 文件对话框 | FileKit | 替代 AWT `FileDialog` |
| 测试 | kotlin-test + Turbine + Compose UI Test | 823 个测试 |

逐项选型理由与核对记录见 [`02-selection.md`](02-selection.md)。

## 非目标（明确不做）

见 [`03-architecture.md`](03-architecture.md) §9.3 的完整表格。核心几条：

| 不做 | 原因 |
|---|---|
| 修改后端 | 契约只读；改了会与官方客户端分叉 |
| 自研插件机制 / JS 插件宿主 | 前端插件返回 `ReactNode`，保留生态 = 自造 WebView |
| 文件树 / 内置编辑器 / diff | 跳转外部 VSCode / IDEA（ADR-005，现行实现不变）。**2026-11-16 起三条调研线**：① WebView + Monaco；② 自研 + 对标开源；③ md-one 分工。**均未选型**，见 ADR-078~081 |
| 远程屏幕 | 用 ToDesk / AweSun |
| 视觉 1:1 复刻 | 用 M3 原生组件，稿子是参考稿 |
| 多语言 | 只做简体中文 |
| WebView 兜底渲染 HTML | ksoup 原生渲染 + diagram-render + ratex |

## 与官方客户端的关系

- **不 fork、不 vendored**：独立仓库，只消费 Hermes 后端的公开契约。
- 契约以 `C:\ai\hermes\app\tui_gateway\contracts\`（Python 单源）为准。
- 官方客户端继续存在；本项目是并行的替代前端。

## 成功判据

能完成「打开 → 选会话 → 提问 → 看着流式回答 → 用终端验证 → 改配置」这六步，
不怀念 Electron 版。量化版本见 [`05-acceptance.md`](05-acceptance.md)。

## 下一步（v0.6.1+）

| 项 | 说明 |
|---|---|
| 浏览器面板 / 插件宿主骨架 | S7 推后到 v0.6.1（ADR-063 / ADR-064）；`NativeView` 子窗口是唯一路径 |
| 内嵌轻量编辑器 | 候选：Aardink（纯 Kotlin Compose 组件，ADR-064）。**不内嵌 VSCode** |
| `ChatViewModel` per-session 化 | 唯一 >600 行的文件（1951 行），见 `03-architecture.md` §9.1 |
| 通知 AUMID 免快捷方式 | `待修复问题.md` #21（待 spike） |
| IM 风格独立聊天窗口 | 需复用同一 WebSocket 连接（架构已为此预留应用级单例） |
