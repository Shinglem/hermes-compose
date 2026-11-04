package dev.shinglem.hermes.core.config

import java.io.File

/**
 * 安装目录与 config 目录的解析，以及由它们派生的各文件路径。
 *
 * **所有相对路径都以 `<home>` 为唯一基准**。不设「相对 config 目录」「相对数据目录」
 * 这类第二基准 —— 多重基准是路径类 bug 的头号来源。
 */
data class ConfigPaths(
    val home: File,
    val configDir: File,
) {
    val configFile: File get() = File(configDir, FILE_CONFIG)
    val customFile: File get() = File(configDir, FILE_CUSTOM)
    val optionsFile: File get() = File(configDir, FILE_OPTIONS)
    val stateFile: File get() = File(configDir, FILE_STATE)
    val jvmArgsFile: File get() = File(configDir, FILE_JVM_ARGS)

    /** 用户覆盖层：设置页只写这两个文件，默认配置保持原样。 */
    val optionsCustomFile: File get() = File(configDir, FILE_OPTIONS_CUSTOM)

    /** 把一个配置里的路径解析成绝对路径：绝对路径原样返回，相对路径相对 `<home>`。 */
    fun resolve(configured: String, fallback: String): File {
        val raw = configured.ifBlank { fallback }
        val file = File(raw)
        return if (file.isAbsolute) file else File(home, raw)
    }

    fun dataDir(config: AppConfig): File = config.app.dataDir?.takeIf { it.isNotBlank() }
        ?.let { resolve(it, "data") }
        ?: defaultDataDir()

    fun logDir(config: AppConfig, dataDir: File): File {
        val dir = File(config.app.log.dir)
        return if (dir.isAbsolute) dir else File(dataDir, config.app.log.dir)
    }

    fun hermesDir(config: AppConfig): File = resolve(config.backend.local.hermesDir, "hermes")

    /**
     * 本地后端的 `HERMES_HOME`。必须是本程序独占的目录 —— 指向 `~/.hermes` 之类的
     * 既有安装会被 [dev.shinglem.hermes.core.backend.LocalBackendPlanner.classifyHome] 拒绝。
     */
    fun hermesHomeDir(config: AppConfig): File = resolve(config.backend.local.homeDir, "hermes-home")

    companion object {
        const val FILE_CONFIG = "config.json"
        const val FILE_CUSTOM = "config_custom.json"
        const val FILE_OPTIONS = "options.json"
        const val FILE_OPTIONS_CUSTOM = "options_custom.json"
        const val FILE_STATE = "state.json"
        const val FILE_JVM_ARGS = "jvm.args"

        /**
         * `<home>` 解析顺序：
         * 1. `-Dhdc.home`
         * 2. 环境变量 `HDC_HOME`
         * 3. 从 `App.jar` 位置推导（`<home>/bin/App.jar` → 上两级）
         * 4. `user.dir`（开发态）
         *
         * 第 3 条不能省：用户完全可能不用 `App.bat` —— 做快捷方式、自己拼 `java -cp`、
         * 被别的启动器拉起。只靠 bat 传参等于把「不经过 bat 就用不了」写死进设计。
         */
        fun resolve(src: ConfigSource): ConfigPaths {
            val home = resolveHome(src)
            val configDirRaw = src.property(PROP_CONFIG_DIR)
                ?: src.env(ENV_CONFIG_DIR)
                ?: DEFAULT_CONFIG_DIR
            val configDirFile = File(configDirRaw)
            val configDir = if (configDirFile.isAbsolute) configDirFile else File(home, configDirRaw)
            return ConfigPaths(home = home, configDir = configDir)
        }

        fun resolveHome(src: ConfigSource): File {
            src.property(PROP_HOME)?.takeIf { it.isNotBlank() }?.let { return canonical(File(it)) }
            src.env(ENV_HOME)?.takeIf { it.isNotBlank() }?.let { return canonical(File(it)) }
            jarDerivedHome()?.let { return canonical(it) }
            return canonical(File(System.getProperty("user.dir") ?: "."))
        }

        /** 安装形态下 jar 位于 `<home>/bin/`；开发/测试形态（classes 目录）不猜，返回 null。 */
        private fun jarDerivedHome(): File? {
            val location = runCatching {
                ConfigPaths::class.java.protectionDomain?.codeSource?.location
            }.getOrNull() ?: return null
            val file = runCatching { File(location.toURI()) }.getOrNull() ?: return null
            if (!file.isFile) return null
            val binDir = file.parentFile ?: return null
            if (binDir.name != "bin") return null
            return binDir.parentFile
        }

        private fun canonical(file: File): File =
            runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

        private fun defaultDataDir(): File {
            val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home").orEmpty()
            return File(root, "hermes-compose")
        }

        const val PROP_HOME = "hdc.home"
        const val PROP_CONFIG_DIR = "hdc.config_dir"
        val ENV_HOME = ConfigEnvNames.HOME
        val ENV_CONFIG_DIR = ConfigEnvNames.CONFIG_DIR
        const val DEFAULT_CONFIG_DIR = "config"
    }
}
