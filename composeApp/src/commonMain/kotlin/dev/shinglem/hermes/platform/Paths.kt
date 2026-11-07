package dev.shinglem.hermes.platform

/** 配置 / 日志 / 缓存根目录（desktop: `%LOCALAPPDATA%\hermes-compose`）。 */
expect val appDataDir: String

/** 把字节写到本地文件（备份下载用）；目录不存在时自动创建。失败返回 false。 */
expect fun writeBytesToFile(path: String, bytes: ByteArray): Boolean

/** 读本地文本文件（会话导入用）；失败返回 null。 */
expect fun readLocalTextFile(path: String): String?
