# 06 · 路线图

> **基线 v0.6.0（2026-11-15）。** 本篇只讲「接下来做什么」。
> 已完成的里程碑（P0–P6、v0.2.0 / v0.3.0 / v0.4–v0.5.x）已归档，
> 见 [`归档/v0.1-0.5-历史/06-roadmap（v0.5.x）.md`](../归档/v0.1-0.5-历史/06-roadmap（v0.5.x）.md)。

---

## 已完成（摘要）

| 版本 | 主线 | 关键交付 |
|---|---|---|
| v0.1–v0.3 | 骨架 → 只读对话 → 流式 → 终端 → 设置中心 | 三栏布局、Markdown 渲染、`prompt.submit` + 事件流、BossTerm 接入 |
| v0.4 | 对话深度 + 桌面集成 | 审批卡片、composer 补全、单实例、提权、自启、托盘、原生 toast |
| v0.5.x | 缺陷修复线（至 0.5.8） | 附件 chip、浏览器启动、清单清理 |
| **v0.6.0** | **结构性重构** | Component + Store 架构（Decompose/MVIKotlin/Koin）、桌面壳迁 Nucleus Tao、业务代码脱离 AWT、FileKit、823 测试 |

v0.6.0 的详细记录见 [`../详细设计/工程与代码/13-重构落地设计`](../归档/v0.6.0-重构/13-重构落地设计.md)（已归档）与
`CHANGELOG.md`。

---

## v0.6.1 · 浏览器面板与插件宿主

**目标**：能在应用内打开网页（官方客户端的 browser panel 能力），并跑通 WebView 插件宿主骨架。

| 项 | 说明 |
|---|---|
| `NativeView` 子窗口槽位 | Tao 已是唯一窗口壳，`NativeView` 是内嵌 WebView2 的唯一可行路径 |
| `BrowserProcess` | 拉起 / 复用 Chromium 实例（与官方客户端共享 profile） |
| `PluginHostView` | WebView 槽位 + 桥；插件机制仍用官方 `plugin.js`，**不自研** |
| WebView2 Runtime 检测 | 发布时检测 + 引导安装（本机已装 157.0.4341.46） |

**退出条件**：

1. 应用内能打开网页并交互，窗口 resize / DPI 切换下不裂；
2. 一个官方插件能在宿主里运行；
3. 不引入 `java.desktop` 引用回归。

**风险**：`NativeView` 在 resize/DPI 下的稳定性（未实测）；`composewebview` 的 POM 依赖
compose 1.11.1（本项目 1.12.x）—— 不通过则自写 WebView2 controller。

---

## 技术债（不绑定版本，按需偿还）

| 项 | 现状 | 偿还方式 |
|---|---|---|
| `ChatViewModel` 1951 行 | 唯一 >600 行文件；六类簿记与三份流式实现已拆出 | per-session Component（涉及 `RootComponent` 的 `ChildStack` 与 InstanceKeeper 作用域，独立一轮） |
| 触摸滚动未验证 | 本机无触摸设备 | 有设备后按 [`10-Windows触屏输入.md`](../详细设计/工程与代码/10-Windows触屏输入.md) 的验收走 |
| 中文 IME 完整链路未验证 | 注入被 IME 吞掉 | 人工验（见技能 `dev-desktop-gui-e2e-testing` §2.7 的替代手法） |
| 通知 AUMID 依赖开始菜单快捷方式 | `待修复问题.md` #21 | 待 spike（Windows App SDK / COM 注册） |
| `jdk.javadoc` 静态依赖 | icu4j 的 javadoc taglet（死代码），已豁免 | 若 icu4j 升级后仍带，保持豁免；不引入 jlink 的 5 个连锁模块 |

---

## 明确不做（延续）

见 [`03-architecture.md`](03-architecture.md) §9.3。

---

## 里程碑门槛（能力门槛，不是时间门槛）

| 门槛 | 判据 |
|---|---|
| 可用 | 能完成「打开 → 选会话 → 提问 → 看流式 → 终端验证 → 改配置」 |
| 可发 | 全量 CI release 通过 + 本机覆盖式部署后 `startup.log` 无异常 |
| 可回归 | 823 测试全绿 + 真机六项（单实例 / 提权 / 自启 / 托盘 / 触摸 / IME，后两项受环境限制） |

---

## 版本号口径

桌面 Release tag 形如 `v<桌面版本>-<hermes版本>`（例 `v0.6.0-0.23.0`）。
**2026-11-07 起所有版本（含小版本）都发全量包**，由 CI `desktop-release` 构建；
产物是纯外壳、不含 payload。工具链在 `toolchain-v<N>` 里，**只增不改**。
发版流程见技能 `global-release-patch`。
