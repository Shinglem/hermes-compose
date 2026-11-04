# 03 · 架构

## 1. 分层

```
┌─────────────────────────────────────────────────────────┐
│ UI 层          Compose 组件（feature/*），只读状态、发意图   │
├─────────────────────────────────────────────────────────┤
│ 状态层         ViewModel / *StateHolder：StateFlow<UiState> │
├─────────────────────────────────────────────────────────┤
│ 领域层         Repository：一次业务操作 = 一次或多次传输调用 │
├─────────────────────────────────────────────────────────┤
│ 传输层         GatewayClient（WS JSON-RPC）                  │
│                RestClient（HTTP /api/*）                     │
├─────────────────────────────────────────────────────────┤
│ 平台层         PTY（BossTerm 自带）· 剪贴板 · 外链 · 偏好     │
└─────────────────────────────────────────────────────────┘
```

**依赖方向单向向下**。UI 层不得直接持有传输层对象；Repository 不得持有 Composable。

---

## 2. 传输层：两条通道

Hermes 后端在 `127.0.0.1:9119`（dashboard 端口）上同时提供两种接口：

| 通道 | 协议 | 用途 | 承载对象 |
|---|---|---|---|
| **gateway** | WebSocket + JSON-RPC 2.0（text 帧） | 会话、聊天、流式事件、server→client 请求 | `GatewayClient` |
| **REST** | HTTP + JSON，`/api/*` | 技能、工具集、cron、profiles、配置、模型、MCP、消息平台 | `RestClient` |

- 两者共用同一个 base URL 与 token。
- `/api/status` 是唯一免鉴权的健康检查；其余未带 token 返回 401。
- **不要把管理类调用塞进 gateway RPC**，也不要指望 gateway 支持 REST 语义。

### 2.1 GatewayClient 必须实现的六件事

```
1. 连接状态机：idle → connecting → open → closed/error
2. 请求：id 自增 → 写帧 → pending map 挂 CompletableDeferred → 超时/结果/错误
3. 心跳：定时 ping + deadline（超时判死，处理半开 TCP）
4. 重连：指数退避 → 重连成功后 replay（拉 session.events.since 补齐）
5. server→client 请求：按 method 路由到注册的 handler，应答 respond/fail，**幂等**
6. 事件分发：method == 'event' 的帧 → SharedFlow<GatewayEvent>
```

### 2.2 超时分级（照搬官方语义，不要统一成 30s）

| 场景 | 超时 | 理由 |
|---|---|---|
| 默认请求 | 30s | 交互式调用 |
| 启动期批量读（config / profiles / models / cron） | 60s | `/api/profiles` 会递归扫每个 profile 的 skill 树，慢但正常 |
| `prompt.submit` | 1800s | 一轮对话完成靠 `event` 通知，不靠 RPC 返回；短超时会误报 |
| `/api/status` 健康探测 | 5–10s | 要快速发现后端死了 |

### 2.3 契约生成（最大的一笔省）

后端契约是 Python 单源生成的：

```
C:\ai\hermes\app\tui_gateway\contracts\        (Python，唯一数据源)
        │  scripts/gen_gateway_contracts.py
        ▼
apps/shared/src/gateway-contract.generated.ts   (5,691 行 TS，有防陈旧测试)
```

本项目：**给同一份 Python 契约写一个 Kotlin 生成器**，输出

- `@Serializable data class`（事件 payload、请求/响应参数）
- `RpcMethods` 常量对象（方法名 → 参数/结果类型）
- `GatewayEventMap` 的类型安全访问器

一期只生成用到的子集（sessions / chat / profiles / models / config / cron / skills / toolsets），
后续按需扩。**不手抄契约**。

> 若生成器成本超预期，退路是手写最小集（约 30–50 个类型），但要在 `07-decisions.md` 记录。

---

## 3. 连接与应用作用域

```
Application（进程级，唯一）
└── AppContainer
    ├── GatewayClient      ← 必须单例
    ├── RestClient         ← 必须单例
    ├── Repositories（lazy）
    └── Preferences（multiplatform-settings）
```

**GatewayClient 与 RestClient 必须是应用级单例**。原因：后续要支持"弹出独立聊天窗口"，
新窗口只能复用同一连接；每个窗口各建一条 WebSocket 会导致事件分叉、会话状态不一致。

Compose Desktop 里把它放在 `application { }` 作用域，`Window { }` 只是订阅者。

---

## 4. 状态管理

### 4.1 约定

- 一个 feature 一个 `ViewModel`（普通类，构造注入 Repository），暴露 `StateFlow<UiState>`。
- UI 用 `collectAsStateWithLifecycle()`（CMP 对应 `collectAsState()`）。
- 事件（一次性动作）用 `Channel` → `Flow`，不要塞进 `UiState`。
- **不做全局单一 store**。原版 `store/` 目录 41,169 行、几十个 nanostores 模块，是 React 重渲染的产物；
  Compose 里按 feature 拆分即可。

### 4.2 会话与 profile 的状态隔离

切换到另一个 profile 时：

- 会话列表：**保留已加载的缓存**，只在缺失时拉取（否则来回切有明显卡顿）。
- 当前打开的会话：按 `(profile, sessionId)` 记忆，切回时恢复。
- 事件订阅：按 profile 过滤，不要用另一个 profile 的事件污染当前 UI。

---

## 5. 本地持久化

用 `multiplatform-settings`（桌面端落在 `java.util.prefs` 或文件，可配）保存：

| 键 | 内容 |
|---|---|
| `ui.mode` | `sessions` / `capabilities` |
| `ui.list.width` | 中栏宽度（如未来启用） |
| `ui.right.panel` | 右栏选中 tab |
| `ui.terminal.open` | 终端展开状态与高度 |
| `sessions.pinned` | 置顶会话 id 列表 |
| `groups.collapsed` | 时间分组折叠状态 |
| `editor.command` | 外链编辑器命令模板 |
| `conn.baseUrl` / `conn.token` | 连接参数（token 走安全存储或环境变量） |
| `drafts.<sessionId>` | 未发送草稿 |

**不上数据库**。等真需要全文检索历史 transcript 时再评估 SQLite。

---

## 6. 线程与协程

- 所有网络与 IO 走 `Dispatchers.IO`；UI 状态更新在 `Dispatchers.Main`（CMP desktop 的 Main = AWT EDT 适配层）。
- 流式渲染：把 token 级 delta 按帧合并（16–32ms 一次 `append`），避免每 token 重组。
- 终端 PTY 读写由 BossTerm 内部线程负责（pty4j 自带），不要放进 UI 协程。

---

## 7. 错误处理

| 层 | 做法 |
|---|---|
| 传输 | 连接错误 → 状态机 + 重连；请求错误 → `Result<T>` 带回错误码与消息 |
| 领域 | 映射成领域错误（未登录 / 后端未启动 / 超时 / 协议错误） |
| UI | 顶部横幅（连接类）+ Snackbar（操作类），不弹阻塞对话框 |
| 致命 | 启动失败显示诊断页（后端地址、错误、打开日志目录按钮） |

---

## 8. 目录结构（建议）

```
composeApp/src/commonMain/kotlin/dev/shinglem/hermes/
├── App.kt                     ← Application 入口 + AppContainer
├── core/
│   ├── transport/             ← GatewayClient / RestClient / JsonRpcFrames
│   ├── contract/              ← 生成的契约类型与 RpcMethods
│   └── prefs/                 ← Preferences 封装
├── feature/
│   ├── session/               ← 会话列表、置顶、分组
│   ├── chat/                  ← 消息列表、流式渲染
│   ├── composer/              ← 输入框、chip、补全
│   ├── terminal/              ← BossTerm 封装、折叠面板
│   ├── capabilities/          ← 技能与工具 / 消息平台 / 定时任务
│   └── settings/
├── ui/
│   ├── layout/                ← Scaffold、rail、状态条
│   └── theme/                 ← M3 主题（原生）
└── platform/                  ← 外链编辑器、剪贴板、文件选择
```
