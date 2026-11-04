package dev.shinglem.hermes.core.app

/** 能否重启自己：开发态（classpath 是 classes 目录）无法还原命令行，只能提示手动重启。 */
enum class RestartSupport { SUPPORTED, DEV_MODE, UNSUPPORTED }

/**
 * 「重启客户端」：把 `config_custom.json` 这类**必须重启才生效**的改动变成一键操作。
 *
 * 语义是"停掉自己的后端 → 用同一条命令行起一个新进程"，然后由调用方退出当前进程。
 * 之所以重启整个进程而不是只重载配置：`app.window.*`、`app.log.*`、`backend.*` 这些
 * 会改变窗口、日志文件与子进程拓扑，热重载做不到干净一致。
 */
interface AppRestarter {

    val support: RestartSupport

    /** 停后端 + 拉起新进程。返回 `true` 表示可以退出当前进程了。 */
    suspend fun restart(): Boolean
}
