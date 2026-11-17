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

## v0.6.1 · 浏览器侧栏（**attach agent 的 Chromium**）

**目标**：在应用内**实时看到 agent 正在操作的浏览器**。
形态是**右侧可收起抽屉**（默认收起），不做独立窗口、不做底部 tab。

> **范围收窄（2026-11-16，用户定稿）**：
> ① **只做浏览器**，插件宿主骨架（原 S7.2）推后；
> ② 实例**不自己拉起**，而是 attach agent 已经拉起的那一只（ADR-082 修订 ADR-063）。

| 项 | 说明 |
|---|---|
| `BrowserDiscovery` | 读 `<HERMES_HOME>/cache/browser/chromium-records/*.json` → `pid` / `user_data_dir` / `devtools`；端口取该 profile 的 `DevToolsActivePort`。**不用 `cdp_url`**（ephemeral 实例不写它） |
| `Win32WindowQuery` | `pid` → 顶层 `HWND`（FFM `EnumWindows`，不用 AWT/JNA） |
| `BrowserSlot` | `NativeView` + `nucleusHwndPlatformView(handle = { hwnd })`，`key(hwnd)` 包一层 |
| `BrowserSidePanel` | 右侧 360dp 抽屉，可收起 / 可拖宽；容器化以便以后放别的侧栏内容；**槽位常驻**（收起时挪出可视区，不销毁） |
| **纳管与展示分离** | agent 调 `browser_*` 工具 → 纳管窗口但**不打开**抽屉；点侧栏才展示 |
| **不做** | `BrowserProcess`（不起进程）、写 `cdp_url`、关面板杀进程 |

**退出条件**：

1. agent 访问网页时抽屉里能看到同一页面，且它的点击 / 滚动实时同步；
2. 关抽屉后浏览器**仍在运行**，agent 下一轮还能用；
3. resize 不裂（DPI 切换留真机手验）；提权下的行为**明确**（能嵌 or 明确提示）；
4. 不引入 `java.desktop` 引用回归。

**四项前置已实测通过**（ADR-082，2026-11-16，Chromium 145.0.7632.6 / JDK 27）：
跨进程嵌入 ✅、Compose 压在子窗口之上（DComp）✅、resize 跟随 ✅、销毁不影响外部进程 ✅。

**交互语义（2026-11-17 用户定稿）**：**纳管 ≠ 展示**。
agent 调 `browser_*` 工具时把窗口收进本进程（`SetParent`），但**抽屉保持关闭**；
用户点右侧竖条才展开看画面。这条要求同时根治了「一会儿纳管一会儿又成独立窗口」的跳变
（根因：心跳用顶层窗口枚举判活，嵌入后枚举不到 → 误判消失 → 销毁槽位 → 窗口还原 →
再发现 → 无限重挂）。实测 15s × 30 次采样解挂 **0 次**。

**剩余风险**：**UIPI** —— 提权进程嵌入非提权窗口可能被拒（降级为提示 + 外部打开）；
DPI 切换需真机手验。

**细节**：[`详细设计/功能/08-浏览器侧栏.md`](../详细设计/功能/08-浏览器侧栏.md)。

---

## v0.6.1+ · 插件宿主骨架（**推后，未开工**）

ADR-064 的 S7.2：一整块 WebView 占一个 pane，跑我们自己的宿主页面，插件走官方 `plugin.js`。
**本版不做** —— 先把"看 agent 在干什么"做扎实。前提：WebView2 Runtime 检测
（本机已装 157.0.4341.46）。

---

## v0.6.1+ · 内嵌编辑器（**调研中，未选型、未开工**）

**三条并列候选**：

- **方向一 · WebView + Monaco**（ADR-078）：文件内容走 `/api/fs/*`，宿主是
  Nucleus WebView（`composewebview`），宿主页走本地 loopback http + 反代，**不接 LSP / git**。
- **方向二 · 自研 + 对标开源**（ADR-079 / ADR-080）：照抄 aardink 的编辑内核（Apache-2.0）
  + auto-dev 的文件系统抽象与 LCS diff（MPL-2.0，代码慎用）。**不 fork 整仓**。
- **方向三 · Markdown 编辑分工**（ADR-081）：**md-one 不废弃**，作为预览器；
  md-one 只加 ~55 行加性改动，编辑态放 `:modules:md-editor`。

> ⚠️ 都是**调研线**，不是决策。方向一已验 S1–S4（宿主真源 / Monaco 可用 / diff 可用），
> **S5 体积实测已达标（2026-11-16 复核）**：裁完 **4.41 MiB**，见下方说明；方向二、三尚未实测。
> 三条在**数据层可合流**（共用 `HermesFileSystem`），只在渲染层分叉。
> 详见 ADR-078~081 与 [`07-文件与编辑器.md`](../详细设计/功能/07-文件与编辑器.md)。

| 项 | 说明 |
|---|---|
| 文件树 + 预览路由 | 懒加载、隐藏规则与安全过滤由后端负责；本地/远程同一条代码路径 |
| `HermesFsProvider` | `@codingame/monaco-vscode-api` 的 `IFileSystemProvider` 实现，约 200–400 行 |
| WebView 宿主 | `dev.nucleusframework:composewebview`（**Nucleus 官方 WebView，独立发版**）+ **本地 loopback http 服务**（Ktor CIO）承载 Monaco 静态资源并反代 `/api/fs`，复用 v0.6.1 的 Runtime 检测 |
| Monaco 裁剪 | 删 `ts/css/html/json` worker，只留 `editor.worker` + 90+ 语言着色，增量约 3–4 MB |
| 刷新策略 | **后端无文件监听**：监听 agent `tool_use` 事件按路径刷新（方案 3）+ 手动刷新按钮 |

**两条已核实的限制**（写进契约与设计，不可假设）：

1. **`mtime` 拿不到** —— 任何端点都不返回，provider 一律补 0（Monaco 会因此每次重读，加 2 秒短期缓存）。
2. **`watch` 不存在** —— 后端无文件系统监听，WebSocket 端点上也没有文件通道；
   方案 3 覆盖不到「外部编辑器改的」「agent 用终端命令改的」两种情况，所以必须有手动刷新。

**前置 spike（S1–S4 已通过；S5 复核后已达标，且不代表方向已选）**：

1. ✅ **通过**：宿主页走本地 http 真源（`data:` 源下 fetch / `new Worker(http)` 均失败；
   `http://127.0.0.1:<port>` 下 8 并发 worker 全 ok）→ 实现为 Ktor CIO loopback 服务 + 反代 `/api/fs`；
2. ✅ **通过**：`composewebview 1.0.3` 与 Nucleus 3.0 运行期共存（窗口能开、WebView 能显示、JS 桥正常）；
3. ✅ **通过**：真实 Monaco 在真源下可用（91 种语言、`colorize` 26 span、`editor.worker` 可构造）；
4. ✅ **通过**：`createDiffEditor` 正常（`getLineChanges()` 返回 1 条）；
5. ✅ **已达标（2026-11-16 复核修正）**：最小可用 AMD 集裁剪后 **4.41 MiB（4,623,470 B）** < 8 MB。
   > **更正**：此前记的「22.04 MB」是 `.local/webview-probe/vs` **未裁剪**的体积
   > （126 个文件 / 23,115,314 B）。按裁剪规则实删
   > `vs/language/{typescript,css,html,json}` + `vs/assets/{ts,css,html,json}.worker-*`
   > + `toggleHighContrast*` 后为 **4.41 MiB**。原结论把"未裁剪量"当成了"裁剪后量"。
   > 注意该素材目录存在 `vs/language/language/…` 的双层嵌套（拷贝时多套了一层），
   > 裁剪脚本必须按**实际层级**删，否则删不到、又会误得 12 MB 的中间值。

> 回归判据：`./gradlew :composeApp:webViewProbe`（S1/S2）与 `:composeApp:webViewProbe3`（S3/S4）。
> **注意**：1–4 只说明这条技术路线可行，不代表方向已选。

**决策与细节**：ADR-078、[`07-文件与编辑器.md`](../详细设计/功能/07-文件与编辑器.md)。

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
