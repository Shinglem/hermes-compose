package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.error.toNotice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.CronJobCreate
import dev.shinglem.hermes.core.contract.CronJobDto
import dev.shinglem.hermes.core.contract.CronJobPatch
import dev.shinglem.hermes.core.contract.MessagingPlatformDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 能力三页的状态持有者。
 *
 * 写操作策略：**乐观更新 + 失败回滚 + 成功后回读**。回读是必须的 —— 后端的
 * 开关会牵连派生状态（例如平台 `state`、工具集 `available`），只有回读才拿到真值。
 */
class CapabilityViewModel(
    private val repository: CapabilityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CapabilityState.initial())
    val state: StateFlow<CapabilityState> = _state.asStateFlow()

    /** 当前 profile：由 AppShell 在切换时写入，所有请求都带上它。 */
    private var profile: String? = null

    fun setProfile(next: String?) {
        if (next == profile) return
        profile = next
        // profile 变了，三页数据全部作废（跨域约定 §3.1）。
        _state.update {
            it.copy(
                skills = Loadable.Idle,
                toolsets = Loadable.Idle,
                platforms = Loadable.Idle,
                cronJobs = Loadable.Idle,
                blueprints = emptyList(),
                deliveryTargets = emptyList(),
                expandedSkill = null,
                expandedPlatform = null,
                expandedJob = null,
                catalog = Loadable.Idle,
                expandedCatalogItem = null,
                cronDraft = null,
                blueprintDraft = null,
            )
        }
        // 只有能力模式可见时才立刻重拉，否则等用户切过去再拉（selectSection 会兜底）。
        if (visible) selectSection(_state.value.section)
    }

    /** 能力模式是否正在显示：决定"进页即拉"要不要现在就跑。 */
    var visible: Boolean = false
        private set

    fun onVisible() {
        visible = true
        selectSection(_state.value.section)
    }

    fun onHidden() {
        visible = false
    }

    fun selectSection(section: CapabilitySection) {
        _state.update { it.copy(section = section) }
        // 进页即拉：只拉这一页需要的，别把三页全打一遍。
        when (section) {
            CapabilitySection.SKILLS -> {
                if (_state.value.skills.valueOrNull == null) refreshSkills()
                if (_state.value.toolsets.valueOrNull == null) refreshToolsets()
            }

            CapabilitySection.PLATFORMS -> if (_state.value.platforms.valueOrNull == null) refreshPlatforms()

            CapabilitySection.CRON -> refreshCron()
        }
    }

    fun selectSkillsTab(tab: SkillsTab) {
        _state.update { it.copy(skillsTab = tab) }
        when (tab) {
            SkillsTab.TOOLSETS -> if (_state.value.toolsets.valueOrNull == null) refreshToolsets()
            // 目录页只在用户真的切过去时才拉：它是 150+ 项的扫描，别跟着技能页一起打。
            SkillsTab.DISCOVER -> if (_state.value.catalog.valueOrNull == null) refreshCatalog()
            SkillsTab.SKILLS -> Unit
        }
    }

    fun setCatalogCategory(category: String?) {
        _state.update { it.copy(catalogCategory = category) }
    }

    fun toggleCatalogItem(identifier: String) {
        val current = _state.value.expandedCatalogItem
        _state.update { it.copy(expandedCatalogItem = if (current == identifier) null else identifier) }
    }

    fun refreshCatalog() = runLoad(
        get = { it.catalog.valueOrNull },
        set = { copy(catalog = it) },
        load = { repository.skillCatalog(profile) },
    )

    /**
     * 安装目录里的技能。
     *
     * 后端是异步起 CLI 的，回执只代表"已提交"；这里等一小会儿再回读目录，
     * 让 `installed` 标记与"已安装技能"列表尽量跟上。真正的最终状态以列表为准。
     */
    fun installCatalogSkill(identifier: String) {
        if (_state.value.busyKey == identifier) return
        _state.update { it.copy(busyKey = identifier) }
        viewModelScope.launch {
            try {
                repository.installSkill(profile, identifier)
                // 给 CLI 一点落地时间再回读；失败也只是标记没刷新，不影响安装本身。
                kotlinx.coroutines.delay(1_500)
                val catalog = runCatching { repository.skillCatalog(profile) }.getOrNull()
                val skills = runCatching { repository.skills(profile) }.getOrNull()
                _state.update { state ->
                    state.copy(
                        catalog = catalog?.let { Loadable.Ready(it) } ?: state.catalog,
                        skills = skills?.let { Loadable.Ready(it) } ?: state.skills,
                        busyKey = null,
                        notice = "已提交安装：$identifier",
                        noticeIsError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(busyKey = null, notice = "安装失败：${throwable.describe()}", noticeIsError = true)
                }
            }
        }
    }

    fun setSkillQuery(query: String) {
        _state.update { it.copy(skillQuery = query) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ── 读 ──────────────────────────────────────────────────────────────

    fun refreshSkills() = runLoad(
        set = { copy(skills = it) },
        load = { repository.skills(profile) },
    )

    fun refreshToolsets() = runLoad(
        set = { copy(toolsets = it) },
        load = { repository.toolsets(profile) },
    )

    fun refreshPlatforms() = runLoad<MessagingPlatformDto>(
        get = { it.platforms.valueOrNull },
        set = { copy(platforms = it) },
        load = { repository.platforms(profile).platforms },
    )

    fun refreshCron() {
        runLoad<CronJobDto>(
            get = { it.cronJobs.valueOrNull },
            set = { copy(cronJobs = it) },
            load = { repository.cronJobs(profile) },
        )
        // 表单需要的两个下拉源；失败不阻塞主列表。
        viewModelScope.launch {
            runCatching { repository.blueprints(profile) }
                .onSuccess { list -> _state.update { it.copy(blueprints = list) } }
            runCatching { repository.deliveryTargets(profile) }
                .onSuccess { list -> _state.update { it.copy(deliveryTargets = list) } }
        }
    }

    // ── 技能 / 工具集 ───────────────────────────────────────────────────

    fun toggleSkill(name: String, enabled: Boolean) {
        val current = _state.value.skills.valueOrNull ?: return
        // 乐观更新：先把开关切过去，失败再整体回滚。
        val optimistic = current.map { if (it.name == name) it.copy(enabled = enabled) else it }
        _state.update { it.copy(skills = Loadable.Ready(optimistic), busyKey = name) }
        viewModelScope.launch {
            try {
                repository.setSkillEnabled(profile, name, enabled)
                val fresh = repository.skills(profile)
                _state.update {
                    it.copy(
                        skills = Loadable.Ready(fresh),
                        busyKey = null,
                        notice = if (enabled) "已启用 $name" else "已停用 $name",
                        noticeIsError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        skills = Loadable.Ready(current),
                        busyKey = null,
                        notice = "开关失败：${throwable.describe()}",
                        noticeIsError = true,
                    )
                }
            }
        }
    }

    fun toggleToolset(name: String, enabled: Boolean) {
        val current = _state.value.toolsets.valueOrNull ?: return
        val optimistic = current.map { if (it.name == name) it.copy(enabled = enabled) else it }
        _state.update { it.copy(toolsets = Loadable.Ready(optimistic), busyKey = name) }
        viewModelScope.launch {
            try {
                repository.setToolsetEnabled(profile, name, enabled)
                val fresh = repository.toolsets(profile)
                _state.update {
                    it.copy(toolsets = Loadable.Ready(fresh), busyKey = null, notice = "已更新 $name", noticeIsError = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        toolsets = Loadable.Ready(current),
                        busyKey = null,
                        notice = "开关失败：${throwable.describe()}",
                        noticeIsError = true,
                    )
                }
            }
        }
    }

    /** 展开 / 收起技能详情；展开时按需拉 SKILL.md 正文。 */
    fun toggleSkillDetail(name: String) {
        if (_state.value.expandedSkill == name) {
            _state.update { it.copy(expandedSkill = null, skillContent = Loadable.Idle) }
            return
        }
        _state.update { it.copy(expandedSkill = name, skillContent = Loadable.Loading) }
        viewModelScope.launch {
            try {
                val content = repository.skillContent(profile, name)
                _state.update { it.copy(skillContent = Loadable.Ready(content)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                _state.update { it.copy(skillContent = Loadable.Failed(error)) }
            }
        }
    }

    // ── 消息平台 ────────────────────────────────────────────────────────

    fun togglePlatformDetail(platformId: String) {
        _state.update {
            it.copy(expandedPlatform = if (it.expandedPlatform == platformId) null else platformId)
        }
    }

    /** 表单输入：记进 draft，保存时才发给后端（后端 `env` 是增量语义）。 */
    fun setPlatformField(platformId: String, key: String, value: String) {
        _state.update { current ->
            val drafts = current.platformDrafts.toMutableMap()
            val fields = (drafts[platformId] ?: emptyMap()).toMutableMap()
            if (value.isEmpty()) fields.remove(key) else fields[key] = value
            drafts[platformId] = fields
            current.copy(platformDrafts = drafts)
        }
    }

    fun clearPlatformDraft(platformId: String) {
        _state.update { it.copy(platformDrafts = it.platformDrafts - platformId) }
    }

    /** 保存平台配置：只提交用户实际填写（非空）的键。 */
    fun savePlatform(platformId: String, enabled: Boolean?) {
        val env = _state.value.platformDrafts[platformId].orEmpty()
        // busyKey 带动作后缀：同一个按钮组的"保存"与"测试"要分别显示进度文案。
        _state.update { it.copy(busyKey = platformBusyKey(platformId, PlatformAction.SAVE)) }
        viewModelScope.launch {
            try {
                val result = repository.updatePlatform(profile, platformId, enabled, env)
                val fresh = repository.platforms(profile).platforms
                _state.update {
                    it.copy(
                        platforms = Loadable.Ready(fresh),
                        busyKey = null,
                        // 保存成功后清掉 draft：已保存的值后端不回显，留着会误导。
                        platformDrafts = it.platformDrafts - platformId,
                        // hot_served=false 表示该平台要重启网关才生效，得告诉用户。
                        notice = if (result.hotServed) "已保存" else "已保存；该平台需要重启网关才生效",
                        noticeIsError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                // 失败要回读：后端可能已写入 .env 但 enabled 落盘失败。
                runCatching { repository.platforms(profile).platforms }.onSuccess { fresh ->
                    _state.update { it.copy(platforms = Loadable.Ready(fresh)) }
                }
                _state.update {
                    it.copy(busyKey = null, notice = "保存失败：${throwable.describe()}", noticeIsError = true)
                }
            }
        }
    }

    fun testPlatform(platformId: String) {
        _state.update { it.copy(busyKey = platformBusyKey(platformId, PlatformAction.TEST)) }
        viewModelScope.launch {
            try {
                val ack = repository.testPlatform(profile, platformId)
                // 后端会把"为什么不行"写在 message 里（例如未启用 / 需要重启网关），优先用它。
                val reason = ack.message ?: ack.detail
                val notice = when {
                    ack.ok -> "连接测试通过"
                    reason != null -> "测试未通过：$reason"
                    else -> "连接测试未通过"
                }
                _state.update { it.copy(busyKey = null, notice = notice, noticeIsError = !ack.ok) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(busyKey = null, notice = "测试失败：${throwable.describe()}", noticeIsError = true)
                }
            }
        }
    }

    // ── 定时任务 ────────────────────────────────────────────────────────

    /** 蓝图实例化：把槽位值填进蓝图并创建任务。 */
    fun instantiateBlueprint(blueprintKey: String, values: Map<String, String>) {
        _state.update { it.copy(busyKey = "blueprint:$blueprintKey") }
        viewModelScope.launch {
            try {
                repository.instantiateBlueprint(profile, blueprintKey, values)
                val fresh = repository.cronJobs(profile)
                _state.update {
                    it.copy(
                        cronJobs = Loadable.Ready(fresh),
                        busyKey = null,
                        blueprintDraft = null,
                        notice = "已按蓝图创建任务",
                        noticeIsError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(busyKey = null, notice = "创建失败：${throwable.describe()}", noticeIsError = true)
                }
            }
        }
    }

    /** 选中一个蓝图 → 用它的 default 值预填表单。 */
    fun startBlueprint(key: String) {
        val blueprint = _state.value.blueprints.firstOrNull { it.key == key } ?: return
        _state.update {
            it.copy(
                blueprintDraft = BlueprintDraft(
                    key = blueprint.key,
                    title = blueprint.title,
                    values = blueprint.fields.associate { field -> field.name to field.default },
                ),
                cronDraft = null,
            )
        }
    }

    fun updateBlueprintValue(field: String, value: String) {
        _state.update { current ->
            val draft = current.blueprintDraft ?: return@update current
            current.copy(blueprintDraft = draft.copy(values = draft.values + (field to value)))
        }
    }

    fun cancelBlueprint() {
        _state.update { it.copy(blueprintDraft = null) }
    }

    fun startCreateCron() {
        _state.update { it.copy(cronDraft = CronDraft()) }
    }

    /** 进入编辑：用现有任务回填表单。 */
    fun startEditCron(jobId: String) {
        val job = _state.value.cronJobs.valueOrNull.orEmpty().firstOrNull { it.id == jobId } ?: return
        _state.update { it.copy(cronDraft = CronDraft.from(job), expandedJob = null) }
    }

    fun cancelCronDraft() {
        _state.update { it.copy(cronDraft = null) }
    }

    fun updateCronDraft(transform: (CronDraft) -> CronDraft) {
        _state.update { current ->
            val draft = current.cronDraft ?: return@update current
            current.copy(cronDraft = transform(draft))
        }
    }

    /** 保存草稿：新建走 POST，编辑走 PUT（只提交真正改过的字段）。 */
    fun saveCronDraft() {
        val draft = _state.value.cronDraft ?: return
        if (!draft.canSave) {
            _state.update { it.copy(notice = "填完 cron 与任务内容再保存", noticeIsError = true) }
            return
        }
        if (!draft.scheduleLooksValid) {
            _state.update { it.copy(notice = "cron 表达式需要 5 段，例如 0 9 * * *", noticeIsError = true) }
            return
        }
        val editing = draft.editingJobId
        _state.update { it.copy(busyKey = if (editing == null) "cron:new" else editing) }
        viewModelScope.launch {
            try {
                if (editing == null) {
                    repository.createCronJob(
                        profile,
                        CronJobCreate(
                            schedule = draft.schedule.trim(),
                            prompt = draft.prompt.trim(),
                            name = draft.name.trim(),
                            deliver = draft.deliver.ifBlank { "local" },
                            profile = profile,
                        ),
                    )
                } else {
                    repository.updateCronJob(
                        profile,
                        editing,
                        CronJobPatch(
                            name = draft.name.trim(),
                            prompt = draft.prompt.trim(),
                            schedule = draft.schedule.trim(),
                            deliver = draft.deliver.ifBlank { "local" },
                        ),
                    )
                }
                val fresh = repository.cronJobs(profile)
                _state.update {
                    it.copy(
                        cronJobs = Loadable.Ready(fresh),
                        busyKey = null,
                        cronDraft = null,
                        notice = if (editing == null) "任务已创建" else "任务已更新",
                        noticeIsError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        busyKey = null,
                        notice = (if (editing == null) "创建失败：" else "更新失败：") + throwable.describe(),
                        noticeIsError = true,
                    )
                }
            }
        }
    }

    fun setCronPaused(jobId: String, paused: Boolean) {
        _state.update { it.copy(busyKey = jobId) }
        viewModelScope.launch {
            try {
                if (paused) repository.pauseCronJob(profile, jobId) else repository.resumeCronJob(profile, jobId)
                val fresh = repository.cronJobs(profile)
                _state.update {
                    it.copy(cronJobs = Loadable.Ready(fresh), busyKey = null, notice = if (paused) "已暂停" else "已恢复", noticeIsError = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, notice = "操作失败：${throwable.describe()}", noticeIsError = true) }
            }
        }
    }

    fun triggerCron(jobId: String) {
        _state.update { it.copy(busyKey = jobId) }
        viewModelScope.launch {
            try {
                repository.triggerCronJob(profile, jobId)
                _state.update { it.copy(busyKey = null, notice = "已触发，稍后刷新看结果", noticeIsError = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, notice = "触发失败：${throwable.describe()}", noticeIsError = true) }
            }
        }
    }

    fun deleteCron(jobId: String) {
        _state.update { it.copy(busyKey = jobId) }
        viewModelScope.launch {
            try {
                repository.deleteCronJob(profile, jobId)
                val fresh = repository.cronJobs(profile)
                _state.update {
                    it.copy(cronJobs = Loadable.Ready(fresh), busyKey = null, notice = "已删除", noticeIsError = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, notice = "删除失败：${throwable.describe()}", noticeIsError = true) }
            }
        }
    }

    fun toggleCronRuns(jobId: String) {
        if (_state.value.expandedJob == jobId) {
            _state.update { it.copy(expandedJob = null, cronRuns = Loadable.Idle) }
            return
        }
        _state.update { it.copy(expandedJob = jobId, cronRuns = Loadable.Loading) }
        viewModelScope.launch {
            try {
                val runs = repository.cronRuns(profile, jobId)
                _state.update { it.copy(cronRuns = Loadable.Ready(runs)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                _state.update { it.copy(cronRuns = Loadable.Failed(error)) }
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 一次装载：进入 Loading → 成功 Ready / 失败 Failed（保留旧值做 `stale`）。
     * 失败时不弹 notice：页面自身的错误态已经足够，避免番茄钟式弹窗。
     *
     * 每个页面各起一个 job（共用会被别的页取消掉），最后一次写入赢。
     */
    private fun <T> runLoad(
        get: (CapabilityState) -> List<T>? = { null },
        set: CapabilityState.(Loadable<List<T>>) -> CapabilityState,
        load: suspend () -> List<T>,
    ) {
        val stale = get(_state.value)
        viewModelScope.launch {
            if (stale == null) _state.update { it.set(Loadable.Loading) }
            try {
                val value = load()
                _state.update { it.set(Loadable.Ready(value)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                _state.update { it.set(Loadable.Failed(error, stale)) }
            }
        }
    }
}

/** 把任意异常收敛成一句提示（`AppError` 走它自己的映射）。 */
private fun Throwable.describe(): String =
    (this as? AppError)?.toNotice() ?: (message ?: "未知错误")
