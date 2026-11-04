---
name: dev-desktop-gui-e2e-testing
description: "用脚本驱动 Windows 桌面 GUI（键鼠注入 + 窗口截图）验证 Compose Desktop 应用时使用：中文输入法吞按键、Enter 不发送、空格丢失、抢不到前台、点击落到别的窗口、elevated 窗口注入被 UIPI 丢弃、DPI 缩放导致截图坐标偏移等问题与替代手法。"
---

# 桌面 GUI 端到端测试：输入法与注入的坑

> **中文名**：桌面 GUI 端到端测试 ｜ 目录与 `name`：`dev-desktop-gui-e2e-testing`

**适用**：需要"启动应用 → 点界面 → 输入文字 → 截图断言"的自动验证（本项目优先 Compose Desktop 桌面端）。
**不适用**：纯逻辑单测、Compose UI 测试（`runComposeUiTest`，不需要真实窗口）。

⚠️ **先想清楚要不要注入**：文案 / 布局 / 状态这类断言能用 `runComposeUiTest` 覆盖的，
**一律写单测**（详见 §3.5）。注入是最后手段，成本高且结论依赖环境（IME、DPI、提权状态）。

本技能只写**可复用的判据与手法**；产品侧的 IME 验收标准见
[`05-acceptance.md`](../../../.design/草案/05-acceptance.md) C1/C2。

---

## §1 头号坑：中文输入法会吞掉一切合成按键

**症状**（实测于微软拼音激活状态）：

| 你发的键 | 实际结果 |
|---|---|
| 字母 `o` `k` | 输入框里出现 `ok`，但应用的 `onPreviewKeyEvent` **一个 KeyEvent 都没收到** |
| 空格 | 丢失（`line one` → `lineone`） |
| `Enter` | 不发送、不换行，什么都不发生 |
| `Shift+Enter` | 不换行 |
| `/sk` | 不进入文本框，只在屏幕底部弹输入法候选框 |

**判据（30 秒定位）**：在应用的按键处理里临时打一行 DEBUG 日志
（例如 `Composer.kt` 的 `handleComposerKey`），然后跑脚本。
**日志里连字母 KeyEvent 都没有** → 按键被 IME 在 AWT/TSF 层消费，**不是应用 bug**；
日志里有 KeyEvent 但行为不对 → 才是应用逻辑问题。定位完记得把日志删掉。

**为什么文字还是"进"了输入框**：IME 直接把提交结果写进文本通道，绕过 KeyEvent。
所以"能看到字"完全不能证明按键到达了应用。

> 反过来说，这条通道**本身**是要验收的功能：候选框能不能弹、commit 后文本对不对，
> 验证方法见 §2.7（完整中文输入链路）。两者不要混为一谈。

---

## §2 可靠手法（按优先级）

### 2.1 用剪贴板粘贴代替逐字输入 ★首选

要验证的是"有这段文本之后应用怎么反应"，不是"键盘能不能打字"：

```powershell
Set-Clipboard -Value "要验证的文本（可多行、可中文）"
# 点中输入框后再按 Ctrl+V
keybd_event(0x11, 0, 0, 0); Key(0x56); keybd_event(0x11, 0, 2, 0)
```

- 走文本通道，不受键盘布局 / IME 状态影响；
- 一次注入整段，**能验证多行与自动高度**；
- 中文无需 `SendKeys`（`SendKeys` 对非 ASCII 会直接抛异常）。

注意：应用若拦截 `Ctrl+V`（本项目为了图片粘贴 / 大段折叠 / 路径识别），
必须保证**普通文本回落到默认粘贴**，否则粘贴会"消失"——
这正是端到端要覆盖的回归点。

### 2.2 点击代替按键

要触发"发送/确认"，优先**点按钮**；只有专门验证 Enter 键行为时才用键盘，
并明确写下"这一条依赖键盘、可能被 IME 干扰"。

### 2.3 实在要按键：先切英文，但仍不可靠

给输入框焦点后发一次孤立 `VK_LSHIFT`（`0xA0`）按下+抬起，多数中文 IME 会切到英文直通。
**不可靠**：`Ctrl+A`、切换焦点、点击别的控件都可能把状态切回去。
所以它只能是"补充手段"，不能作为验证结论的唯一依据。

### 2.4 方向键 / Delete / Home 必须带 `KEYEVENTF_EXTENDEDKEY` ★

`keybd_event` 对**扩展键**（↑↓←→、Insert/Delete/Home/End/PgUp/PgDn、右 Ctrl/Alt、小键盘除号与回车）
必须带标志位 `0x0001`，否则被当作小键盘的等价键（↑ 变成 NumPad8），应用一个事件都收不到：

```powershell
# 按下: EXTENDEDKEY(0x1)；抬起: EXTENDEDKEY|KEYUP(0x3)
keybd_event(0x26, 0, 1, 0); keybd_event(0x26, 0, 3, 0)   # ↑
```

**实测教训**：本仓库"输入框按 ↑ 取发送历史"连续多轮判为"不生效"，实际是脚本没带扩展键标志；
应用侧逻辑一直是对的（Compose UI 测试一直是绿的）。**先怀疑注入，再怀疑应用** ——
判据是同一次会话里普通键（Enter/Backspace/Ctrl+V）都生效、只有方向键类无效。

### 2.5 点击坐标偏移：先说清"点击落在哪"★本轮新增

**症状**：`+` 按钮点坐标有效，点输入框毫无反应（连 `onFocusChanged` 都不触发），
看起来像"输入框不可点击"的应用 bug。

**根因**：`GetWindowRect` 返回的矩形可能**大于窗口实际可见区域**（DWM 边框/阴影未计入内容区）。
`CopyFromScreen` 按这个矩形截图，于是截图底部**混入了窗口之外的内容**，
而 `SetCursorPos` 用的是真实屏幕坐标 —— 两者差几十像素。

**判据（一眼识别）**：截图最底部出现**不属于该窗口**的内容（终端文字、桌面背景、别的窗口边缘）。
一旦看到，就不要相信截图坐标，必须校准：

```powershell
# 1. 找一个已知可点的控件（如新建按钮），用截图坐标点击，确认成功
# 2. 输入框用「截图坐标 - 偏移」逐个试，直到出现日志/光标变化
foreach ($y in @(820, 840, 855, 865, 875)) {
    Click-At $h 860 $y
    Key 0x41
    if ((Get-Content $log | Select-String 'DIAG').Count -gt 0) { break }   # 命中
}
```

本仓库实测：1440x900 窗口上偏移约 **57px**（截图 y=877 的输入框，实际要点 y=820）。

### 2.5.1 先声明 DPI 感知，否则截图与鼠标两套坐标系 ★

PowerShell 默认是 **非 DPI 感知**进程：系统会给它一份"逻辑坐标"。
高缩放比（125%/150%/175%）下，`GetWindowRect` 与 `CopyFromScreen` 用的是**缩放后的坐标系**，
而 `SetCursorPos` 打进去的是**物理像素** —— 两者差几十到几百像素，表现为"截图对得上、点击点不中"。

```powershell
Add-Type @'
using System.Runtime.InteropServices;
public static class D { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
'@
[void][D]::SetProcessDPIAware()      # 必须在任何 GetWindowRect / 截图 / 点击之前调用一次
```

判据：窗口 `GetWindowRect` 的尺寸应与应用自己记的窗口尺寸一致（本仓库 1440x900）。
不一致（例如报成 2560x1600）就是没声明 DPI 感知。

### 2.6 切输入法布局用 Win+Space，别指望单次 Shift ★本轮新增

孤立 `VK_LSHIFT(0xA0)` 或 `VK_SHIFT(0x10)` 在微软拼音下**经常无效**（本轮两种都试过，layout 始终 `0x8040804`）。
可靠的切法是模拟系统快捷键：

```powershell
keybd_event(0x5B,0,0,0);  keybd_event(0x20,0,0,0)   # Win down, Space down
keybd_event(0x20,0,2,0);  keybd_event(0x5B,0,2,0)   # Space up, Win up
# 用 GetKeyboardLayout(GetWindowThreadProcessId($h)) 校验：0x8040804(中文) → 0x4090409(英文)
```

**不要省略校验**：`GetKeyboardLayout` 是唯一能证明"切换真的生效"的证据，
否则后面所有的"打不出字"都无法归因。

### 2.7 验证中文输入：拼音 → 候选框 → 数字键选词 ★本轮新增

想验证"中文输入法能不能用"，光打字母不够（IME 可能只是在直通模式）。**完整链路**：

1. 确认 layout 是中文（`0x8040804`）且输入框已有焦点；
2. 逐键发 `n i h a o`（`0x4E 0x49 0x48 0x41 0x4F`），每键间隔 130–150ms；
3. 截图：应看到**候选框**（`1 你好 2 你号 3 拟好 …`）+ 输入框里的组合串（`ani'hao`）；
4. **用数字键 `1`（`0x31`）选词**，不要用空格；
5. 截图：输入框应变成 `你好`。

**为什么不用空格选词**：`keybd_event` 注入的空格会被**应用与 IME 同时接收** ——
应用把它当普通空格插入，IME 又同时关闭候选框，结果是 `anihao`（拼音残留）而不是"你好"。
数字键没有这个二义性。**真实键盘不受影响**，这只是注入侧的现象。

**这条路能证明什么**：候选框位置、组合态渲染、IME commit → `onValueChange` 全通，
即 BasicTextField 的 IME 通道是好的。**不能证明**的是真实键盘的空格/Enter 时序 —— 那只能人工验。


---

## §3 抢前台：Windows 不允许后台进程抢焦点

`SetForegroundWindow` 从后台进程调用会返回 `False`，鼠标/键盘会打进用户当时的窗口（**危险**）。

```powershell
ShowWindowAsync($h, 6)   # SW_MINIMIZE
Start-Sleep -Milliseconds 400
ShowWindowAsync($h, 9)   # SW_RESTORE —— 还原时通常能拿到前台
```

**每一个**鼠标和键盘动作前都校验（不只是点击前——`Ctrl+A` + `Delete` 打到别的窗口会删掉用户的文件内容），
不匹配就立即中止，绝不盲目注入：

```powershell
if ([W]::GetForegroundWindow() -ne $h) { Write-Host "foreground lost"; exit 2 }
```

### 3.1 别只按窗口标题找进程 ★真实事故

原版 Electron 客户端与本项目 Compose 桌面端历史上**窗口标题都是 `Hermes`**
（本仓库 v0.3.2 起客户端标题是 `Hermes Desktop Compose`，但用户机器上可能跑着旧版本）。
按 `Get-Process | Where-Object MainWindowTitle -eq 'Hermes*'` 会命中**原版应用**，
于是按键打进了正在使用的真实客户端（那里有真实会话，输入框就是生产输入框）。

**可靠做法**：按可执行文件路径 / 命令行区分，并在启动应用时自己记住 pid。

```powershell
# JVM 进程的窗口句柄就挂在 java(w).exe 上，Electron 是 Hermes.exe
$proc = Get-Process java,javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { Write-Host 'app window not found (已退出？)'; exit 1 }
```

**前置断言**：脚本开始先确认窗口确实存在且**是刚启动的那个**（日志里有 startup 行 +
`MainWindowHandle -ne 0`）。应用可能已经退出，此时所有注入都会打到别处。

### 3.2 前台句柄是 0 就立刻停手 ★

`GetForegroundWindow()` 返回 **0** 表示当前会话没有可见桌面（锁屏、RDP 断开、快速用户切换）。
此时 `ShowWindowAsync` / `SetForegroundWindow` 全部无效，而且**键鼠会打进另一个会话的登录界面**。

```powershell
if ([W]::GetForegroundWindow() -eq 0) { Write-Host 'session not interactive (locked/disconnected)'; exit 3 }
```

对照判据（本仓库实测）：窗口站仍是 `WinSta0\Default`、屏幕尺寸读得到、应用 `Responding=True`，
但前台句柄恒为 0、`LogonUI` 出现在**另一个 SessionId** —— 这不是 bug，是会话不可见。
**不要试图绕过**（例如 `PrintWindow` 截图 + `PostMessage` 注入），结果不可信。

### 3.3 原生拖放（OLE DnD）无法脚本化
文件拖入走的是 OLE 拖放，需要**真实的拖放源**（资源管理器发起）。
`SendInput` 只能移动光标，不会生成 `IDropTarget` 调用；同进程 `DoDragDrop` 也不跨进程生效。
所以拖放的验证只能：① 逐项测它下游的 RPC（`file.attach` / `image.attach`）；② 让人工拖一次确认交互。

实现侧注意：CMP 的 `Modifier.dragAndDropTarget` 在 `androidx.compose.foundation.draganddrop`
（不在 `ui` 包），详见技能 [`compose-multiplatform-desktop`](../compose-multiplatform-desktop/SKILL.md) 的硬坑表。

### 3.4 抢不到前台时：用"临时置顶截图"，不要硬塞注入 ★

`ShowWindowAsync(6/9)` + `SetForegroundWindow` 有时仍然失败（用户正在别的窗口打字、
`AttachThreadInput` 也被拒）。此时**只想要一张截图**的话，不需要前台：

```powershell
# HWND_TOPMOST = -1；SWP_NOSIZE|SWP_NOMOVE|SWP_SHOWWINDOW = 0x43
[void][S]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x43)
Start-Sleep -Milliseconds 700
# CopyFromScreen 截窗口矩形
[void][S]::SetWindowPos($h, [IntPtr](-2), 0, 0, 0, 0, 0x43)   # HWND_NOTOPMOST 复原
```

**只用于截图**：置顶期间窗口会盖住用户的窗口，必须立刻复原；期间**不要**注入键鼠
（坐标仍可能打到别处）。要验证交互就回到 §2.1（剪贴板）或改用单测。

### 3.5 elevated 进程的窗口：注入与关闭都会被 UIPI 丢弃 ★

应用以管理员运行时（`app.elevate=ask` 且用户点了"是"，或由计划任务以
`RunLevel=HighestAvailable` 拉起），普通权限脚本对它的窗口**做不了任何事**：

| 动作 | 结果 |
|---|---|
| `PostMessage(WM_CLOSE)` | 返回 `False` |
| `CloseMainWindow()` | 返回 `True`，窗口不动 |
| `SetCursorPos` + `mouse_event` | 点击不进入该窗口 |
| `SetForegroundWindow` | 返回 `False` |

所以验证顺序应该是：

1. **Compose UI 测试**（`runComposeUiTest`）覆盖文案 / 布局 / 状态 —— 本项目已有多例，
   比注入可靠一个数量级；
2. **日志 + 进程状态**（`logs/startup.log` 的 `silent` / `elevated` / `autostart` 行，
   `MainWindowHandle`、`MainWindowTitle`）；
3. 真要注入：把配置文件里的提权开关临时改 `never`（**先备份，验完还原**），
   重启后以普通权限跑；结论里注明"被测环境已降权"。

> 反例：为了点开"关于"页去调 `SetCursorPos`，点了三次都没反应，实际是窗口 elevated ——
> 那次正确做法是写一个 `AboutPageTest`（两行断言，之后永久有效）。

---

## §4 结果判定：截图，不要相信"操作成功"

```powershell
$rect = New-Object W+RECT
[void][W]::GetWindowRect($h, [ref]$rect)
$bmp = New-Object System.Drawing.Bitmap ($rect.Right - $rect.Left), ($rect.Bottom - $rect.Top)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $w, $h))
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
```

- 脚本"成功结束"只证明按键发出去了，**不证明界面做了对的事**；
- 关键节点各截一张（输入后 / 动作后 / 完成态），失败时能直接看出卡在哪；
- 窗口 rect 与截图坐标系同为物理像素，DPI 缩放下也一致；
- 只截目标窗口区域，避免把用户桌面其他内容带进证据。

---

## §5 数据隔离与善后（必做）

端到端会**真的写后端数据**，必须先隔离：

1. 用 `session.create` 建**专用测试会话**，先发一条无害种子消息让它落库、出现在列表顶部；
2. 所有验证都在这个会话里做；
3. 结束时 `session.close` + `DELETE /api/sessions/{id}` 删掉；
4. 误操作（发到了真实会话）立刻收拾：
   - `session.interrupt` 停掉这一轮（避免 agent 继续按旧上下文执行任务）；
   - `session.undo` 回滚刚写入的用户消息与回复。

**绝不在有上下文的真实会话里做"随便发一句"的测试**——agent 可能把它当成继续执行旧任务的指令。

---

## §6 检查清单

跑 GUI 端到端前逐条确认：

- [ ] **能不能用 Compose UI 测试（`runComposeUiTest`）代替注入？** 文案 / 布局 / 状态断言一律优先单测（§3.5）
- [ ] 进程是否 elevated？是的话注入必然失效，别浪费时间（§3.5）
- [ ] 是否必须注入按键？能换剪贴板就换
- [ ] 若必须按键：先切英文（用 Win+Space 并校验 layout），并在报告里标注"此结论依赖 IME 状态"
- [ ] 脚本是否先调了 `SetProcessDPIAware()`？没调就别信坐标（§2.5.1）
- [ ] 截图底部有没有混入窗口之外的内容？有就先校准点击坐标（§2.5）
- [ ] 窗口确实存在（`MainWindowHandle -ne 0`）、且按**进程名**而非窗口标题定位
- [ ] `GetForegroundWindow() != 0`（是 0 就别动，会话不可见）
- [ ] 每次鼠标/键盘前校验前台窗口
- [ ] 关键节点截图（至少：动作前、动作后）
- [ ] 数据隔离：专用会话 + 结束后删除
- [ ] 改过的用户配置（提权开关等）验完已还原
- [ ] 临时的置顶 / 临时脚本 / 临时截图已复原与清理
- [ ] 失败时能区分"注入没到"与"应用逻辑错"（临时 DEBUG 日志）

---

## §7 反例（都踩过）

| 做法 | 为什么错 |
|---|---|
| `SendKeys.SendWait("{ENTER}")` | pwsh 默认 MTA 线程，`SendKeys` 本身不可靠；且 IME 会吃掉 Enter |
| `SendKeys` 打中文 | 非 ASCII 字符直接抛异常 |
| "输入成功了 → 说明 Enter 也能用" | 输入走文本通道，Enter 走 KeyEvent，两条路完全不同 |
| "文字没变 → 一定是应用没实现" | 先查注入：方向键缺 `EXTENDEDKEY`、IME 吞键、焦点丢了，三种都表现为"没反应" |
| 连续做完"输入 + 发送 + 断言"再看结果 | 失败时无法定位是注入、是应用、还是断言写错；要拆开并截图 |
| 直接点坐标不校验前台 | 会点到用户正在用的窗口上（本仓库实测误点过其他应用） |
| 用窗口标题 `Hermes` 找窗口 | 原版 Electron 客户端同名，实测把按键打进了真实客户端；应按进程名 `java/javaw` + `MainWindowHandle -ne 0`（本仓库客户端标题已改为 `Hermes Desktop Compose`，但用户机器上可能跑着旧版本） |
| 只在点击前校验前台 | 键盘动作同样危险：`Ctrl+A`+`Delete` 打到编辑器/资源管理器会丢用户内容 |
| "点输入框没反应 → 输入框坏了" | 先看截图底部有没有混入窗口外的内容：有就是坐标偏移（§2.5），本仓库实测差 57px |
| 孤立的 `Shift` 切中英 | 本轮两种 VK 都无效，`Win+Space` 一次成功（§2.6）；切完必须用 `GetKeyboardLayout` 校验 |
| 空格选词验证中文输入 | 注入的空格会被应用和 IME 同时接收，得到 `anihao` 而不是"你好"；用数字键选词（§2.7） |
| 用注入点击去验证"关于页有没有显示产品名" | 窗口 elevated 时点击被 UIPI 丢弃（三次无反应）；这类断言写成 `runComposeUiTest` 两行就够（§3.5） |
| 不声明 DPI 感知就截窗口 | `GetWindowRect` 给逻辑坐标、`SetCursorPos` 吃物理像素，高缩放比下差几十到几百像素（§2.5.1） |
| 抢不到前台就反复 `SetForegroundWindow` | 只想要截图时用临时 `HWND_TOPMOST`（§3.4）；要交互则换剪贴板或单测 |
| 验完把用户的提权/主题配置留在测试值 | 改配置前备份，验完还原（§6 清单最后两条） |

---

## §8 相关

- 产品侧 IME 行为与验收：[`05-acceptance.md`](../../../.design/草案/05-acceptance.md) C1/C2
- composer 实现（按键分派与 `composition` 判断）：`composeApp/src/commonMain/kotlin/dev/shinglem/hermes/feature/composer/Composer.kt`
- 构建/镜像类坑：[`04-构建环境与镜像.md`](../../../.design/详细设计/工程与代码/04-构建环境与镜像.md)
- 注入失败但其实是系统/提权问题：[`dev-windows-integration-debug`](../dev-windows-integration-debug/SKILL.md)
- 验证通过后怎么落地到本机安装：[`global-release-patch`](../global-release-patch/SKILL.md)

---

## §9 顺带发现的产品 bug（GUI 端到端才暴露的两类模式）

用剪贴板 + 键盘实跑才发现的问题，单测与 Compose UI 测试都不容易覆盖：

| 现象 | 根因 | 判据 |
|---|---|---|
| 接受补全后得到 `/skinill`（目标 `/skin`） | 替换区间用的是**请求发出时**的光标，异步返回后文本已变长，尾部残留 | 任何"异步结果 + 文本区间"的组合都要在**应用时刻**重算区间 |
| 按 Enter 什么都不发生 | 补全浮层还在但已无有效项，`handleComposerKey` 无条件 `return true` 吞掉按键 | 按键处理里"接管"必须和"确实处理了"绑定：返回布尔值，未处理就放行 |
| 接受补全后浮层不消失、Enter 一直停在"接受" | 接受后立刻重新请求补全，同一条目又匹配自己 | 接受结果不要触发同一套自动补全，等用户下一次输入 |

**通用结论**：GUI 端到端不只验证"能不能用"，它专门暴露**状态机在时间维度上的错位**
（请求时的快照 vs 应用时的现状、按键接管 vs 实际处理）。这类问题单测要写"返回前文本又变了"的用例才能覆盖。
