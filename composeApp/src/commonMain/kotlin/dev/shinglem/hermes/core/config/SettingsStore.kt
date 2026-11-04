package dev.shinglem.hermes.core.config

import kotlinx.serialization.json.JsonObject

/**
 * 设置页的持久化入口：**只写 `config_custom.json` / `options_custom.json`**，
 * 默认的 `config.json` / `options.json` 保持原样（它们是随版本下发的模板）。
 *
 * 写入是「深合并 + 覆盖」，不是整文件替换：用户在设置页只改了一个键，
 * 不该把同一份 custom 文件里其它键（可能是手工加的）冲掉。
 */
interface SettingsStore {
    /** 需要重启才生效的配置。 */
    fun saveConfigPatch(patch: JsonObject)

    /** 立即生效的选项（写完 `OptionsWatcher` 会自己重载）。 */
    fun saveOptionsPatch(patch: JsonObject)
}
