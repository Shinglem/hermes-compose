package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.settings.ProfileCreateDraft
import dev.shinglem.hermes.component.settings.ProfilesEffect
import dev.shinglem.hermes.component.settings.ProfilesGateway
import dev.shinglem.hermes.component.settings.ProfilesIntent
import dev.shinglem.hermes.component.settings.ProfilesResult
import dev.shinglem.hermes.component.settings.ProfilesState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.error.toUserNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.isProfileNameValid
import dev.shinglem.hermes.feature.settings.slugifyProfileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 配置档案页的 IO 边界。
 *
 * 写操作一律「提交 → 回读列表」（后端派生字段只出现在列表里），所以每个写动作
 * 成功后都会再调一次 [load]，并用 [ProfilesResult.Loaded.preferred] 指定"希望停在谁上面"。
 */
internal class ProfilesExecutor(
    private val gateway: ProfilesGateway,
    executorContext: CoroutineContext,
) : CoroutineExecutor<ProfilesIntent, Nothing, ProfilesState, ProfilesResult, ProfilesEffect>(executorContext) {

    private var loadJob: Job? = null
    private var soulJob: Job? = null

    override fun executeIntent(intent: ProfilesIntent) {
        when (intent) {
            is ProfilesIntent.Appear -> if (intent.connected) loadIfNeeded()
            ProfilesIntent.Reload -> load()
            is ProfilesIntent.Select -> select(intent.name)
            is ProfilesIntent.EditSoul -> dispatch(ProfilesResult.SoulEdited(intent.text))
            ProfilesIntent.SaveSoul -> saveSoul()
            ProfilesIntent.StartCreate -> dispatch(ProfilesResult.DraftOpened(ProfileCreateDraft()))
            ProfilesIntent.CancelCreate -> dispatch(ProfilesResult.DraftClosed)
            is ProfilesIntent.EditDraftName -> editDraft { it.copy(name = slugifyProfileName(intent.raw)) }
            is ProfilesIntent.EditDraftClone -> editDraft { it.copy(cloneFrom = intent.source) }
            is ProfilesIntent.EditDraftSoul -> editDraft { it.copy(soul = intent.text) }
            ProfilesIntent.SubmitCreate -> submitCreate()
            is ProfilesIntent.Delete -> delete(intent.name)
            is ProfilesIntent.Rename -> rename(intent.name, intent.newName)
            is ProfilesIntent.Import -> import(intent.path)
            is ProfilesIntent.Export -> export(intent.name, intent.output)
            ProfilesIntent.DismissBanner -> dispatch(ProfilesResult.BannersCleared)
        }
    }

    private inline fun editDraft(block: (ProfileCreateDraft) -> ProfileCreateDraft) {
        val draft = state().draft ?: return
        dispatch(ProfilesResult.DraftChanged(block(draft)))
    }

    private fun loadIfNeeded() {
        if (state().list is Loadable.Idle) load()
    }

    private fun load(preferred: String? = null) {
        loadJob?.cancel()
        val stale = state().list.valueOrNull
        dispatch(ProfilesResult.Loading(stale))
        loadJob = scope.launch {
            try {
                dispatch(ProfilesResult.Loaded(gateway.list(), preferred))
                // 选中项还没有 SOUL 内容时补拉一次（与原实现一致）。
                // 注意：`Loaded` 可能刚把选中项换掉（删档场景），此时 state.selected 已是新档，
                // 拉的是新档的 SOUL —— 不能拿 `preferred` 或删除前的名字。
                val selected = state().selected
                if (selected != null && state().soul.valueOrNull == null) loadSoul(selected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.Failed(throwable.asAppError(), stale))
            }
        }
    }

    private fun select(name: String) {
        if (state().selected == name) return
        dispatch(ProfilesResult.Selected(name))
        loadSoul(name)
    }

    private fun loadSoul(name: String) {
        soulJob?.cancel()
        dispatch(ProfilesResult.SoulLoading(name))
        soulJob = scope.launch {
            try {
                dispatch(ProfilesResult.SoulLoaded(name, gateway.soul(name)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.warn("读取 SOUL.md 失败：$name")
                dispatch(ProfilesResult.SoulFailed(name, throwable.asAppError()))
            }
        }
    }

    private fun saveSoul() {
        val name = state().selected ?: return
        if (state().busyKey != null || !state().soulDirty) return
        val content = state().soulDraft
        dispatch(ProfilesResult.Busy("soul"))
        scope.launch {
            try {
                gateway.saveSoul(name, content)
                loadSoul(name)
                dispatch(ProfilesResult.Succeeded("已保存 SOUL.md"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.FailedWithMessage("保存 SOUL.md 失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun submitCreate() {
        val draft = state().draft ?: return
        if (!draft.canSubmit || state().busyKey != null) return
        dispatch(ProfilesResult.Busy("create"))
        scope.launch {
            try {
                val result = gateway.create(
                    name = draft.name,
                    cloneFrom = draft.cloneFrom,
                    soul = draft.soul.takeIf { it.isNotBlank() },
                )
                dispatch(ProfilesResult.DraftClosed)
                val notice = buildString {
                    append("已创建 ${result.name.ifBlank { draft.name }}")
                    if (result.modelError.isNotBlank()) append("（模型未设置：${result.modelError}）")
                }
                dispatch(ProfilesResult.SucceededWithSelection(notice, draft.name))
                load(preferred = draft.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.FailedWithMessage("新建失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun delete(name: String) {
        if (state().busyKey != null) return
        dispatch(ProfilesResult.Busy("delete:$name"))
        scope.launch {
            try {
                gateway.delete(name)
                dispatch(ProfilesResult.SelectedReset(null))
                dispatch(ProfilesResult.Succeeded("已删除 $name"))
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.FailedWithMessage("删除失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun rename(name: String, newName: String) {
        val slug = slugifyProfileName(newName)
        if (!isProfileNameValid(slug) || slug == name || state().busyKey != null) return
        dispatch(ProfilesResult.Busy("rename:$name"))
        scope.launch {
            try {
                gateway.rename(name, slug)
                dispatch(ProfilesResult.SelectedReset(slug))
                dispatch(ProfilesResult.Succeeded("已重命名为 $slug"))
                load(preferred = slug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.FailedWithMessage("重命名失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun import(path: String) {
        if (path.isBlank() || state().busyKey != null) return
        dispatch(ProfilesResult.Busy("import"))
        scope.launch {
            try {
                val name = gateway.import(path, null)
                dispatch(
                    ProfilesResult.SucceededWithSelection(
                        "已导入 ${name.ifBlank { "档案" }}",
                        name.ifBlank { null },
                    ),
                )
                load(preferred = name.ifBlank { null })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.FailedWithMessage("导入失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun export(name: String, output: String) {
        if (output.isBlank() || state().busyKey != null) return
        dispatch(ProfilesResult.Busy("export:$name"))
        scope.launch {
            try {
                val result = gateway.export(name, output)
                dispatch(ProfilesResult.Succeeded("已导出到 ${result.path.ifBlank { output }}"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProfilesResult.FailedWithMessage("导出失败：${throwable.toUserNotice()}"))
            }
        }
    }
}
