# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，用中文表述。
版本号口径见文末「版本号说明」——**客户端里程碑版本**与**发布 tag** 是两套数字。

---

## [未发布]

### 新增

- **四套新配色风格**：`options.ui.style` 从 2 个扩到 6 个 ——
  `material`（默认）/ `paper`（纸面）/ `ocean`（海洋蓝）/ `sage`（灰绿）/ `plum`（梅紫）/ `amber`（琥珀）。
  每套都有亮暗两版（明暗仍由 `options.ui.theme` 决定），改完立即生效；设置页的选项直接显示中文名。
- **内置 Inter / JetBrains Mono 字体**：换到 Temurin 后 JBR 自带的 43 个字体不复存在，
  界面观感会变。把 JBR 上实际用到的两族随应用分发（SIL OFL 1.1，见
  `composeResources/files/LICENSE-*.txt`），`MaterialTheme(typography = appTypography())` 统一接管；
  中文仍由系统字体回退（Inter 没有 CJK 字形）。
- **JCEF 自备 Chromium**：Temurin 没有 `jcef` 模块，改由 `me.friwi:jcefmaven` 提供 `org.cef.*`、
  `me.friwi:jcef-natives-windows-amd64` 提供 native bundle（构建期解压到 `build/cef/bin`，
  成品放 `<安装目录>/cef`）。CEF 的加载器被换成按**绝对路径**加载，所以与 `java.home`、
  `java.library.path` 都无关；`MARKDOWN_CEF_DIR` / `-Dmarkdown.cef.dir` /
  `MarkdownConfig.cefNativeDir` 三个口子任选。详见 `.design/详细设计/工程与代码/11-JCEF集成.md`。

### 变更

- **审批 / 提问只显示在发起它的会话里**：后端是"全局在等"，但界面之前把所有待处理请求都画在当前
  会话，切到别的对话也会看到别人的审批，容易误当成自己要处理的事情。现在按会话过滤
  （`ChatState.visibleApprovals` / `visibleClarify`），归属不明的仍显示（否则永远没人处理）。
- **系统通知可带按钮**：审批通知改成右下角的**通知窗**，直接点「允许一次 / 拒绝」就地处理，
  不必先把窗口拉出来再找卡片（系统气泡不支持按钮）。纯提示（一轮完成等）仍走托盘气泡。
- **可选接入 Windows 原生通知（toast）**：设置 → 桌面 → 通知里多了「使用系统原生通知」开关，
  **默认关**（用应用内 M3 卡片，零系统痕迹）。打开后由 `nucleus.notification-windows` 发原生 toast
  （进通知中心、带按钮），并创建/更新开始菜单快捷方式 —— 这是 Windows 的 AUMID 归属硬要求
  （桌面、其它目录、纯注册表键都不行，四组对照见 `.design/详细设计/工程与代码/12-通知窗.md`）。
  快捷方式文件名与 AUMID 都带 `<home>` 哈希，多份便携包互不覆盖；快捷方式被删时设置页会显示
  原因并回退到自绘窗，不静默失效。
- **已存在的开始菜单快捷方式不再每次重写**：打开开关时先看 `<产品名> (<home 哈希>).lnk` 在不在，
  在就沿用（`REQUIRE_NO_CREATE`，实测 mtime 前后一致），不在才创建。判据只认文件名 ——
  名字里带 `<home>` 哈希，同名即同一实例。快捷方式的 target 由库取**当前进程的 exe**
  （`GetModuleFileNameW`），没有写死路径：成品指向自己的 `hermes-desktop-compose.exe`，
  开发态指向跑它的那个 `java.exe`。

- **打开原生通知后任务栏图标变成 JVM 咖啡杯**：`initialize` 会改进程的 AUMID，Windows 随后按它找图标，而该 AUMID 没注册图标就会退回 `java.exe` 的图标。现在打开时注册 `HKCU\Software\Classes\AppUserModelId\<aumid>` 的 `DisplayName` / `IconUri` / `ShowInSettings`，关掉开关时连同开始菜单快捷方式一起删掉。
- **运行时基线从 JBR 25 换成 Temurin 27**（`local.properties` 的 `hermes.rt.dir`）。
  根因：JBR 有意把触摸事件合成为**滚轮**（实测同一份代码 Temurin 是 41 个 `Move`、0 个 `Scroll`，
  JBR 是 0 个 `Press`、23 个 `Scroll`），Compose 的 `scrollable` 只认拖动、滚轮又跳行，
  怎么调都「不跟手」。换官方 JDK 后触摸变成正常的 press/move，全部 native workaround 删除
  （`:modules:win-touch`、`TouchScrollRegistry`、`WM_TOUCH` 子类化都不再需要）。
  代价：JBR 自带的中文字体观感与 JCEF 需要自己补（见下两条）。详见
  `.design/详细设计/工程与代码/10-Windows触屏输入.md`。

### 修复

- **触屏无法滑动会话列表**：Compose Desktop 在 Windows 上把触屏合成为鼠标拖动，而
  `LazyColumn` 的 `scrollable` 主动拒绝鼠标拖动（`CanDragCalculation = { type != Mouse }`），
  手指滑动完全无效；同时吸底只认滚轮事件，`followTail` 恒为 `true`，列表被不断拽到底部
  （表现为「迅速翻很多行然后不动」）。新增 `Modifier.touchDragScroll`（`draggable` +
  `dispatchRawDelta`，**不**用 `startDragImmediately`，否则会吞掉点击与长按），
  拖动即暂停跟随，发新消息时恢复。
- **审批 / 提问请求在别的会话或窗口不前台时没有提示**：`approval` 与 `clarify` 到达时判断
  用户是否正看着该会话；没看着就弹系统通知（含重连后 `approval.pending` 补拉），同一请求只提醒
  一次；**点击通知或托盘图标直接跳到发起它的会话**（跨 profile 也会等列表就绪后选中）。
- **`clarify` 工具无法使用**：之前未注册 `clarify` handler，后端只能收到 `-32601`。
  现在弹出可作答卡片（单选 / 多选 / 自由输入 / 批量），支持取消，并处理 `request.cancel` 撤回。
- **切到别的会话再切回来，回复停在半截、看起来卡死**：切走时保存会话快照（含流式段落），
  切回先恢复再判断；切走期间有新内容会在切回时补拉完整 transcript，若那轮已结束则静默重读。
  **切走期间的事件现在继续累积到该会话的快照上**（影子状态）：只标记"脏"再等补拉不够 ——
  切回时那轮往往还在跑（审批就是在等它），补拉会被跳过，而丢弃的增量再也补不回来，
  界面就停在切走前那一帧（用户实测必须点刷新）。
- **界面产品名统一为 `Hermes Desktop Compose`**：窗口标题、托盘提示、系统通知标题、
  「已在运行」提示框、关于页都改用同一常量（`core/app/ProductInfo.kt`），
  空态文案里的产品名也用占位符取它。裸的 `Hermes` 保留给后端 agent / payload 进程
  （消息里的助手署名、给 Hermes 发消息、连不上后端）。
- **paper 主题的文字太淡**：正文 `#5A524A`（7.4:1）→ `#2A2521`（14.8:1），
  次要文字 `#9A9080`（**3.0:1**）→ `#6A6055`（6.0:1）；暗色版同步加深。
  次要文字用在分组标题、时间戳、输入框提示、skill 说明这些地方，之前低于 WCAG AA 的 4.5:1。
- **纸面 Markdown 跟着变淡**：`markdown_style = paper`（或 `follow` + paper 风格）改用宿主色板，
  排版仍是 Outside Paper（标题小一档、行高 1.6），只是正文/次要文字取加深后的色值；
  `follow` + 其他风格时 Markdown 直接跟随当前 M3 色板。
- 对比度门槛固化为单测 `AppPaletteContrastTest`（正文 ≥ 11:1、次要 ≥ 4.5:1），改色先过它。
- **消息区两侧留白过窄，触屏容易误选文本**：原来固定 `horizontal = 20.dp`，手指落在文字上会被
  `SelectionContainer` 接走变成**选中**而不是滚动。改成随窗口宽度自适应（取可用宽度的 6%，
  夹在 24–96 dp），留白区仍属于外层 `touchDragScroll` 的拖动区域，滑动行为不变。
- **打开原生通知后任务栏多出一个图标**：`initialize` 会把**进程**的 AUMID 换成通知用的那个，
  而任务栏按 AUMID 分组，于是同一个应用变成「固定项 + 运行窗口」两项。toast 靠显式 AUMID 发送、
  不依赖进程这个值，所以初始化后立刻用 `SetCurrentProcessExplicitAppUserModelID("")` 清掉
  （传 `NULL` 会返回 `E_INVALIDARG`，Windows 只认空串）。
- **运行中删掉开始菜单快捷方式后通知静默消失**：库的 `show` 在 `.lnk` 缺失时不报错也不弹，
  而 `ready` 仍是 `true`，于是既不提醒也不回退。现在 `show` 先自查快捷方式，缺失就置为不可用
  （设置页显示原因）并回退到应用内通知窗。重新打开开关会重建快捷方式。
- **`createDistributable` / MSI 装出来的应用一启动就 `NoClassDefFoundError: javax/naming/NamingException`**：
  compose 插件默认只按 jdeps 静态分析带 7 个模块，缺 `java.naming`。给 `nativeDistributions`
  显式声明 `modules(...)`（与绿色版 `rt/` 同一套 16 个模块）。绿色版本来就有，只在 MSI /
  本地 `createDistributable` 上暴露。

---

## [0.3.2] · 2026-11-02

> 本版是 **patch 发布**：只提供 `bin/App.jar`，覆盖旧文件后重启客户端即可，
> 配置与数据目录不受影响。

### 修复

- **开机自启会弹出主窗口**：系统里可能同时存在两条本产品的自启任务 —— 早期版本的固定名
  `HermesCompose`（`Task version="1.3"`，**没有** `--silent`）与现行的 `HermesCompose-<home hash>`。
  名字对不上，谁也不会去清理，于是旧任务先拉起一个**带窗口**的实例（还白弹一次 UAC），
  带 `--silent` 的新任务晚 20 秒起来后被单实例挡掉、静默退出。
  现在对账同时校验 `<Command>` 与 `<Arguments>`（之前只比路径，无参任务永远被判为健康、从不重写），
  并在已提权时删掉指向**同一个 exe** 的冗余任务。路径比对仍是必须项：指向别处 exe 的任务
  可能是另一份正在用的安装，一律不碰；写当前任务失败时不删任何东西。
- **设置页的「失效自启任务」列表**也认早期固定名，便携包挪位后的残留不再漏列。

### 升级

- 覆盖 `bin/App.jar` 后重启客户端。
- 若系统里还留着 `\HermesCompose`（查看：`schtasks /Query /TN "\HermesCompose"`），
  新版第一次**提权**启动会自动删掉它；也可以手动
  `schtasks /Delete /TN "\HermesCompose" /F`（需管理员）。删完可确认只剩
  `HermesCompose-<hash>`，且它的 `Arguments` 是 `--silent`。

---

## [0.3.1] · 2026-11-01

> 本版是 **patch 发布**：不重新打包完整安装包，只提供可直接覆盖升级的文件
> （`bin/App.jar` 与一个模块 jar）。已在 0.3.0 上的用户按 Release 说明替换目录里的文件即可，
> 配置与数据目录不受影响。

### 新增

- **本地后端的监听地址 / 端口 / 凭据可配置**（设置 → 后端连接，沿用现有设置项，改完重启生效）：
  `backend.local.host`（默认 `127.0.0.1`）、`backend.local.port`（默认 `0` = OS 分配）、
  `backend.local.token`（默认空 = 每次启动随机；填了就跨重启固定）。
  固定端口 + 固定 token 后，别的机器可以 `ssh -L` 或 Tailscale 隧道过来、用 `remote` 模式连；
  浏览器也能直接开同一个端口。注意 `0.0.0.0` 会让后端进入 gated 模式（要求口令/OAuth），
  本客户端只发 session token，那条路连不上 —— 官方建议就是「回环 + 隧道」。

- **思考过程按 Markdown 渲染（仅限已写完的段）**：流式期间的 `reasoning.delta` 仍是纯文本
  （逐 token 重解析会把 CPU 峰值翻倍，而且折叠态看不见内容），一轮结束后落地的
  `ChatItem.Reasoning` 用 `MarkdownText` 渲染，代码块 / 列表 / 表格不再显示裸语法。
  展开态同时补上限高（320dp）与内部滚动 —— 之前的注释承诺了限高但代码里没有。

- **后台任务的显示与操作**：composer 上方新增状态条，`process.list` 每 5s 轮询本会话的后台进程
  （`terminal(background=true)` 起的命令），显示命令、运行时长 / 退出码，可就地点开输出
  （`agent.terminal.output` 增量实时追加）、用 `process.kill` 终止单个进程、或隐藏已结束的行。
  终止刻意不走 `process.stop` —— 那是 registry 全局 kill_all，会连别的会话的进程一起杀。

### 修复

- **用量条（上下文占用 / token 消耗）重新可见**：两个原因叠在一起 ——
  ① `session.usage` 之前只在轮结束时刷新，而 `session.usage` 需要 runtime id，打开历史会话根本不会
  `session.resume`，所以顶栏那条一直是空的（切回已 resume 的会话也不会补）。现在打开会话后在后台补一次
  resume，并同步刷新用量、审批与后台任务（切走期间会用 key 校验丢弃过期结果）。
  ② 采集到的累计 token（`totalTokens` / `calls`）从来没进过界面：现在标签显示
  `已用 / 上限（百分比） · 累计 X`，点开的明细里再给 `累计 X tokens · N 次调用`。

- **PTY 初始窗口尺寸**：spawn 时补上 `setInitialColumns` / `setInitialRows`（未布局时默认 120×30，
  resize 钳制到 ≥40×10）。子进程读的是**创建时**的窗口宽度，PowerShell 7 启动横幅按它算
  `PadRight(width - N)`，宽度异常时会拿到负数并直接 FailFast 崩掉整个 shell。

- **会话列表闪烁**：`session.list` 刷新时不再无条件置 `Loading`（那会让 `sessions` 变空、
  整片列表被换成骨架行）。`sessions.changed` 在流式期间会连推多条，叠加 400ms 去抖后
  就成了"列表不停闪"。现在只有首次加载 / 切 profile 才显示骨架，同 profile 的刷新是静默替换，
  失败时保留旧列表 + 错误条。

- **流式分段顺序**：思考 / 工具 / 正文改为按到达顺序排列的有序段落
  （`StreamingState.segments` + `StreamSegment`）。此前分字段堆放，一轮里混了工具调用时
  会变成"所有思考在最上面、所有正文在中间、工具全在最后"，要切走再切回来才显示正确；
  `message.interim` 现在会封段，`message.complete` 只替换尾部未封段的正文，不再整轮覆盖。
- **审批应答恢复**：连接建立后补发 `client.capabilities {server_requests:true}` ——
  缺这一步后端认为本端不支持 server→client 请求，审批请求根本不会发出，
  危险命令（如 `Get-CimInstance`）直接被撤回并判 BLOCKED。同时补上
  `approval.received`（卡片上屏确认）、`approval.pending`（重连补拉）、
  `approval.respond`（补拉卡片的应答路径）与 `request.cancel`（撤回时移除卡片）。
  本端兜底超时从 240s 提到 330s（后端是 300s）。

---

## [0.3.0] · 2026-11-01（待发布）

> 本节可直接作为 GitHub Release 正文使用（去掉本行，标题层级按需调整）。
> 一句话：**终端换成 Compose 原生渲染，对话主链路补上「看得全 / 控得住 / 看得见」**。

### 重点

- **终端渲染重写**：终端从 JediTerm + `SwingPanel` 换成自维护的 BossTerm 裁剪版，改为 Compose Canvas 原生绘制。
  Swing 互操作带来的 heavyweight 遮挡（弹框被盖住）、IME 组合态失控、焦点跨边界三类问题随之消失，
  中文输入的组合态在 Compose 侧接管。
- **终端默认 shell 改为 `pwsh`**：装了 PowerShell 7 就用 7；没装按 `powershell`（5.1）→ `cmd` 逐档降级。
- **对话深度**：历史分页、导出、分支 / 复制、生成中纠偏（steer）、重发 / 回退、子代理实时面板、上下文用量条、会话内容搜索。
- **桌面集成**：托盘气泡通知、后端更新检查、关于页、`Ctrl+K` 命令面板、链接走外部浏览器。

### 新增

**对话**

- 历史分页：向上滚动按需加载更早的消息，插入时用锚点补偿，视口不跳位。
- 导出会话为 **JSON** 与 **Markdown** 两种格式（含全量消息，文件名按标题清洗后落地）。
- **复制为新会话**（整份克隆）与**从某条消息分支**（截到该条）两种语义分开。
- **生成中纠偏**：回答还在输出时直接说话会走 `session.steer`，不再必须停下来等。
- **忙时直接发第二条**：后端排队，发送按钮在生成中不再禁用。
- **重发 / 回退到某条消息**：破坏性操作，界面二次确认。
- **上下文用量条**：状态条显示当前上下文占用，接近上限时有提示；明细按需拉取。
- **推理块折叠**：思考过程默认收起，不占主视野。
- **子代理面板**：roster + 实时 tail 流，可直接 steer / interrupt。
- **会话内容搜索**：命中行显示后端给的标题与片段，与标题过滤是两个独立模式。
- 消息里的链接点击走系统外部浏览器。

**桌面与设置**

- **托盘气泡通知**：轮次完成 / 失败时提示（仅在窗口非前台且开关打开时发），点击回到对应会话。
- **命令面板**：`Ctrl+K`。
- **关于页与更新检查**：`/api/hermes/update/check?force=true`，区分「有新版本 / 已是最新 / 检查失败」。
  ⚠️ 这是**后端**更新检查，不是客户端自身更新。
- 设置补页：通知 / 快捷键 / 关于。
- 终端「选中加入对话」：选中文本可直接引用进输入框。

### 变更

- **审批 / 提问只显示在发起它的会话里**：后端是"全局在等"，但界面之前把所有待处理请求都画在当前
  会话，切到别的对话也会看到别人的审批，容易误当成自己要处理的事情。现在按会话过滤
  （`ChatState.visibleApprovals` / `visibleClarify`），归属不明的仍显示（否则永远没人处理）。
- **系统通知可带按钮**：审批通知改成右下角的**通知窗**，直接点「允许一次 / 拒绝」就地处理，
  不必先把窗口拉出来再找卡片（系统气泡不支持按钮）。纯提示（一轮完成等）仍走托盘气泡。
- **可选接入 Windows 原生通知（toast）**：设置 → 桌面 → 通知里多了「使用系统原生通知」开关，
  **默认关**（用应用内 M3 卡片，零系统痕迹）。打开后由 `nucleus.notification-windows` 发原生 toast
  （进通知中心、带按钮），并创建/更新开始菜单快捷方式 —— 这是 Windows 的 AUMID 归属硬要求
  （桌面、其它目录、纯注册表键都不行，四组对照见 `.design/详细设计/工程与代码/12-通知窗.md`）。
  快捷方式文件名与 AUMID 都带 `<home>` 哈希，多份便携包互不覆盖；快捷方式被删时设置页会显示
  原因并回退到自绘窗，不静默失效。

- **终端渲染实现**：JediTerm 3.76 + Pty4J + `SwingPanel` → BossTerm 裁剪版（Compose 原生绘制）。
  读屏改走 BossTerm 的导出接口（`snapshot` / `tailText` / `selectedText`），`terminal.read` 字段保持不变。
- **终端默认 shell**：原来固定 `powershell.exe`，现在按 `pwsh.exe` → `powershell.exe` → `cmd.exe` 探测降级。
- **终端默认字体**：字体名留空时回落 BossTerm 自带的 MesloLGS Nerd Font，
  oh-my-posh 之类提示符里的 Nerd 图标私有区字形不再显示成方块；填了系统字体名就用系统的，缺字形由 Skia 回落。
- **终端配置位置**：BossTerm 的存储根从 `~/.bossterm` 改到 `%LOCALAPPDATA%\hermes-compose\terminal`，
  终端外观设置本身由 `config/state.json` 注入，不再读写 `settings.json`。
- **弹框不再让位**：删掉「终端展开时先收起面板再弹框」的绕法（Swing heavyweight 遮挡已不存在）。
- **Markdown 渲染**：渲染实现整体迁到 `modules/markdown-one`（submodule），移除 mikepenz 渲染器的直接依赖；
  公式引擎换 latex-renderer；嵌套块走适配器链；Markdown 主题改为独立设置。

### 修复

- 点终端「+」新建标签后一片空白（标签对账的 `LaunchedEffect` key 漏了 `tabs`，新标签从未起 PTY）。
- 终端字体设置改了没反应（渲染路径没读 `fontName`），顺带修好 Nerd 图标显示成方块。
- 终端中文输入：用 `TextFieldValue.composition` 区分组合态与提交，同一次提交去重；
  切换输入法的快捷键从被微软拼音抢占的 `Ctrl+Space` 改为 `Ctrl+Alt+I`。
- 自适应布局：选区开关下沉到 `MarkdownText`，修自研渲染层下选区功能失效。
- 更正行内公式的解析器版本口径（markdown 0.7.17）。

### 内部与性能

- 终端不再有 `hermes.terminal` 构建开关：`modules/terminal`（宿主）+ `modules/terminal-bossterm`（git submodule + composite build）始终参与构建。
- 删除 JediTerm / Pty4J 依赖，终端相关依赖全部收敛在 `modules/terminal` 内。
- 删除 `modules/ui-markdown` 门面模块，Markdown 主题设置独立（ADR-053）。
- 字体解析（含 bundled 字体落盘）从组合线程挪到 IO 线程，首帧先用平台等宽字体占位，避免第一次进终端卡顿。
- 终端列数 / 存活进程数改 1s 轮询 + 去重，不再依赖缓冲区回调。
- `modules/markdown-one` 多次跟随升级（`00fca0b` → `8c250c5` → `707f3c8` → `ad5ef40`）。
- BossTerm 改为自维护 fork（Apache-2.0），只保留终端核心子集，
  不再依赖上游的整应用打包（Android target / ktor-server / supabase / MCP SDK / zxing / jna 均未引入）。
- 决策与口径同步：新增 ADR-054（终端改 Compose 原生渲染，取代 ADR-040）；
  全仓库文档把「JediTerm + SwingPanel」的旧口径改为实现现状（`.design/` 的选型、依赖、工程结构、终端域篇）。

### 已知缺口（本版明确没做）

| 缺口 | 说明 |
|---|---|
| **附件图片缩略** | composer 附加的图片只作为下一轮模型输入，历史行里没有稳定的图片引用可渲染；Markdown 里的 `![](url)` 正常显示 |
| **终端文本回放** | 重启只恢复标签 / cwd / 展开态与高度，**不复活进程**，因此不回放历史输出 |
| **文件拖入终端** | BossTerm 留了钩子但未接线，拖文件到终端当前无反应（链接点击、右键菜单、选中加入对话均已可用） |
| **回看行数只对新标签生效** | 改「滚动回看行数」不会影响已经打开的标签，需新开标签才生效（字号 / 字体 / 配色是实时生效的） |
| **忙碌队列可见列表** | 后端自己排队（`display.busy_input_mode`），没有查询队列的 RPC，客户端只显示「已发送」 |
| **客户端自更新** | 关于页的更新检查针对后端；绿色版 zip 自更新仍未做 |

### 升级注意

1. **终端不再是 Swing 组件**：如果之前用过旧版终端，`~/.bossterm` 目录不会再被创建 / 读取，
   可以手动删除。终端相关文件现在在 `%LOCALAPPDATA%\hermes-compose\terminal`；
   终端外观设置（字体 / 字号 / 回看行数 / 配色）在 `config/state.json` 里，
   从旧版升上来的外观设置会被沿用，但**回看行数**只在新建标签时生效（见上表）。
2. **默认 shell 变了**：机器上装了 PowerShell 7 的话，终端默认起的是 `pwsh` 而不是 `powershell.exe`（5.1）。
   依赖 5.1 行为的脚本，请在终端里手动 `powershell.exe` 或调整配置。
3. **产物改名**：可执行文件名从 `Hermes.exe` 改为 **`hermes-desktop-compose.exe`**（配置在
   `app\hermes-desktop-compose.cfg`）。资产名相应变成
   `hermes-desktop-compose-0.3.0-win32-x64-{local,remote}.7z.001`；`local` / `remote` 两个变体、
   `.7z.001` 分卷、`SHA256SUMS.txt` 的形态不变。
   ⚠️ 仓库里 `HermesCompose-<hash>` 这个前缀（单实例互斥量 / 自启动计划任务名）**没有改**，
   它和产物名不是一回事；用户数据目录仍是 `%LOCALAPPDATA%\hermes-compose`。
4. **payload 未变**：本版的 `local` 变体内置的上游 payload 仍是 `v0.22.5`，
   终端与对话的改动全部在客户端壳里。两种变体的选择方式与旧版一致
   （`config/config.json` 的 `backend.mode`）。

---

## [0.2.0] · 2026-10-31

终端面板（`modules/terminal`）、设置中心分组（配置档案 / 模型 / 安全 / 提供方 / 调试）、技能发现、
会话行操作与实时刷新、消息引用、全局快捷键与终端标签重命名；
Markdown 渲染改用 `modules/markdown-one`（git submodule）。

## [0.1.0] · 2026-10-29

首个可运行版本：Compose 桌面客户端外壳 + 内置 Hermes 本地后端（sealed payload）与远程模式、
jlink 裁剪运行时、原生启动器打包、单实例与自启任务清理。

---

## 版本号说明

仓库里只有两套数字，含义不同，改版本前先分清：

| 数字 | 在哪 | 谁生成 | 谁消费 |
|---|---|---|---|
| `appVersion = "0.4.0"` | `composeApp/build.gradle.kts` | 手工 | jpackage 的 `packageVersion` / `-Djpackage.app-version`、启动器 cfg、发布资产文件名 |
| `v<桌面版本>-<hermes版本>` | GitHub Release tag | `desktop-release` 按输入拼 | 发布资产入口、`gh release download` |

**tag 就是唯一版本号**，形如 `v0.4.0-0.22.5`：前半是桌面端版本（对应 `appVersion`），
后半是配套的 Hermes payload 版本（`Shinglem/hermes-bundle` 的 tag）。
`appVersion` 只影响 exe 的属性与 cfg，不回写 tag。

工具链（启动器 + 裁剪运行时）在同一个 Release 里，tag 是 `toolchain-v<N>`
（`v1`、`v2` ……**只增不改**），和桌面版本完全解耦：换一次 JDK / 改一次模块列表才需要 +1。

| Release | tag 形式 | 资产 |
|---|---|---|
| 桌面端 | `v0.4.0-0.22.5` | `hermes-desktop-compose-0.4.0-0.22.5-windows-x64.7z.001`（+ 后续卷）+ `SHA256SUMS.txt` |
| 工具链 | `toolchain-v1` | `launcher-windows-x64.zip` / `runtime-windows-x64.zip` / `SHA256SUMS.txt` |
| payload | 另一个仓库 `Shinglem/hermes-bundle` 的 `v0.22.5` | payload 压缩分卷 |

**patch 发布约定**：完整包只在桌面版本升位时由 CI 构建；同一位数内的小修只上传**替换用文件**
（`hermes-desktop-compose-<版本>-patch.zip`），下载后覆盖既有安装目录里的同名文件即可升级，
`config/` 与 `data/` 不动。
