package dev.shinglem.hermes.platform

/**
 * 用系统默认程序打开一个**本机文件或目录**。
 *
 * 与 [openExternalUrl] 分开是刻意的：那个只放行 `http` / `https` / `mailto`，
 * 因为它的输入来自模型输出（不能替用户执行本地命令）。这里的路径来自**用户自己**
 * 附加的图片或点开的附件，语义完全不同，需要放行 `file`。
 *
 * 桌面实现走 `rundll32 url.dll,FileProtocolHandler`（Windows 上 `Desktop.browse(File)`
 * 对某些关联不可靠），失败返回 false，由调用方决定是否提示。
 */
expect fun openLocalPath(path: String): Boolean
