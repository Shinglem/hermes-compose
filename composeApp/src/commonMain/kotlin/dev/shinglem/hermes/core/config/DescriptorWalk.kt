package dev.shinglem.hermes.core.config

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * 沿 `app.log.keep_days` 这样的路径在 [SerialDescriptor] 上导航到目标字段的 descriptor。
 *
 * 这是「环境变量能按字段类型解析」的前提：拿到 descriptor 才知道这个键是 int / bool /
 * enum / 数组 / 对象。
 */
fun descriptorAt(root: SerialDescriptor, keyPath: String): SerialDescriptor? {
    var current: SerialDescriptor = root
    for (segment in keyPath.split('.')) {
        if (current.kind != StructureKind.CLASS) return null
        val index = (0 until current.elementsCount)
            .firstOrNull { current.getElementName(it) == segment } ?: return null
        current = current.getElementDescriptor(index)
    }
    return current
}

/**
 * 递归列出全部合法键路径（`a.b.c` 形式）。
 *
 * **不手写维护**：直接从 descriptor 遍历，`AppConfig` 加字段后自动生效。
 * 数组与标量视作叶子（数组元素的内部结构不做键路径）。
 */
fun knownConfigKeyPaths(root: SerialDescriptor): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(descriptor: SerialDescriptor, prefix: String) {
        if (descriptor.kind != StructureKind.CLASS) return
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            out += path
            walk(descriptor.getElementDescriptor(i), path)
        }
    }
    walk(root, "")
    return out
}

/** 键路径 → `HDC_` 环境变量名（`backend.remote.base_url` → `HDC_BACKEND_REMOTE_BASE_URL`）。 */
fun keyPathToEnvName(keyPath: String): String =
    "HDC_" + keyPath.uppercase().replace('.', '_')

/** 键路径 → JVM 属性名（`backend.remote.base_url` → `hdc.backend.remote.base_url`）。 */
fun keyPathToPropertyName(keyPath: String): String = "hdc.$keyPath"

/** 按路径在 JSON 树里取子节点；用于判断某个键是否被某个文件显式写过。 */
fun serializerDescriptorOfConfig(): SerialDescriptor = AppConfig.serializer().descriptor

/** 供调试/日志用：确认某个 kind 是叶子类型。 */
internal fun SerialDescriptor.isLeaf(): Boolean = kind != SerialKind.CONTEXTUAL && kind != StructureKind.CLASS
