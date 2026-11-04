---
name: global-release-patch
description: "发版或本机升级桌面端时使用：判断该发完整包还是 patch、只打包与主版本的差异文件、手工上传 GitHub Release（无 gh CLI）、覆盖式部署并用 startup.log 验证。"
---

# 桌面端发版：patch 包与覆盖式部署

> **中文名**：桌面端发版与覆盖部署 ｜ 目录与 `name`：`global-release-patch`

**适用**：要给客户端发一个新版本、要在本机把某个版本"装上"、或需要判断"这次该发完整包还是 patch"。
**不适用**：完整包（`x.y.0` 里程碑）的 CI 构建 —— 那条路走 `.github/workflows/desktop-release.yml`，
见 [`README.md`](../../../README.md) 的「发行产物」。

⚠️ **第一步永远是读 [`CHANGELOG.md`](../../../CHANGELOG.md) 文末的「版本号说明」**。
那里写着当前采用的发布策略（v0.3.1 起：**小版本只发差异文件、手工上传、覆盖式更新、不走 CI**）。
**不要凭"发版 = 打 tag + 传 7z"的惯性动手** —— 本项目已经为此返工过一次。

## §1 三套版本号，别混用

| 数字 | 在哪 | 谁改 | 影响 |
|---|---|---|---|
| `appVersion` | `composeApp/build.gradle.kts` | **手工**，每发一版改一次 | jpackage `packageVersion`、启动器 cfg 的 `-Djpackage.app-version` |
| `v<桌面版本>-<hermes版本>` | 本仓库 Release（CI 产出） | `desktop-release` workflow | 完整包 `…-windows-x64.7z.001` |

**两者互不推导**：tag 不回写 `appVersion`，`appVersion` 不参与 tag 生成。
工具链（启动器 + 裁剪运行时）走自己的 `toolchain-v<N>`，**只增不改**。

## §2 该发哪种包

| 情况 | 发什么 | 入口 |
|---|---|---|
| 桌面版本升位（换 payload、改启动器 / `rt/`、依赖大版本） | 完整包 `…-windows-x64.7z.001`（**纯外壳**，payload 由用户自备） | CI `desktop-release` |
| 只改了客户端壳的类与资源（`bin/App.jar`） | **patch zip（只含替换用文件）** | 本技能 §3，手工上传 |
| 只改了 `lib/` 里某几个依赖 jar | patch zip（把这些 jar 按相对路径放进去） | 同上 |
| 换了 JDK / 改了 `runtimeModules` / 换了启动器 | 先跑 `toolchain-windows`（`version` +1），再发桌面包 | CI |

判断依据是**差异文件的物理位置**，不是"改了几行"：`syncReleaseTest` 后拿 `release-test`
与既有安装目录逐个比对 `lib/`（名字 + 大小 + hash）与 `bin/App.jar`。

## §3 patch 发布步骤

1. **改版本号**：`composeApp/build.gradle.kts` 的 `appVersion = "x.y.z"`（KDoc 里的 tag 示例一起改）。
2. **写 CHANGELOG**：新增 `## [x.y.z] · <日期>` 段，含「修复 / 新增」与**「升级」**（用户怎么装）；
   文末「历史对照」表加一行。
3. **提交**：`chore: 发布 vX.Y.Z`（与代码变更分开提交）。
4. **产出**：`./gradlew :composeApp:syncReleaseTest` → 得到 `release-test/bin/App.jar`。
5. **算差异**（关键一步，别省）：

   ```powershell
   $src = Get-ChildItem release-test\lib -File | ForEach-Object { "$($_.Name)|$($_.Length)" }
   $dst = Get-ChildItem <既有安装>\lib -File | ForEach-Object { "$($_.Name)|$($_.Length)" }
   Compare-Object $src $dst
   ```

   大小相同但 hash 不同时再比 `Get-FileHash` —— **SNAPSHOT 依赖（如 `compose-ui-desktop-*-SNAPSHOT.jar`）
   重新构建后字节会变、内容等价**，这类文件不必进 patch，否则等于又发了一遍全量。

6. **打包**：保持安装目录的相对结构（`bin/App.jar`、必要时 `lib/xxx.jar`），
   用 `Compress-Archive` 打成 `hermes-desktop-compose-<版本>-patch.zip`。
7. **校验和**：`SHA256SUMS.txt` = `<sha256 小写>  <文件名>`（两个空格），与 zip 实算一致才继续。
8. **打 tag**：annotated，说明写"相对上一个完整包的变更"（可直接用 CHANGELOG 段）。
9. **推**：`git -c http.proxy=… push --dry-run origin main` 先验权限，再 `push origin main vX.Y.Z`
   （代理与凭据纪律见技能 [`global-git-ops`](../global-git-ops/SKILL.md)）。
10. **建 Release 并上传资产**：本机没有 `gh` CLI，走 REST API（§4）。
11. **回下载校验**：把资产重新拉下来算 sha256，与 `SHA256SUMS.txt` 对上才算发完（§4）。

## §4 用 REST 上传 / 校验（无 `gh` CLI）

```powershell
# token 只从凭据副本读进内存，绝不打印、不写进脚本文件
$raw   = Get-Content (Join-Path $env:USERPROFILE '.git-credentials') -Raw
$token = ([regex]::Match($raw, 'https://[^:]+:(?<t>[^@\r\n]+)@github\.com')).Groups['t'].Value.Trim()
$h = @{ Authorization = "token $token"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'hermes-release' }

# 建 Release（正文取 CHANGELOG 对应段）
Invoke-RestMethod -Uri "https://api.github.com/repos/<owner>/<repo>/releases" -Headers $h -Method Post `
  -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json; charset=utf-8'

# 上传资产（同名要先删，REST 不支持覆盖）
Invoke-RestMethod -Uri "https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets?name=<name>" `
  -Headers $h -Method Post -Body ([IO.File]::ReadAllBytes($path)) -ContentType application/zip

# 校验：GET asset.url，Accept: application/octet-stream，下载后算 hash
```

匿名调用 `api.github.com` 会 `403 rate limit exceeded` —— 私有仓库必须带 token。

## §5 本机覆盖部署（升级自己这台）

```powershell
# 1) 先停进程：jar 被占用时 Copy-Item 会失败或写坏
Get-Process -Name hermes-desktop-compose | Stop-Process -Force    # 有多个实例时按 PID 逐个停
# 2) 只覆盖改动过的文件（默认就是 bin\App.jar）
Copy-Item release-test\bin\App.jar <home>\bin\App.jar -Force
# 3) 核对落地的确实是新产物
(Get-FileHash <home>\bin\App.jar).Hash -eq (Get-FileHash release-test\bin\App.jar).Hash
# 4) 用计划任务以"托盘态"拉起（与开机自启走同一条路径）
schtasks /Run /TN '<任务名>'
# 5) 看日志确认，而不是只看"进程在跑"
Get-Content <home>\logs\startup.log -Tail 6
```

验证口径：

| 想确认 | 看什么 |
|---|---|
| 新 jar 真生效 | `startup.log` 里新增的日志字段 / 窗口标题 / 关于页版本 |
| 托盘态正确 | 进程 `MainWindowHandle = 0`，日志 `silent=true` |
| 自启任务健康 | 日志 `autostart task=… installed=true exeArgsMatch=true pending=false` |

## §6 坑

| 现象 | 多半是 | 处理 |
|---|---|---|
| 按"完整包 + 7z"发了 patch 版本 | 没读 CHANGELOG 的发布约定 | 撤 Release，按 §3 重来；**先读约定再动手** |
| patch 里塞进了上百个 jar | 把"重新构建了"当"内容变了" | 按 §3 第 5 步比 名字+大小+hash；SNAPSHOT 的字节漂移不算变化 |
| `Copy-Item` 报占用 / 装完还是旧行为 | 客户端还在跑 | 先停进程，再覆盖 |
| 上传 404 / 403 | tag 未推 / 未带 token / 仓库私有 | 先推 tag；token 走凭据副本；`--dry-run` 先验 push 权限 |
| `SHA256SUMS.txt` 校验不过 | 先写 SUM 后重打 zip，或换行被改写 | 先定 zip 再算 hash；文件按 ASCII + LF 写 |
| 装完看到的还是旧界面 | 只更新了 `release-test`，没拷进 `<home>` | 部署是两步：出产物 → 覆盖安装目录 |
| 忘记改 `appVersion` | 只在 CHANGELOG 改了版本 | 版本源头是 `build.gradle.kts`，CHANGELOG 只是描述 |

## §7 相关

- 版本口径与历史对照：[`CHANGELOG.md`](../../../CHANGELOG.md)「版本号说明」
- 完整包 CI：[`README.md`](../../../README.md)「发行产物」、`.github/workflows/desktop-release.yml`
- 本机组装脚本：`packaging/assemble-release-test.ps1`
- 覆盖部署后的运行态排障：[`dev-windows-integration-debug`](../dev-windows-integration-debug/SKILL.md)
