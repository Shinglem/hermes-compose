package dev.shinglem.hermes.core.backend

/**
 * 就绪握手的解析（`06-本地后端与双模式.md` §4.3）。
 *
 * 不做行首锚定：uvicorn 的 stderr 块末尾没有换行，哨兵会被拼到
 * `Started server process [4711]` 后面。也正因如此，匹配必须允许出现在任意位置。
 */
object ReadySentinels {
    /** `HERMES_BACKEND_READY port=9120` / `HERMES_DASHBOARD_READY port=9120`。 */
    private val READY = Regex("""(?<!\w)HERMES_(?:BACKEND|DASHBOARD)_READY port=(\d+)""")

    /** 端口被占：把「起不来」与「端口冲突」区分开。 */
    private val PORT_IN_USE = Regex("""BACKEND_PORT_IN_USE port=(\d+)""")

    /** ready 文件内容：`{"port":N}`（也容忍 `{"port":  N}`）。 */
    private val READY_FILE = Regex(""""port"\s*:\s*(\d+)""")

    fun readyPort(text: String): Int? = READY.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun portInUse(text: String): Int? = PORT_IN_USE.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun readyFilePort(json: String): Int? = READY_FILE.find(json)?.groupValues?.get(1)?.toIntOrNull()
}
