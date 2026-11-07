---
name: global-release-patch
description: "发版（含小版本的全量 CI release）或本机升级桌面端时使用：改版本号与 CHANGELOG、触发 desktop-release、核验资产、覆盖式部署并用 startup.log 验证。"
---

# 桌面端发版：全量 CI release 与覆盖式部署

> **中文名**：桌面端发版与覆盖部署 ｜ 目录与 `name`：`global-release-patch`（名字沿用旧称，内容已改）

**适用**：要给客户端发一个新版本、要在本机把某个版本"装上"、或需要确认"这版该走哪条发版路径"。
**不适用**：本机临时验证（那只需 `syncReleaseTest` + 覆盖 `bin/App.jar`，见 §5）。

⚠️ **第一步永远是读 [`CHANGELOG.md`](../../../CHANGELOG.md) 文末的「版本号说明」**。
当前策略（**2026-11-07 起**）：**所有版本，包括 0.x.y 小版本，都发全量包**，由 CI
`desktop-release` 构建；产物是**纯外壳、不含 payload**，所以体量与耗时都很小。
**不再发「只含替换文件」的 patch zip**（历史 Release 保留，不再新发）。
**不要凭"小版本 = 手工传几个 jar"的旧惯性动手** —— 本项目为此改过一次约定。

## §1 三套版本号，别混用

| 数字 | 在哪 | 谁改 | 影响 |
|---|---|---|---|
| `appVersion` | `composeApp/build.gradle.kts` | **手工**，每发一版改一次 | jpackage `packageVersion`、启动器 cfg 的 `-Djpackage.app-version` |
| `v<桌面版本>-<hermes版本>` | 本仓库 Release（CI 产出） | `desktop-release` workflow | 完整包 `…-windows-x64.7z.001` |

**两者互不推导**：tag 不回写 `appVersion`，`appVersion` 不参与 tag 生成。
工具链（启动器 + 裁剪运行时）走自己的 `toolchain-v<N>`，**只增不改**。

## §2 走哪条路

| 情况 | 做什么 | 入口 |
|---|---|---|
| 任何要发出去的版本（功能 / 修复 / 小版本） | 全量包 `…-windows-x64.7z.001`（**纯外壳**，payload 由用户自备） | CI `desktop-release`（§3） |
| 换了 JDK / 改了 `runtimeModules` / 换了启动器 | 先跑 `toolchain-windows`（`version` +1），再发桌面包 | CI |
| 只想在本机验证新代码 | 不进 Release，直接 `syncReleaseTest` + 覆盖 `bin/App.jar` | §5 |

判断依据是**要不要给别人**，不是"改了几行"。全量包不含 payload，所以"多打一个包"成本很低 ——
这就是取消 patch 的原因。

## §3 全量发版步骤

1. **改版本号**：`composeApp/build.gradle.kts` 的 `appVersion = "x.y.z"`（KDoc 里的说明一起看）。
2. **写 CHANGELOG**：新增 `## [x.y.z] · <日期>（hermes <payload版本>）` 段，含「新增 / 修复」与
   **「升级」**（用户怎么装、哪些目录不要动）；文末「版本号说明」的资产表加一行。
3. **提交**：`chore: 发布 vX.Y.Z`（与代码变更分开提交）。
4. **本地先编一遍**：`./gradlew :composeApp:compileKotlinDesktop :composeApp:desktopTest`
   —— CI 失败一轮的代价远大于这 1 分钟。
5. **同步并推送**：`git -c http.proxy=… pull --rebase origin main` → `push --dry-run` 验权限 → `push origin main`
   （代理与凭据纪律见技能 [`global-git-ops`](../global-git-ops/SKILL.md)）。
6. **触发 CI**：`desktop-release` 是 `workflow_dispatch`，**tag 由 CI 生成**
   （`v<desktop>-<hermes>`），不要自己先打 tag。本机没有 `gh` CLI，走 REST：

   ```powershell
   $raw   = Get-Content (Join-Path $env:USERPROFILE '.git-credentials') -Raw
   $token = ([regex]::Match($raw, 'https://[^:]+:(?<t>[^@\r\n]+)@github\.com')).Groups['t'].Value.Trim()
   $h = @{ Authorization = "token $token"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'hermes-release' }

   $body = @{ ref = 'main'; inputs = @{ desktop_version = '0.5.1'; hermes_version = '0.23.0'; toolchain_version = '1' } } |
           ConvertTo-Json
   Invoke-RestMethod -Uri "https://api.github.com/repos/Shinglem/hermes-compose/actions/workflows/desktop-release.yml/dispatches" `
     -Headers $h -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json'
   ```

   `hermes_version` 留空会取 payload 仓库 latest —— **要显式写**，否则以后 bundle 一升级，
   旧客户端版本号会被安上新的 payload 后缀。
7. **等 CI 并通过 REST 核验**：`GET /repos/.../actions/runs?per_page=1` 看 conclusion；
   完成后查 `GET /repos/.../releases/tags/v<desktop>-<hermes>`，确认资产齐全
   （`…7z.001`（+卷）与 `SHA256SUMS.txt`），并把 `SHA256SUMS.txt` 与本地算的 hash 对一遍。
8. **失败处理**：CI 红了先看日志（构建脚本在 workflow 里，`syncReleaseTest` 之后会断言
   `release-test\hermes` 不存在 —— 存在说明 payload 混进了外壳）。不要重复触发刷 run。

> 历史（已废止）：0.3.1 ~ 0.5.0 期间小版本走「只上传替换文件」的 patch zip
> （`hermes-desktop-compose-<版本>-patch.zip`，手工 `Compress-Archive` + `SHA256SUMS.txt`）。
> 如果确实需要给某人一个临时补丁，仍然可以按老办法手工打包，但**不要**为它建 Release。

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
| 只发了个 patch zip，用户装完版本号没变 | 又按旧的 patch 约定发了 | 撤掉 Release，按 §3 走全量 CI |
| CI 报 `release-test\hermes 存在` | payload 被复制进外壳 | `syncReleaseTest` 不该带 payload；用 `syncReleasePayload` 时才出完整 payload 树 |
| `Copy-Item` 报占用 / 装完还是旧行为 | 客户端还在跑 | 先停进程，再覆盖 |
| 上传 404 / 403 | 未带 token / 仓库私有 / run 未完成 | token 走凭据副本；先确认 run conclusion |
| `SHA256SUMS.txt` 校验不过 | 资产被重传过 | 以 Release 上的 `SHA256SUMS.txt` 为准重新下载核对 |
| 装完看到的还是旧界面 | 只更新了 `release-test`，没拷进 `<home>` | 部署是两步：出产物 → 覆盖安装目录 |
| 忘记改 `appVersion` | 只在 CHANGELOG 改了版本 | 版本源头是 `build.gradle.kts`，CHANGELOG 只是描述 |

## §7 相关

- 版本口径与历史对照：[`CHANGELOG.md`](../../../CHANGELOG.md)「版本号说明」
- 完整包 CI：[`README.md`](../../../README.md)「发行产物」、`.github/workflows/desktop-release.yml`
- 本机组装脚本：`packaging/assemble-release-test.ps1`
- 覆盖部署后的运行态排障：[`dev-windows-integration-debug`](../dev-windows-integration-debug/SKILL.md)
