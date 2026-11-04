package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.ProfileAdminDto
import dev.shinglem.hermes.core.contract.ProfileSoulDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 新建档案对话框的草稿。 */
data class ProfileCreateDraft(
    val name: String = "",
    /** null = 不克隆；`"default"` = 克隆默认档案（后端语义）。 */
    val cloneFrom: String? = DEFAULT_PROFILE,
    val soul: String = "",
) {
    val nameValid: Boolean get() = isProfileNameValid(name)
    val canSubmit: Boolean get() = nameValid
}

data class ProfilesState(
    val list: Loadable<List<ProfileAdminDto>> = Loadable.Idle,
    val selected: String? = null,
    val soul: Loadable<ProfileSoulDto> = Loadable.Idle,
    /** SOUL.md 编辑器里的文本（与后端读到的内容不同即为脏）。 */
    val soulDraft: String = "",
    val draft: ProfileCreateDraft? = null,
    /** 正在进行的写操作（按钮禁用 + 转圈）：`"delete:<name>"` / `"save-soul"` / … */
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val profiles: List<ProfileAdminDto> get() = list.valueOrNull.orEmpty()

    val current: ProfileAdminDto? get() = profiles.firstOrNull { it.name == selected }

    val soulDirty: Boolean
        get() = soul.valueOrNull?.let { soulDraft != it.content } == true
}

/**
 * 配置档案页的状态持有者。
 *
 * 写操作一律「提交 → 回读列表」，因为后端会派生一堆字段（`has_env`、`skill_count`、
 * `gateway_running`），回执里没有。删除当前选中档后自动回落到 `default`。
 */
class ProfilesViewModel(
    private val repository: ProfilesAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    private var loaded = false

    fun onVisible() {
        if (!loaded) refresh()
    }

    fun refresh() {
        val stale = _state.value.list.valueOrNull
        viewModelScope.launch {
            if (stale == null) _state.update { it.copy(list = Loadable.Loading) }
            try {
                val profiles = repository.list()
                loaded = true
                _state.update { state ->
                    val selected = state.selected
                        ?.takeIf { name -> profiles.any { it.name == name } }
                        ?: profiles.firstOrNull { it.isDefault }?.name
                        ?: profiles.firstOrNull()?.name
                    state.copy(list = Loadable.Ready(profiles), selected = selected, error = null)
                }
                _state.value.selected?.let { if (_state.value.soul.valueOrNull == null) loadSoul(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                _state.update { it.copy(list = Loadable.Failed(error, stale)) }
            }
        }
    }

    fun select(name: String) {
        if (_state.value.selected == name) return
        _state.update { it.copy(selected = name, soul = Loadable.Idle, soulDraft = "") }
        loadSoul(name)
    }

    fun editSoul(text: String) {
        _state.update { it.copy(soulDraft = text, notice = null) }
    }

    fun saveSoul() {
        val name = _state.value.selected ?: return
        if (_state.value.busyKey != null || !_state.value.soulDirty) return
        val content = _state.value.soulDraft
        _state.update { it.copy(busyKey = "soul", error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.saveSoul(name, content)
                loadSoul(name)
                _state.update { it.copy(busyKey = null, notice = "已保存 SOUL.md") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存 SOUL.md 失败：${throwable.describe()}") }
            }
        }
    }

    // ── 新建 / 删除 / 重命名 ─────────────────────────────────────────────

    fun startCreate() {
        _state.update { it.copy(draft = ProfileCreateDraft(), error = null, notice = null) }
    }

    fun cancelCreate() {
        _state.update { it.copy(draft = null) }
    }

    fun editDraftName(raw: String) {
        _state.update { state -> state.copy(draft = state.draft?.copy(name = slugifyProfileName(raw))) }
    }

    fun editDraftClone(source: String?) {
        _state.update { state -> state.copy(draft = state.draft?.copy(cloneFrom = source)) }
    }

    fun editDraftSoul(text: String) {
        _state.update { state -> state.copy(draft = state.draft?.copy(soul = text)) }
    }

    fun submitCreate() {
        val draft = _state.value.draft ?: return
        if (!draft.canSubmit || _state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "create", error = null) }
        viewModelScope.launch {
            try {
                val result = repository.create(
                    name = draft.name,
                    cloneFrom = draft.cloneFrom,
                    soul = draft.soul.takeIf { it.isNotBlank() },
                )
                loaded = false
                _state.update {
                    it.copy(
                        draft = null,
                        busyKey = null,
                        notice = buildString {
                            append("已创建 ${result.name.ifBlank { draft.name }}")
                            if (result.modelError.isNotBlank()) append("（模型未设置：${result.modelError}）")
                        },
                    )
                }
                refresh()
                select(draft.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "新建失败：${throwable.describe()}") }
            }
        }
    }

    fun delete(name: String) {
        if (_state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "delete:$name", error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.delete(name)
                loaded = false
                _state.update {
                    it.copy(busyKey = null, notice = "已删除 $name", selected = null, soul = Loadable.Idle, soulDraft = "")
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "删除失败：${throwable.describe()}") }
            }
        }
    }

    fun rename(name: String, newName: String) {
        val slug = slugifyProfileName(newName)
        if (!isProfileNameValid(slug) || slug == name || _state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "rename:$name", error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.rename(name, slug)
                loaded = false
                _state.update { it.copy(busyKey = null, selected = slug, soul = Loadable.Idle, notice = "已重命名为 $slug") }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "重命名失败：${throwable.describe()}") }
            }
        }
    }

    // ── 导入 / 导出 ─────────────────────────────────────────────────────

    fun import(path: String) {
        if (path.isBlank() || _state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "import", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val name = repository.import(path, null)
                loaded = false
                _state.update { it.copy(busyKey = null, notice = "已导入 ${name.ifBlank { "档案" }}", selected = name.ifBlank { null }) }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "导入失败：${throwable.describe()}") }
            }
        }
    }

    fun export(name: String, output: String) {
        if (output.isBlank() || _state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "export:$name", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val result = repository.export(name, output)
                _state.update { it.copy(busyKey = null, notice = "已导出到 ${result.path.ifBlank { output }}") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "导出失败：${throwable.describe()}") }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun loadSoul(name: String) {
        _state.update { it.copy(soul = Loadable.Loading) }
        viewModelScope.launch {
            try {
                val soul = repository.soul(name)
                _state.update { if (it.selected == name) it.copy(soul = Loadable.Ready(soul), soulDraft = soul.content) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                Log.warn("读取 SOUL.md 失败：$name")
                _state.update { if (it.selected == name) it.copy(soul = Loadable.Failed(error)) else it }
            }
        }
    }
}

private fun Throwable.describe(): String =
    (this as? AppError)?.toNotice() ?: (message ?: "未知错误")

/** 克隆来源下拉里的「不克隆」哨兵值。 */
internal const val CLONE_NONE = "__none__"

internal const val DEFAULT_PROFILE = "default"
