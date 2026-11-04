package dev.shinglem.hermes.core.config

import java.io.File
import kotlinx.serialization.json.JsonObject

/** 加载成功的结果：不可变的配置对象 + 它来自哪里（日志与错误提示要用）。 */
data class LoadedConfig(
    val config: AppConfig,
    val paths: ConfigPaths,
    /** true = 本次启动生成了默认 `config.json`（UI 应提示一次）。 */
    val generatedDefault: Boolean = false,
)

/**
 * 配置加载：读文件 → 深合并 → 单点覆盖 → 校验。
 *
 * 失败策略（设计文档 §3.3.7）：
 * - 文件不是合法 JSON / 类型非法 / 枚举或范围非法 → 抛 [ConfigError]，**拒绝启动**
 * - 出现 schema 外的键 → WARN 后继续（用户要求"未知值能通过"）
 */
class ConfigLoader(
    private val source: ConfigSource,
    private val warn: (String) -> Unit = {},
) {
    private val assembler = ConfigAssembler(source, warn)

    fun load(paths: ConfigPaths): LoadedConfig {
        val base = readObjectOrNull(paths.configFile)
        val generated = base == null
        val baseObject = base ?: run {
            // 缺失即用默认值；顺便落一份模板，用户可以照着改。
            runCatching {
                paths.configDir.mkdirs()
                paths.configFile.writeText(DEFAULT_CONFIG_TEMPLATE, Charsets.UTF_8)
            }
            ConfigJson.parseToJsonElement(DEFAULT_CONFIG_TEMPLATE) as JsonObject
        }

        val custom = if (paths.customFile.isFile) readObjectOrNull(paths.customFile) else null
        val config = assembler.assemble(baseObject, custom)
        return LoadedConfig(config = config, paths = paths, generatedDefault = generated)
    }

    /**
     * 读成 `JsonObject`。文件存在但解析失败 → [ConfigError]（拒绝启动）；
     * 文件不存在 → null（交给上游决定生成模板还是跳过）。
     */
    private fun readObjectOrNull(file: File): JsonObject? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { failure ->
            throw ConfigError("读取 ${file.absolutePath} 失败：${failure.message}", failure)
        }
        return try {
            ConfigJson.parseToJsonElement(text) as? JsonObject
                ?: throw ConfigError("${file.absolutePath} 的顶层必须是 JSON 对象（`{ ... }`）")
        } catch (failure: ConfigError) {
            throw failure
        } catch (failure: Throwable) {
            throw ConfigError("${file.absolutePath} 不是合法的 JSON：${failure.message}", failure)
        }
    }

    companion object {
        /** 首次运行时写下的模板：所有默认值都显式列出，方便用户就地修改。 */
        val DEFAULT_CONFIG_TEMPLATE: String = """
            {
              "version": 1,
              "backend": {
                "mode": "remote",
                "local": {
                  "hermes_dir": "hermes",
                  "ready_timeout_ms": 90000,
                  "extra_args": [],
                  "run_cron": true,
                  "home_dir": "data/agent"
                },
                "remote": {
                  "base_url": "http://127.0.0.1:9119",
                  "token": {
                    "source": "env_file",
                    "value": null
                  },
                  "connect_timeout_ms": 10000
                }
              },
              "app": {
                "profile": null,
                "data_dir": null,
                "log_level": "INFO",
                "log": {
                  "dir": "logs",
                  "keep_days": 14,
                  "max_files": 20,
                  "also_stderr": true
                },
                "window": {
                  "width": 1440,
                  "height": 900,
                  "min_width": 900,
                  "min_height": 600
                },
                "close_to_tray": true
              }
            }
        """.trimIndent()
    }
}

/** 进程级真实来源：JVM 系统属性 + 进程环境变量。 */
object SystemConfigSource : ConfigSource {
    override fun property(key: String): String? = System.getProperty(key)
    override fun env(key: String): String? = System.getenv(key)
    override fun envNames(): Set<String> = System.getenv().keys
    override fun propertyNames(): Set<String> = System.getProperties().stringPropertyNames()
}
