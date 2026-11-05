package dev.shinglem.hermes.feature.editors

import dev.shinglem.hermes.core.config.EditorActionKind

/**
 * 参数模板 → 真正的参数列表。
 *
 * 模板形如 `--goto {path}:{line}`，占位符由调用方给出（`path` / `dir` / `line` / `file1` / `file2`）。
 *
 * 两条规则决定了它能不能"一套模板覆盖两种意图"：
 * 1. **取不到值的占位符，让它所在的整个参数被丢弃**，而不是替换成空串。
 *    这样 `--goto {path}:{line} {path}` 在有行号时两个参数都生效（VSCode 取最后一个匹配），
 *    没行号时第一个参数整体消失，等价于 `code path`。
 * 2. **占位符的值以 `-` 开头时丢弃该参数**。`path` 最终来自模型输出，
 *    不拦的话模型写个 `--user-data-dir=...` 就成了参数注入。
 */
fun buildEditorArgs(template: String, vars: Map<String, String>): List<String> {
    val tokens = splitTemplate(template)
    // 先逐 token 解析，null 表示"这个参数取不到完整值"
    val resolved = tokens.map { token -> resolveToken(token, vars) }
    // 再连带丢弃：`--goto {path}:{line}` 缺行号时，值没了，前面的 `--goto` 也必须走 ——
    // 否则会留下一个期待参数的开关，编辑器直接报错（比什么都不做更糟）。
    val out = mutableListOf<String>()
    resolved.forEachIndexed { index, value ->
        if (value != null) {
            out += value
            return@forEachIndexed
        }
        // 往回吃掉紧邻的开关式参数（以 `-` 开头且不是 `--key=value` 形式）
        while (out.isNotEmpty()) {
            val last = out.last()
            if (last.startsWith("-") && !last.contains('=')) out.removeAt(out.lastIndex) else break
        }
    }
    return out
}

/** 单个参数：占位符齐全时返回替换结果，否则 null。 */
private fun resolveToken(token: String, vars: Map<String, String>): String? {
    val resolved = StringBuilder()
    var index = 0
    while (index < token.length) {
        val open = token.indexOf('{', index)
        if (open < 0) {
            resolved.append(token, index, token.length)
            break
        }
        resolved.append(token, index, open)
        val close = token.indexOf('}', open)
        if (close < 0) {
            // 没闭合的花括号当普通字符
            resolved.append(token, open, token.length)
            break
        }
        val name = token.substring(open + 1, close)
        val value = vars[name]
        // 缺值，或值像一个开关（参数注入防护）→ 整个参数作废
        if (value.isNullOrEmpty() || value.startsWith("-")) return null
        resolved.append(value)
        index = close + 1
    }
    return resolved.toString().takeIf { it.isNotEmpty() }
}

/**
 * 按空白切分模板，但**双引号包裹的一段算一个参数**（引号本身去掉）。
 *
 * 不用 shell 分词：结果永远交给 `ProcessBuilder` 的参数列表，不需要 shell 语义；
 * 这里只需要处理"路径里有空格"这一种情况。
 */
internal fun splitTemplate(template: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var hasContent = false
    template.forEach { char ->
        when {
            char == '"' -> {
                quoted = !quoted
                hasContent = true
            }

            char.isWhitespace() && !quoted -> {
                if (hasContent) {
                    out += current.toString()
                    current.setLength(0)
                    hasContent = false
                }
            }

            else -> {
                current.append(char)
                hasContent = true
            }
        }
    }
    if (hasContent) out += current.toString()
    return out
}

/**
 * 某动作实际要执行的参数；不可用（关闭或模板解析为空）时返回 null。
 *
 * 「关闭就回退到打开文件」的规则由调用方实现：拿到 null 时改用
 * [EditorActionKind.OPEN_FILE]。
 */
fun resolveActionArgs(
    kind: EditorActionKind,
    enabled: Boolean,
    template: String,
    vars: Map<String, String>,
): List<String>? {
    if (!enabled) return null
    val effective = template.ifBlank { kind.defaultTemplate }
    return buildEditorArgs(effective, vars).takeIf { it.isNotEmpty() }
}
