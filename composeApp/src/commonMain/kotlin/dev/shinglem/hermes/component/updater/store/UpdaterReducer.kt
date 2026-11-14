package dev.shinglem.hermes.component.updater.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.updater.UpdaterResult
import dev.shinglem.hermes.component.updater.UpdaterState

/**
 * 检查更新页的**纯**状态迁移：没有协程、没有 IO。
 *
 * 可以直接 `UpdaterReducer().run { UpdaterState().reduce(...) }` 断言
 * （见 `UpdaterReducerTest`），不需要 ComponentContext。
 */
internal class UpdaterReducer : Reducer<UpdaterState, UpdaterResult> {

    override fun UpdaterState.reduce(msg: UpdaterResult): UpdaterState = when (msg) {
        // 已有结果时不要清掉：用户再点一次时旧结果还在，只是按钮转圈
        // （迁移前就是这么做的，清掉会让面板闪一下空白）。
        is UpdaterResult.Started -> copy(checking = true, error = null)

        is UpdaterResult.Loaded -> copy(checking = false, result = msg.dto, error = null)

        // 失败**保留**旧的 result：后端挂了不该让已经拿到的版本信息消失。
        is UpdaterResult.Failed -> copy(checking = false, error = msg.error)

        UpdaterResult.Cleared -> copy(error = null)
    }
}