package dev.shinglem.hermes.platform

/**
 * 把文本写进用户选定的绝对路径（导出会话、另存配置）。
 *
 * 返回 `false` 表示写失败（路径不可写 / 目录不存在）；调用方负责提示，
 * 这里不抛异常 —— 导出失败不该让整个界面崩掉。
 */
expect fun writeTextFile(path: String, text: String): Boolean
