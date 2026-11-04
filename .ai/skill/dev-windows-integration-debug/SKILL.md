---
name: dev-windows-integration-debug
description: "排查开机自启、单实例、UAC 提权、托盘、计划任务这类 Windows 集成问题时使用：先读系统真实状态（schtasks / 进程 / startup.log）再改代码，幂等对账必须校验全部字段，内部标识改名要带迁移清理。"
---

# Windows 集成排障：自启 / 单实例 / 提权

> **中文名**：Windows 集成排障 ｜ 目录与 `name`：`dev-windows-integration-debug`

**适用**：用户报"开机自启会弹窗 / 不启动"、"托盘没有图标"、"双击没反应"、"又弹了一次 UAC"，
或你要改自启任务、单实例、提权相关代码之前。
**不适用**：纯 UI 的键鼠注入验证（见 [`dev-desktop-gui-e2e-testing`](../dev-desktop-gui-e2e-testing/SKILL.md)）。

设计口径在 [`07-提权与自启动.md`](../../../.design/详细设计/工程与代码/07-提权与自启动.md) 与
[`08-单实例与启动时序.md`](../../../.design/详细设计/工程与代码/08-单实例与启动时序.md)；
本技能只写**排障顺序与判据**。

## §1 顺序：系统真相 → 日志 → 代码

这类问题的根因**经常不在代码里**，而在系统里留下的旧状态（旧任务、旧互斥量、旧 exe 路径）。
先取证，再改代码 —— 否则会"改一堆代码，故障依旧"。

```powershell
# 1) 系统里到底注册了几条我们的任务？（含参数，不能只看名字）
schtasks /Query /FO CSV /NH | ConvertFrom-Csv -Header Name,Next,Status |
  Where-Object { $_.Name -like '*HermesCompose*' }
foreach ($n in <上面列出的名字>) { schtasks /Query /TN $n /XML | Select-String '<Command>|<Arguments>|<Delay>|<RunLevel>|<URI>' }

# 2) 现在跑着几个进程、是不是托盘态
Get-Process -Name hermes-desktop-compose | Select-Object Id,MainWindowHandle,MainWindowTitle

# 3) 启动日志（我们自己的，不受 elevated 影响）
Get-Content <home>\logs\startup.log -Tail 40
```

判据：

| 日志行 | 含义 |
|---|---|
| `start pid=… elevated=true silent=false` + `args (none)` | 被**不带 `--silent`** 的路径拉起（早期任务 / 手动双击） |
| `instance already running; silent trigger exits without activating` | 晚到的那条自启任务被单实例挡掉 —— 说明有两条在触发 |
| `autostart … exeArgsMatch=false` | 任务存在但命令或参数与期望不符（对账会重写） |

> 本仓库真实事故：`\HermesCompose`（早期固定名、**没有** `--silent`）与
> `\HermesCompose-<hash>` 并存 → 旧任务先弹出带窗口的实例，新任务 20 秒后被单实例挡掉。
> 代码里只比 `<Command>` 不比对 `<Arguments>`，所以旧任务永远"健康"、永不重写。

## §2 幂等对账的三条硬要求

`ScheduledAutostart.reconcile` 这类"让系统状态贴合配置"的逻辑：

1. **期望状态有哪些字段，就校验哪些字段**。只比路径不比参数 = 参数永远修不回来。
2. **读不到 = 不匹配**（编码 / 解析失败一律当"需要重写"）。反过来的假设会永远坏在"自以为健康"。
3. **先建立新的，再清理旧的**。反序的话，写失败会让这份安装彻底失去自启。

清理旧物时的边界（本仓库血泪）：

- 只认**自己算出的名字**或明确的命名空间（`^HermesCompose(-[0-9a-f]{8})?$`），绝不按前缀模糊删
  —— 本机就有 `Hermes-Netcatty-Launch`、`HermesDashboard` 这类别家的任务。
- 只删**指向同一个 exe** 的多余项；指向别处的一律不碰（可能是另一份在用的安装）。
- 文件名比较要规范化：忽略大小写、`/` 与 `\`、首尾引号与结尾反斜杠。

## §3 改名 = 迁移，不是重命名

内部标识（计划任务名前缀、单实例互斥量、数据目录）**一旦发布就不能只改常量**：
既有用户机器上的旧对象不会自己消失。

| 改什么 | 必须同时做 |
|---|---|
| 自启任务名 | 启动时扫描并清理**旧名字 + 同一 exe** 的任务；`parseOrphans` 认全两种命名 |
| 互斥量名 | 新旧都探测一次，命中即视为"已在运行"，否则会同时起两份 |
| 数据目录 | 一次性迁移旧目录，或明确放弃并写进 CHANGELOG「升级注意」 |

## §4 提权与 UIPI：普通进程摸不到 elevated 窗口

`elevate=ask` 时进程是管理员，普通权限的脚本对它**做不了这些事**（都会被静默拒绝）：

| 想做 | 结果 | 证据 |
|---|---|---|
| `PostMessage(WM_CLOSE)` | 返回 `False` | UIPI 丢弃低完整性消息 |
| `CloseMainWindow()` | 返回 `True` 但窗口不动 | 同上 |
| `SetCursorPos` + `mouse_event` 点击 | 点击落到别的窗口 | 注入被拦 |
| `SetForegroundWindow` | 返回 `False` | 前台限制 |

所以**验证 elevated 进程的 UI 不要指望注入**，改用：

1. **日志 + 进程状态**（首选）：`startup.log` 已经有 `silent` / `elevated` / `autostart` 判据；
2. **Compose UI 测试**：文案、布局、状态这类断言直接写 `runComposeUiTest`，比注入可靠得多；
3. 真要注入：把 `config_custom.json` 的 `app.elevate` 临时改 `never`（**先备份，验完还原**），
   重启后以普通权限跑；注意这会改变被测环境，结论要注明。

## §5 排障命令小抄

```powershell
# 计划任务：手动触发（等价于登录触发，不用注销）
schtasks /Run /TN '<任务名>'
# 计划任务：删一条（需管理员）
schtasks /Delete /TN '<任务名>' /F
# 是否管理员
(New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
# 全量任务 XML（一次拿全部定义，比逐个查询快一个数量级）
schtasks /Query /XML
```

⚠️ **elevated 进程的命令行 / 环境变量读不到**（WMI `Win32_Process.CommandLine` 受系统保护）。
排障看自己写的日志文件，别指望 `Get-CimInstance`。

## §6 坑

| 现象 | 原因 | 做法 |
|---|---|---|
| "代码里明明有 `--silent`，为什么还弹窗" | 系统里另有一条旧任务 | §1 先枚举，别只看当前任务名 |
| 改了任务名之后用户还每次登录跑两次 | 旧任务没人删 | §3 迁移清理 |
| 删除任务报 `Access is denied` | 非管理员 | 提权执行（`Start-Process -Verb RunAs`），或让客户端在已提权时自清理 |
| 自启任务存在、参数也对，但登录后没起来 | `LogonType=S4U` / `/RU SYSTEM` 会落到无 UI 会话 | 必须是 `InteractiveToken` + 当前用户 |
| 托盘图标"没出现" | Windows 11 默认把新图标折叠进 `^` | 点开 `^` 确认，不要据此改代码 |
| 用 `-c safe.directory` 才能跑 git | 仓库盘符属主与当前用户不同 | 见 [`global-git-ops`](../global-git-ops/SKILL.md) |

## §7 相关

- 提权与自启动设计：[`07-提权与自启动.md`](../../../.design/详细设计/工程与代码/07-提权与自启动.md)（§3.4 参数表、§3.9 冗余任务清理）
- 单实例与启动时序：[`08-单实例与启动时序.md`](../../../.design/详细设计/工程与代码/08-单实例与启动时序.md)
- 覆盖部署与发版：[`global-release-patch`](../global-release-patch/SKILL.md)
