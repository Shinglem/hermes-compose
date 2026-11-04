package dev.shinglem.hermes.core.app

/**
 * 一条"失效的自启动任务"：名字是本产品的（`HermesCompose-<hash>`），但指向的程序已经不存在。
 *
 * 典型来源：便携包从 `D:\Hermes` 挪到 `E:\Hermes` —— 任务名带 `<home>` 哈希，挪完就是新名字，
 * 旧任务留在系统里、每次登录都失败一次。
 *
 * 只用于**展示与逐条确认删除**，不做任何自动清理（本机就有个名字带 `Hermes` 前缀但指向
 * `Netcatty.exe` 的任务，按前缀批量删会误伤）。
 */
data class OrphanTaskInfo(
    val name: String,
    /** 任务里记录的 `<Command>` 路径（已确认该文件不存在）。 */
    val command: String,
)
