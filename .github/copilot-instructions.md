# 项目规则 —— 见 `AGENTS.md`

> ⚠️ **本文件不含规则内容，只作指针。** 所有规则、非目标、硬约束、编码与提交规范、技能路由
> **全部在仓库根的 [`AGENTS.md`](../AGENTS.md)**。

**请先完整阅读 [`AGENTS.md`](../AGENTS.md) 并严格遵守。**

它包含（不限于）：

- **语言** —— 对话与思考过程用中文；代码标识符、命令、路径、配置键名、报错原文保持英文原样。
- **非目标** —— 不改后端、不做 JS 插件宿主、不做文件树/编辑器/diff、不做远程屏幕、不做多语言。
- **契约只读** —— WebSocket JSON-RPC + REST 都在 `127.0.0.1:9119`；`8642` 不是桌面端接口。
- **硬约束** —— `prompt.submit` 超时 1800s、输入框 `BasicTextField`、外层 `LazyColumn` 不嵌套、
  跨消息选择不修、composer 发 Markdown 源码、不使用 `NavigationRail` / `stickyHeader`。
- **技能** —— 技能单一事实源 `.ai/skill/`，清单唯一处 [`.ai/README.md`](../.ai/README.md)，
  接入方式见 `AGENTS.md` §9。

> 本仓库的**唯一规则权威源是 `AGENTS.md`** —— 各 AI agent 用各自的机制**指向**它，不复制内容。

## 技能接入（Copilot）

- 技能真身：`.ai/skill/<技能名>/SKILL.md`（目录名 = frontmatter `name` = 技能 ID，**必须一级**）。
- Copilot 读的是 [`.github/skills`](./skills) —— 一个指向 `../.ai/skill` 的**符号链接**，
  整目录共享，新增技能**无需**改链接。
- 自检（`LinkType` 必须是 `SymbolicLink`，否则技能静默失效）：

  ```powershell
  Get-Item .github\skills | Select-Object Name,LinkType,Target
  ```

- 修复：删除被检出的普通文件/目录后重建链接（Windows 需开发者模式或管理员）：

  ```powershell
  Remove-Item .github\skills -Recurse -Force
  New-Item -ItemType SymbolicLink -Path .github\skills -Target '../.ai/skill'
  ```

## 先读什么

- **任务路由表**：[`AGENTS.md`](../AGENTS.md) §9 的「任务路由」—— **唯一一份**，不要在本文件复制。
- **技能清单**：[`.ai/README.md`](../.ai/README.md) —— **唯一处**，新增技能只登记在那里。
- **改完怎么验、怎么落地**：[`AGENTS.md`](../AGENTS.md) §10「变更验证与落地」。

> 本文件曾有一份路由表副本，已删除：两处必然漂移，而且新技能很容易只登记进其中一处
> （结果就是 agent 读不到）。要加路由，改 `AGENTS.md`。
