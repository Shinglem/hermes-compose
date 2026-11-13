package dev.shinglem.hermes.core.contract

/**
 * 「这份配置/数据属于哪个档案」的**类型**承载（S3）。
 *
 * ### 为什么需要它
 *
 * 0.5.4 修过一个 `4001 not_found` 家族：所有 Repository 方法都是
 * `fun xxx(profile: String?, ...)`，调用方很容易：
 * 1. 把**档案名**和**其它 String 参数**写反（编译器一声不响，因为类型一样）；
 * 2. 忘了传 profile，于是后端用默认档，界面显示的是别的档案的数据；
 * 3. 传了 `"default"` 而该接口要求 `null`（或反过来）—— 两套约定混用。
 *
 * 用 `@JvmInline value class` 包一层之后，上面三种在**类型检查**阶段就过不去，
 * 而且运行时零开销（编译期内联，不装箱）。
 *
 * ### 为什么 `None` 而不是直接 `String?`
 *
 * `profile = null` 在后端有两种不同语义，混在一起就是上面第 3 条：
 * - **`None`（无作用域）**：调 `/api/profiles` 这类"与档案无关"的接口，**不带** `?profile=`
 *   —— 即 [query] 返回 null；
 * - **`"all"`（跨档案）**：`/api/cron/jobs?profile=all` 这类确实要把 `all` 当值发出去。
 *
 * 以前两者都写成 `String?`（`null` vs `"all"`），读代码的人分不清"没传"和"传了 all"。
 * 现在 [All] 与 [None] 是**两个不同的值**，各自有名字。
 */
@JvmInline
value class ProfileScope private constructor(val value: String?) {

    /** 拼进 query 的值：`None` 时返回 null（调用方据此决定是否加 `?profile=`）。 */
    val query: String? get() = value

    val isNone: Boolean get() = value == null

    override fun toString(): String = value ?: "(none)"

    companion object {
        /** 与档案无关的调用（后端用当前默认档，或接口本身不分档案）。 */
        val None = ProfileScope(null)

        /** 显式跨档案（`?profile=all`）。 */
        val All = ProfileScope("all")

        /**
         * 从外部输入构造。
         *
         * `null` / 空串 / `"default"` 都归一成 [None]：后端对"不传"和"传 default"
         * 是同一件事，而且 `default` 是后端自己派生的名字，不该被当成显式作用域。
         */
        fun of(name: String?): ProfileScope {
            val trimmed = name?.trim().orEmpty()
            return when {
                trimmed.isEmpty() || trimmed == DEFAULT_PROFILE -> None
                trimmed == ALL_PROFILES -> All
                else -> ProfileScope(trimmed)
            }
        }

        const val DEFAULT_PROFILE = "default"
        const val ALL_PROFILES = "all"
    }
}
