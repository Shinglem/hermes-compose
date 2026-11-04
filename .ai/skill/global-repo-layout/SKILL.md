---
name: global-repo-layout
description: "hermes-compose 仓库的目录规划、技能命名与新增流程：某内容该放哪个目录、新技能怎么命名与登记、各 agent 怎么接入技能库。"
---

# 仓库目录规划与技能命名

> **中文名**：仓库目录规划与技能命名 ｜ 目录与 `name`：`global-repo-layout`

**适用**：新建技能、新增文档，或不确定「某内容该放哪」时。
本技能是 [`AGENTS.md`](../../../AGENTS.md)「目录约定 / 文档与技能索引 / 项目技能与多 agent 接入」
几节的操作展开；两者冲突时以 `AGENTS.md` 为准。

## §1 目录地图（每类内容只有一个家）

| 路径 | 放什么 | 权威源 |
|---|---|---|
| `AGENTS.md` | 项目级规则（首要规则、非目标、硬约束、编码与提交规范、路由表） | ✅ 唯一 |
| `.ai/skill/<技能名>/SKILL.md` | 技能（可复用的流程、判据、坑） | ✅ 唯一 |
| `.ai/README.md` | 技能清单与来源（**唯一处**） | ✅ 唯一 |
| `.design/` | 设计两层：`草案/`（做什么、为什么）与 `详细设计/`（怎么做；UI 视觉稿为参考稿） | ✅ 唯一 |
| `.github/` | Copilot 接入层：`copilot-instructions.md`（指针）+ `skills`（链接），**本身不存规则/技能内容** | — |
| `composeApp/src/` | 源码，按 `feature/<域>/` 分包 | ✅ 唯一 |
| `gradle/libs.versions.toml` | 依赖版本（唯一版本来源） | ✅ 唯一 |

**判断口诀**：规则 → `AGENTS.md`；方法 / 判据 / 坑 → 技能；做什么与为什么 → `.design/`；代码 → `composeApp/`。
**同一内容只写一处**，别处只允许**链接指向**（复制必然漂移）。

`.design/` 与实现冲突时：先改文档，或把偏差明确记录到 `.design/草案/07-decisions.md`，不要静默偏离。

## §2 技能命名

目录名 = frontmatter `name` = 技能 ID，**全小写 + 连字符**。

| 来源 | 命名 | 例子 | 说明 |
|---|---|---|---|
| 本项目新建 · 技术栈 | `dev-<技术>` | 将来的 `dev-compose-ime`、`dev-ktor-rpc` | 讲某个技术栈在本项目里怎么用 |
| 本项目新建 · 全局 | `global-` | `global-git-ops`、`global-repo-layout` | 讲跨模块的流程、纪律、约定 |
| 本项目新建 · 服务器 | `server-` | —（本期无服务器） | 只在真接入服务器后再建 |
| 外部技能库同步 | **保留上游 `name`** | `compose-multiplatform-desktop`、`desktop-stack-rewrite-assessment`、`kotlin-tooling-*` | 改名会让上游同步对不上，**不要改** |

⚠️ **模块技能等模块正式命名后再建**（占位名会带来改名连锁成本：目录名 = `name` = 技能 ID）。

## §3 新建一个技能的步骤

1. **定前缀**（§2）——先想清是技术栈（`dev-`）、全局纪律（`global-`）、服务器（`server-`），
   还是从外部库同步（保留上游名）。
2. 建目录 `.ai/skill/<前缀-名>/`，入口 `SKILL.md`。
3. frontmatter 只写必要项：

   ```yaml
   ---
   name: <前缀-名>
   description: "<何时该用（触发条件式一句话）；技术名词保留英文>"
   ---
   ```

4. 附属材料放同目录：`references/`（详解）、`scripts/`（可跑脚本）、`assets/`（模板）。
5. 在 [`.ai/README.md`](../../README.md) 清单里**登记**（唯一处）。
6. 若影响任务路由，同步更新 `AGENTS.md` §9 的路由表。

**不需要**改任何链接或 agent 配置 —— `.github/skills` 链接的是整个 `.ai/skill/` 目录。

### ⚠️ 技能目录必须是 `.ai/skill/` 的**一级**子目录

VS Code 的技能发现**只扫一层**：对每个子目录看它是否直接含 `SKILL.md`，**不递归**
（源码：`promptFilesLocator.ts` 的 `findAgentSkillsInFolder`）。
所以 `.ai/skill/kotlin/kotlin-tooling-xxx/SKILL.md` 这种**分组嵌套会被静默忽略**。
需要分类时用命名前缀（`dev-` / `kotlin-`）表达，不要用子目录。

## §4 技能写作纪律

- 只写**项目可复用**的内容；**不写**个人主机的绝对路径、用户名、凭据位置。
- 路径用**仓库相对路径**（`.ai/skill/…`、`.design/…`），交叉引用用相对链接，保证可校验、无死链。
- 凭据只写**位置**不写值（`AGENTS.md` §0）。
- 技能里**不重复** `AGENTS.md` 已有规则，只引用；不重复构建脚本已覆盖的步骤。
- **行尾一律 LF**；新写文件不要 CRLF。
- 单个 `SKILL.md` 保持可一次读完（超过约 200 行就拆到 `references/`，正文给索引）。

## §5 多 agent 接入

技能库（`.ai/skill/`）与规则（`AGENTS.md`）是**两个中心**，各 agent 用软链 / 指针文件**指向**它们，不复制内容：

| Agent | 技能接入 | 规则接入 |
|---|---|---|
| **VS Code / Copilot** | `.github/skills` → 符号链接 `../.ai/skill` | `.github/copilot-instructions.md` → 指针文件 |
| Codex / 其它支持 Agent Skills 的工具 | 需要时加 `.agents/skills` → `../.ai/skill` | 原生读 `AGENTS.md` |

接入细节、软链检出前置（Windows 开发者模式 + `core.symlinks=true`）与自检修复命令见
[`AGENTS.md`](../../../AGENTS.md) §9。
