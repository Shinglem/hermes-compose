---
name: global-git-ops
description: "在本仓库（Shinglem/hermes-compose）与 GitHub 之间同步代码时使用：代理探活、凭据自查、pull --rebase 流程与提交纪律。"
---

# git 操作与同步（本仓库 ↔ GitHub）

> **中文名**：git 同步 ｜ 目录与 `name`：`global-git-ops`

本仓库 `Shinglem/hermes-compose`（远端 `https://github.com/Shinglem/hermes-compose.git`，
默认分支 **`main`**）与 GitHub 之间**拉取 / 推送**的固定做法。
技能只留流程与命令；**不写账号 / token 值，也不登记任何个人凭据的位置**
（见 [`AGENTS.md`](../../../AGENTS.md) §0 与 §7）。

## When to Use

- 用户说「拉取」「同步一下」「push 上去」「看看远端有没有新提交」
- `git fetch` / `pull` / `push` **卡住、超时、504 或 TLS 报错**（多半是没走代理）
- 提交（`commit` / `push`）前做**明文凭据扫描**自查
- 不确定提交信息该用什么格式、能不能把多件事混在一个提交里时

**不适用**：Gradle 拉依赖的代理（同一台代理，但配置在 `gradle.properties`，见 `AGENTS.md` §5）。

## §1 首要规则：GitHub 访问按需走代理

本机 Windows，**直连 `github.com:443` 不通**（`AGENTS.md` §5 实测），必须显式走本机代理。
本机代理端口记录为 **7890**；但**代理客户端开关与端口会变**，用前先探活，不要假设它开着。

两条纪律：

- ⚠️ **用前探活**：`Test-NetConnection` + `curl.exe` 双确认。
- ⚠️ **不要把代理写进全局配置**（`git config --global http.proxy …`）：代理关掉后**所有** git 操作都会失败。
  保持默认直连，需要时按「一次性」传参。

### 探活（只读）

```powershell
Test-NetConnection 127.0.0.1 -Port 7890 -InformationLevel Quiet   # True = 端口在听

# 401 = 通道通（私有仓库未带凭据即 401）；000 / 超时 = 代理没开
curl.exe -sS -m 8 -o NUL -w "%{http_code}`n" -x http://127.0.0.1:7890 `
  https://github.com/Shinglem/hermes-compose.git/info/refs?service=git-upload-pack

git config --get http.proxy     # 返回空 = 默认直连，正常
```

### 一次性带代理（推荐）

```powershell
git -c http.proxy=http://127.0.0.1:7890 fetch origin --prune
git -c http.proxy=http://127.0.0.1:7890 pull --rebase origin main
git -c http.proxy=http://127.0.0.1:7890 push origin main
```

或临时环境变量（只对当前进程生效）：

```powershell
$env:HTTPS_PROXY = 'http://127.0.0.1:7890'
git fetch origin --prune
```

> `Invoke-WebRequest` 不读环境变量代理且吞吐极差；验通道一律用 `curl.exe`（系统自带）+ `-x` 显式指定。

## §2 凭据 —— 只引用位置，绝不打印值

- 远端是 **HTTPS**，推送要 GitHub 凭据；**凭据值永远不进对话 / 脚本 / 文档 / 仓库 / 日志**（§0）。
- 本机已配凭据助手（`git config --get credential.helper` 非空，本机为 `store`），
  git 自动带凭据，**命令里不出现 token**。
- 凭据归各自操作者，个人凭据不入库、不共享。需要显式传 token 时用环境变量或一次性
  `credential.helper` 注入，**用完即弃**。
- `403` / `Authentication failed` = 未带凭据，或该凭据无 push 权限。

```powershell
git config --get credential.helper
git -c http.proxy=http://127.0.0.1:7890 push --dry-run origin main   # 先 dry-run 验权限
```

## §3 同步流程（提交前先 pull；分叉不硬来）

本仓库约定用 **`git pull --rebase`** 保持线性历史；**rebase 冲突就 `git rebase --abort`，
恢复现场并报告**，不自行解冲突、不 force push。

1. **先看状态**：`git status -sb`、`git log --oneline -3`。工作区脏时先提交或 `git stash`。
2. **探活 → 带代理拉取**：`git -c http.proxy=… pull --rebase origin main`
   （或先 `fetch origin --prune` 再看 `git status -sb` 的 `behind N`）。
3. **纯快进**：能快进时 `pull` 自动完成，无额外动作。
4. **rebase 冲突**：`git rebase --abort` → 报告冲突文件清单 → **停下等指示**。
5. **收尾核对**：`git status -sb`（与上游一致）+ `git log --oneline -3`。

## §4 提交纪律

- **入库前扫描明文凭据**：按内容特征匹配，不按行号 —— `KEY=` / `--key` / `*_TOKEN` /
  `PASSWORD` / 长随机串。出现真值先改占位符或改从环境变量读取，再 `git add`。
- **提交信息用中文**，格式 `<类型>: <简述>`，类型取 `feat` / `fix` / `refactor` / `docs` / `chore` / `test`
  （`AGENTS.md` §7）。例：`docs: 适配 Copilot 技能接入层`、`fix: 修正会话列表分组标题闪烁`。
- **一次提交一件事**；不要混合「重构 + 功能」。
- **不提交**：`build/`、`.gradle/`、`*.log`、本地路径嗅探文件、任何 token（`.gitignore` 已覆盖大部分）。
- **Copilot 参与的提交**带尾注：
  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`。
- **新写文件一律 LF**（`AGENTS.md` §6；仓库 [`.gitattributes`](../../../.gitattributes) 强制 `eol=lf`）。
- **禁止**：force push、绕过钩子、`git checkout --` 丢弃他人改动、制造空提交。

## §5 坑位

| 现象 | 多半是 | 处理 |
|---|---|---|
| `fetch` / `pull` 长时间无输出、最后超时或 `Empty reply from server` | 直连 GitHub，没走代理 | 按 §1 探活后加 `-c http.proxy=http://127.0.0.1:7890` |
| 刚才还能拉，突然全挂 | 代理客户端被关了 | 重跑 §1 探活；确认代理在跑 |
| **所有** git 操作都连不上代理 | 曾把代理写死进全局配置 | `git config --global --unset http.proxy`（以及 `https.proxy`） |
| `Authentication failed` / `403` | 未配凭据助手或凭据无权限 | 按 §2 确认 `credential.helper` + `push --dry-run` |
| `fatal: detected dubious ownership in repository` | 仓库所在盘符的属主与当前用户不同（换机器 / 共享盘 / 双系统） | **每条命令带** `-c safe.directory=<仓库路径>`；不要 `--global` 加例外，也不要为此改目录 owner |
| `pull --rebase` 报冲突 | 本地与远端真分叉 | `git rebase --abort` → 报告 → 等指示 |
| 提交里出现整文件重写、diff 巨大 | 行尾 CRLF/LF 漂移（本机 `core.autocrlf=true`） | 按 `.gitattributes` 的 `eol=lf` 重写该文件后再提交 |
| `.github/skills` 在别的机器上「技能消失」 | 符号链接被检出成普通文件（`core.symlinks=false`） | 见 [`AGENTS.md`](../../../AGENTS.md) §9 的自检与修复 |

> `safe.directory` 的正确用法（**按次传参**，别悄悄改目录 owner）：
>
> ```powershell
> git -c safe.directory=<仓库路径> status -sb
> ```
>
> 环境变量与 git 配置**不跨命令存活**，所以写脚本时要把这一项固定带在**每一条** git 调用上。
> 想少写几次可以自行 `git config --global --add safe.directory <仓库路径>`（等于承认"这个目录可信"），
> 但不要因为报错就去改仓库属主 —— 那会影响同一台机器上的其他用户/工具。

## §5.1 Release 资产（本机没有 `gh` CLI）

发版要上传资产时：**流程见技能 [`global-release-patch`](../global-release-patch/SKILL.md)**，
这里只记与 git/凭据相关的三点：

- 建 Release、传资产走 GitHub REST（`api.github.com` / `uploads.github.com`），
  token 从凭据助手或凭据副本**读进内存**，不打印、不落进脚本文件；
- 推 tag 前先 `push --dry-run`；`tag` 与 `main` 可以一条命令推（`push origin main vX.Y.Z`）；
- 匿名调 API 会 `403 rate limit exceeded`，私有仓库下载资产也必须带 token。

## §6 与其他文档的关系

- **规则与纪律**（首要规则、提交规范、行尾）：[`AGENTS.md`](../../../AGENTS.md)。
- **技能怎么建、放哪**：[`global-repo-layout`](../global-repo-layout/SKILL.md)。
- **发版与覆盖部署**：[`global-release-patch`](../global-release-patch/SKILL.md)。
- 本技能只讲**本机 ↔ 远端**同步，不含 Gradle 依赖下载与本地构建（见 `AGENTS.md` §5）。
