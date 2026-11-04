---
name: desktop-stack-rewrite-assessment
description: "重写桌面客户端到另一套 UI 栈时：功能域映射、WebView 边界与档位。"
version: 1.0.0
author: Hermes Agent
license: MIT
platforms: [windows, linux, macos]
metadata:
  hermes:
    tags: [重写评估, 跨生态迁移, Compose-Multiplatform, Electron, 组件选型]
    related_skills: [open-source-project-evaluation, codebase-inspection]
---

# 桌面端跨生态重写评估

> **中文名**：桌面端重写评估｜目录与 `name`：`desktop-stack-rewrite-assessment`

适用："用 X 重写现有桌面客户端行不行/多少钱""后端不动只换前端""这个 Electron 应用换成 Compose 要多久"。
与 `open-source-project-evaluation` 的分工：那边评估**别人家的项目能不能用**，这里评估**自己的重写边界与工作量**。

**核心规则：结论必须拆到"功能域 × 目标栈三分类"，不给笼统的"能做/不能做"。** 一句"生态不兼容"会同时高估和低估成本。

## When to Use

- 用户问某跨平台 UI 框架（Compose Multiplatform / Flutter / Tauri / Avalonia）能否替代现有 Electron 客户端
- 后端保持不变、只重写前端形态的可行性讨论
- 需要为"是否立项 + 投哪档"提供依据

## 流程

1. **依赖清单即功能域索引。** 读 `package.json` 的 `dependencies`，不从 UI 截图或 README 反推。`@xterm/*`→终端、`@codemirror/*`→编辑器、`@novnc/novnc`→远程桌面、`shiki`/`katex`/`mermaid`→富文本栈、`node-pty`→主进程 PTY、`electron-updater`→更新。
2. **定位现实现，区分"库包装"与"自研逻辑"。** 同一个库可能是 200 行包装，也可能是带 schema/联动/快捷键/持久化的子系统。`ls` + 看文件头注释 + 统计该目录**生产行数**（测试分开计）。行数决定"重写"还是"重新设计"。
3. **三分类映射（产出就是这三堆）：** 有成熟库 ⇒ 直接选型；有库但太新（2025 年后涌现）⇒ spike 必验 IME/撤销/性能/中文/大文件；无等价物 ⇒ 收敛成"孤岛清单"。不写"待评估"，不按 README 选型。
4. **先收敛孤岛，再谈档位。** 档位的含义会随选型变：终端走 pty4j+组件、编辑器走原生库之后，"WebView 兜重资产"可能只剩 1–2 个小岛，与最初假设完全不同。

## 决定性判据

1. **扩展点返回框架对象 ⇒ 目标栈无法原生渲染。** 例：插件贡献项 `render?: () => ReactNode`。保留这类 UI 插件唯一务实路径是内嵌浏览器（自带 React 运行时 + DOM = 自造 WebView）；而 `data?: unknown`（主题/布局预设/命令）是纯数据，可跨语言解析。**只支持 data 型、放弃 UI 型**常常是最优切法。
2. **前后端插件是两套系统，语言不必相同。** 后端插件（进程内：工具/钩子/提供者/平台适配器，Python 常见）与前端插件（UI 贡献，TS/React 常见）manifest 与发现机制独立；重写前端**不影响**后端插件能力。必须把"放弃插件生态"拆成"丢 UI 扩展"与"丢功能扩展"，并用"desktop 分类条目数 / 总条目数"这种分母量化。
3. **范围裁剪要主动扇连带效应。** 用户说"先不考虑 X"时，立刻列出下游模块（例：不做前端插件 ⇒ 插件 pane 需求消失 ⇒ 布局引擎可砍）。
4. **布局引擎（IDE 级 docking）是最易低估的大件。** 量化：引擎生产行数 + 相关 store + 测试文件数（测试常与生产同量级），列能力清单（递归 split/group、normalize、浮动 pane、拖拽停靠、tab 三态、预设、多 pane 镜像），再看多少是给"编辑器/插件"服务的。真正难的不是拖分隔条（`pointerInput` 几十行），而是递归树 + normalize + 序列化迁移 + 生命周期/共享/恢复。折中形态通常才是用户要的：**固定骨架 + 宽度可拖 + 折叠 + 单区多 tab + 一个硬上限的例外**（如"中区最多两列并排"，用 `Row` + `weight(1f)` 两种分支，不引入递归树）。

## Compose Multiplatform 侧对照（详细选型时逐行复核库活跃度）

| 功能域 | 现代 JS 栈 | Compose 侧候选 | 风险 |
|---|---|---|---|
| 终端 | xterm.js + node-pty | pty4j（Windows 用 ConPTY）+ KetraTerm / BossTerm / JediTerm(Swing) | 中 |
| 代码编辑器 | CodeMirror 6 | SweetEditorCompose（C++ 内核）或自研 + tree-sitter | 高 |
| 流式 Markdown | streamdown / unified | CommonMark-java + 自研渲染；multiplatform-markdown-renderer（容错自加）| 中高 |
| 代码高亮 | shiki（TextMate）| 无等价；codehigh / tree-sitter 方案 | 高 |
| 数学公式 | KaTeX | 纯 Kotlin LaTeX 渲染库 | 中高 |
| 图 / 远程桌面 | mermaid / noVNC | 无等价 | **红（必须内嵌浏览器）** |
| 长列表 / 拖拽排序 | react-virtual / dnd-kit | LazyColumn / sh.calvin.reorderable | 低 |
| 输入框 chip/@/slash | contenteditable 自研 | BasicTextField + InlineTextContent | 高 |
| 协议 / 事件流 | fetch / SSE / WebSocket | Ktor client + SSE/WebSocket 插件 | 低 |
| 本地缓存 / 路由 | localStorage / react-router | SQLDelight / navigation-compose(KMP) | 低 |
| 自动更新 / 打包 | electron-updater / builder | 无标准方案（自研）/ packageDistributionForCurrentOS | 高 / 中 |

结论形状：多数"大件"都有原生或 Swing 路径，孤岛通常只剩 1–2 个（图渲染、远程桌面）。

## 输出形状（用户认这个）

每个功能域一条，四段式：`现实现（实证行数/文件）→ 砍掉后写什么（量级估计）→ 失去什么（逐条，让他判断）→ 建议档位`。

- 档位用 **L1 起步 / L1.5 低成本的例外 / L3 永不做**，给出每档退出条件；比"高/中/低"可执行。
- 风险固定四档：低（成熟库）/ 中（有库需适配）/ 高（需自研或太新）/ 红（只能内嵌浏览器）。
- 结论方向跟数字走：测算上修后完整对等性价比"进一步恶化"时，明说方向被加强，不为安慰而淡化。
- 先一句结论，再证据表（带 `文件:行号`），最后 3–5 条 spike 项（每项带通过判据与退出条件），不直接给工期承诺。

## Pitfalls

1. **规模口径必须钉死并双路验证。** `xargs` 分批会让 `wc -l | tail -1` 只拿到最后一批的 total（可差 10 倍且不报错）。用 python `os.walk` 逐文件求和或 pygount，并用第二条口径交叉验证，差额归因到具体文件（漏小众扩展名、`.d.ts`、i18n 语言包）。反推人天前先剔除语言包与生成物。
2. **不要把"语言不同"当"不能协作"。** 前端 TS、后端 Python 是常态，靠 JSON-RPC/HTTP/事件流通，或统一包里 `__init__.py` + `desktop/plugin.js` 并存。看到两种语言先找接口。
3. **区分"产品概念"与"后端概念"。** 前端插件可能把后端已有的 profile/session 包装成 bot/群聊/roster，后端一行专属代码都没有。先 grep RPC 调用面（`request('...')` 去重）再断言重写要不要动后端。
4. **别把重资产当成一个整体报。** "终端+编辑器+渲染栈 = 300–500 人天"看不出哪块是孤岛；拆到库级再给档位。
