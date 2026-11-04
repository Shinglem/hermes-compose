# .ai · Agent 协作文档层

> 给在本仓库工作的 AI agent（Claude Code / Codex / Copilot / 其他支持 Agent Skills 的工具）使用的技能包。
> 主入口是仓库根目录的 **`AGENTS.md`**；本目录是**按需加载**的深度材料。

- **技能单一事实源**：`.ai/skill/<技能名>/SKILL.md`，目录名 = frontmatter `name` = 技能 ID。
- **必须是 `.ai/skill/` 的一级子目录**：VS Code 的技能发现只扫一层，分组嵌套（如 `skill/kotlin/xxx`）会被静默忽略。
- **本文件是技能清单的唯一处**；新增 / 删除技能都要在这里登记。
- 各 agent 的接入方式见 [`AGENTS.md`](../AGENTS.md) §9（Copilot 读 `.github/skills` → 链接到本目录）。

---

## 什么是技能

每个技能是一个目录，内含 `SKILL.md`（YAML frontmatter 的 `name` / `description` + 正文），
可选 `references/`（深度材料）、`scripts/`、`assets/`。遵循 [Agent Skills 标准](https://agentskills.io/)。

**加载原则**：agent 应该是**先读 `description`，命中场景才读正文**。不要把所有技能一次性塞进上下文。

---

## 本项目技能清单

### 本项目自建

| 技能 | 何时用 |
|---|---|
| [`global-git-ops`](skill/global-git-ops/SKILL.md) | 拉取 / 推送 / 同步 GitHub、代理探活、提交前凭据自查与提交纪律 |
| [`global-repo-layout`](skill/global-repo-layout/SKILL.md) | 内容该放哪、新建技能怎么命名、技能目录结构与多 agent 接入 |
| [`global-release-patch`](skill/global-release-patch/SKILL.md) | 发版 / 本机升级前 —— 该发完整包还是 patch、只打包差异文件、手工上传 Release、覆盖式部署与 `startup.log` 验证 |
| [`dev-desktop-gui-e2e-testing`](skill/dev-desktop-gui-e2e-testing/SKILL.md) | 用脚本驱动真实窗口做端到端验证前 —— 中文输入法吞按键、Enter 不生效、抢前台、DPI 坐标偏移、elevated 窗口注入失效、截图断言、测试数据隔离 |
| [`dev-windows-integration-debug`](skill/dev-windows-integration-debug/SKILL.md) | 自启 / 单实例 / 提权 / 托盘「不工作」时 —— 先读系统真实状态（`schtasks`、进程、`startup.log`）再改代码 |

### 项目自有（从 Hermes 技能库同步）

| 技能 | 何时用 |
|---|---|
| [`compose-multiplatform-desktop`](skill/compose-multiplatform-desktop/SKILL.md) | 写任何 Compose Multiplatform 桌面代码前 —— 布局、渲染、终端、剪贴板、窗口的坑都在这里 |
| [`desktop-stack-rewrite-assessment`](skill/desktop-stack-rewrite-assessment/SKILL.md) | 需要重新评估"某个能力该用哪套栈/要不要 WebView"时 |

`compose-multiplatform-desktop/references/component-selection.md` 是**逐功能域的组件候选表**
（渲染 / 高亮 / 编辑器 / 终端 / SVG / 自适应布局 / 网络 / 持久化），含可获取渠道、许可证与已知坑。
**选库前先查它**，不要凭记忆。

### Kotlin 官方技能（Kotlin/kotlin-agent-skills，Apache 2.0）

| 技能 | 本项目适用性 |
|---|---|
| `kotlin-tooling-kotlin-toolchain` | ✅ **适用** —— Gradle 的 Kotlin 工具链配置（JDK 版本锁定） |
| `kotlin-tooling-gradle-to-kotlin-toolchain-project` | ✅ **适用** —— 项目级工具链迁移/配置 |
| `kotlin-tooling-java-to-kotlin` | ⚠️ 部分 —— 从 Java 代码移植时参考；本项目是新写 |
| `kotlin-tooling-gradle-to-kotlin-toolchain-plugin` | ⚠️ 仅在写 convention plugin 时用 |
| `kotlin-tooling-immutable-collections-0-5-x-migration` | ⚠️ 仅在用 kotlinx-collections-immutable 时 |
| `kotlin-tooling-agp9-migration` | ❌ Android Gradle Plugin，本项目无关 |
| `kotlin-tooling-cocoapods-spm-migration` | ❌ iOS，本项目无关 |
| `kotlin-tooling-native-build-performance` | ❌ Kotlin/Native，本项目无关 |
| `kotlin-backend-jpa-entity-mapping` | ❌ 后端 JPA，本项目无关 |

> 全量保留是为了将来要做 Android/iOS 版本时直接可用；不适用的技能 `description` 不会命中，不占上下文。
> 这些技能从上游同步，**保留上游 `name`**，不要加 `dev-` 前缀（改名会让下次同步对不上）。

---

## 与 `.design/` 的分工

| 目录 | 性质 | 谁维护 |
|---|---|---|
| `AGENTS.md` | 约束与约定（"必须/禁止"） | 人 + agent，改动需谨慎 |
| `.design/` | **设计事实源**（做什么、为什么） | 人主导，agent 可提修改建议 |
| `.ai/skill/` | 可复用的方法论与踩坑（怎么做） | 人 + agent |

**冲突处理**：`.design/` 与代码冲突时，先改文档或记录偏差到 `07-decisions.md`，不要静默偏离。

---

## 更新方式

项目自有技能来自 Hermes 技能库（`C:\ai\hermes\skill\`）。同步命令（目标是 `.ai/skill/` 一级）：

```bash
cp -r "C:/ai/hermes/skill/compose-multiplatform-desktop" .ai/skill/
cp -r "C:/ai/hermes/skill/desktop-stack-rewrite-assessment" .ai/skill/
```

Kotlin 官方技能更新（上游就是一级目录，可直接摊平到 `.ai/skill/`）：

```bash
git clone --depth 1 https://github.com/Kotlin/kotlin-agent-skills.git /tmp/kas
cp -r /tmp/kas/skills/* .ai/skill/
```

> GitHub 直连不通，clone 与 fetch 都要带代理，见技能 [`global-git-ops`](skill/global-git-ops/SKILL.md)。

更新后在提交信息里写清楚同步了哪个技能的哪个版本。
