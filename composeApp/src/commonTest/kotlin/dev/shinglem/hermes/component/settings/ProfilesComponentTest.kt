package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.shinglem.hermes.core.contract.ProfileAdminDto
import dev.shinglem.hermes.core.contract.ProfileCreateResultDto
import dev.shinglem.hermes.core.contract.ProfileExportResultDto
import dev.shinglem.hermes.core.contract.ProfileSoulDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 文件级函数：嵌套类 `FakeGateway` 的默认参数里也能用（不能引外部类成员）。 */
private fun newProfile(name: String, isDefault: Boolean = false) =
    ProfileAdminDto(name = name, isDefault = isDefault)

/**
 * S2 · 配置档案页。
 *
 * 这个域有三条与其它 settings 域不同的规则，都值得断：
 * 1. **没有 profile 维度**（它管理所有档案本身），所以 Component 不随 activeProfile 重建；
 * 2. **写操作一律回读列表**（后端派生字段只在列表里）；
 * 3. **删掉当前选中档后自动回落**（否则右侧详情会指向一个已经不存在的档案）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesComponentTest {

    private fun profile(name: String, isDefault: Boolean = false) = newProfile(name, isDefault)

    private class FakeGateway(
        var profiles: List<ProfileAdminDto> = emptyList(),
        var soulContent: String = "# SOUL",
        var failList: Throwable? = null,
        var failCreate: Throwable? = null,
        var failDelete: Throwable? = null,
    ) : ProfilesGateway {
        var listCount = 0
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val soulSaves = mutableListOf<Pair<String, String>>()

        override suspend fun list(): List<ProfileAdminDto> {
            listCount++
            failList?.let { throw it }
            return profiles
        }

        override suspend fun soul(name: String): ProfileSoulDto = ProfileSoulDto(content = soulContent)

        override suspend fun saveSoul(name: String, content: String) {
            soulSaves += name to content
            soulContent = content
        }

        override suspend fun create(name: String, cloneFrom: String?, soul: String?): ProfileCreateResultDto {
            failCreate?.let { throw it }
            created += name
            profiles = profiles + newProfile(name)
            return ProfileCreateResultDto(name = name)
        }

        override suspend fun delete(name: String) {
            failDelete?.let { throw it }
            deleted += name
            profiles = profiles.filterNot { it.name == name }
        }

        override suspend fun rename(name: String, newName: String) {
            renamed += name to newName
            profiles = profiles.map { if (it.name == name) it.copy(name = newName) else it }
        }

        override suspend fun import(archive: String, name: String?): String = "imported"

        override suspend fun export(name: String, output: String): ProfileExportResultDto =
            ProfileExportResultDto(path = output)
    }

    private fun component(gateway: ProfilesGateway): DefaultProfilesComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultProfilesComponent(
            componentContext = DefaultComponentContext(lifecycle),
            gateway = gateway,
            executorContext = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `未就绪不发请求`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ProfilesIntent.Appear(connected = false))
        assertEquals(0, gateway.listCount)
    }

    @Test
    fun `就绪后读取列表并选中默认档`() = runTest {
        val gateway = FakeGateway(
            profiles = listOf(newProfile("alpha"), newProfile("default", isDefault = true)),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)

        val state = subject.state.value
        assertTrue(state.list is Loadable.Ready)
        assertEquals("default", state.selected, "没指定偏好时应落到默认档")
    }

    @Test
    fun `选中档案会加载 SOUL`() = runTest {
        val gateway = FakeGateway(
            profiles = listOf(newProfile("alpha"), newProfile("default", isDefault = true)),
            soulContent = "# Alpha SOUL",
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.Select("alpha"))

        val state = subject.state.value
        assertEquals("alpha", state.selected)
        assertEquals("# Alpha SOUL", state.soulDraft)
        assertTrue(!state.soulDirty)
    }

    @Test
    fun `编辑 SOUL 后变脏`() = runTest {
        val gateway = FakeGateway(profiles = listOf(newProfile("default", isDefault = true)))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.EditSoul("# 改过的"))

        assertTrue(subject.state.value.soulDirty)
    }

    @Test
    fun `保存 SOUL 后不再脏`() = runTest {
        val gateway = FakeGateway(profiles = listOf(newProfile("default", isDefault = true)))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.EditSoul("# 改过的"))
        subject.onIntent(ProfilesIntent.SaveSoul)

        assertEquals(1, gateway.soulSaves.size)
        assertTrue(!subject.state.value.soulDirty)
        assertNotNull(subject.state.value.notice)
    }

    @Test
    fun `新建档案后回读列表并停在它上面`() = runTest {
        val gateway = FakeGateway(profiles = listOf(newProfile("default", isDefault = true)))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        val before = gateway.listCount

        subject.onIntent(ProfilesIntent.StartCreate)
        subject.onIntent(ProfilesIntent.EditDraftName("new-one"))
        subject.onIntent(ProfilesIntent.SubmitCreate)

        assertEquals(listOf("new-one"), gateway.created)
        assertTrue(gateway.listCount > before, "写操作必须回读列表（后端的派生字段只在列表里）")
        assertNull(subject.state.value.draft, "提交成功后应关掉草稿")
        assertEquals("new-one", subject.state.value.selected)
    }

    @Test
    fun `档案名会被 slug 化`() = runTest {
        val gateway = FakeGateway(profiles = listOf(newProfile("default", isDefault = true)))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.StartCreate)
        subject.onIntent(ProfilesIntent.EditDraftName("My New Profile"))

        val name = subject.state.value.draft?.name.orEmpty()
        assertTrue(name.none { it.isUpperCase() || it == ' ' }, "档案名应统一成小写 slug：$name")
    }

    @Test
    fun `删除当前档案后清空选中`() = runTest {
        val gateway = FakeGateway(
            profiles = listOf(newProfile("alpha"), newProfile("beta")),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.Select("alpha"))
        val before = gateway.listCount

        subject.onIntent(ProfilesIntent.Delete("alpha"))

        assertEquals(listOf("alpha"), gateway.deleted)
        assertTrue(gateway.listCount > before)
        val state = subject.state.value
        // 删掉后应落到剩下的档案，右侧 SOUL 换成新选中那一个（不能还是旧档案的）。
        assertEquals("beta", state.selected, "应回落到剩下的档案")
    }

    @Test
    fun `重命名后选中新名字`() = runTest {
        val gateway = FakeGateway(profiles = listOf(newProfile("alpha"), newProfile("beta")))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.Select("alpha"))
        subject.onIntent(ProfilesIntent.Rename("alpha", "Gamma"))

        assertEquals(listOf("alpha" to "gamma"), gateway.renamed)
        assertEquals("gamma", subject.state.value.selected)
    }

    @Test
    fun `新建失败保留草稿`() = runTest {
        val gateway = FakeGateway(
            profiles = listOf(newProfile("default", isDefault = true)),
            failCreate = AppError.Unknown("nope"),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ProfilesIntent.StartCreate)
        subject.onIntent(ProfilesIntent.EditDraftName("new-one"))
        subject.onIntent(ProfilesIntent.SubmitCreate)

        assertNotNull(subject.state.value.draft, "失败后关掉草稿会让用户白填一遍")
        assertNotNull(subject.state.value.error)
        assertNull(subject.state.value.busyKey)
    }

    @Test
    fun `列表读取失败落成 Failed`() = runTest {
        val gateway = FakeGateway(failList = AppError.Unknown("offline"))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        assertTrue(subject.state.value.list is Loadable.Failed)
    }
}
