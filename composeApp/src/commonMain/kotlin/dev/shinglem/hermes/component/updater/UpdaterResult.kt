package dev.shinglem.hermes.component.updater

import dev.shinglem.hermes.core.contract.BackendUpdateCheckDto
import dev.shinglem.hermes.core.error.AppError

/** Executor 产出的结果（`Reducer` 的输入）。 */
sealed interface UpdaterResult {
    /** 开始检查。[force] 用于日志区分"用户点的"与"进页面自动查的"。 */
    data class Started(val force: Boolean) : UpdaterResult

    data class Loaded(val dto: BackendUpdateCheckDto) : UpdaterResult

    data class Failed(val error: AppError) : UpdaterResult

    data object Cleared : UpdaterResult
}