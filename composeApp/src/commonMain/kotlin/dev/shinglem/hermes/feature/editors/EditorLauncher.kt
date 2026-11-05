package dev.shinglem.hermes.feature.editors

/**
 * 起一个外部编辑器进程。
 *
 * 平台实现要点（desktop）：**不经过 shell**（`ProcessBuilder` 参数列表），
 * `.cmd` / `.bat` 必须经 `cmd.exe /c` 包装 —— JVM 无法直接执行批处理。
 *
 * @return false 表示起不来（路径不存在、权限、抛异常），调用方应给出可见提示。
 */
expect fun launchEditorProcess(exePath: String, args: List<String>): Boolean
