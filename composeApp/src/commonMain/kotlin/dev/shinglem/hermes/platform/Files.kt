package dev.shinglem.hermes.platform

/**
 * 把文本写进用户选定的绝对路径（导出会话、另存配置）。
 *
 * 返回 `false` 表示写失败（路径不可写 / 目录不存在）；调用方负责提示，
 * 这里不抛异常 —— 导出失败不该让整个界面崩掉。
 */
expect fun writeTextFile(path: String, text: String): Boolean

/**
 * 读本地文件的原始字节（图片附加要用：后端只接受 base64 内容，不接受路径）。
 *
 * 返回 `null` 表示读失败（文件不存在 / 无权限 / 太大）；调用方按"附加失败"提示。
 */
expect fun readFileBytes(path: String): ByteArray?
