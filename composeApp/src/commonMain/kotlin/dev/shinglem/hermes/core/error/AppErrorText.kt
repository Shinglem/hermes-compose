package dev.shinglem.hermes.core.error

/**
 * 把领域错误翻译成一句可读的中文提示。
 *
 * 放在 `core/error` 是因为它被多个功能域共用（能力页、设置页的提供方密钥页……），
 * 不属于任何一个 feature。文案里**不要写死地址/端口** —— 本地模式的后端端口是动态的，
 * remote 也可能指向别的 host。
 */
fun AppError.toNotice(): String = when (this) {
    AppError.BackendUnreachable -> "连不上后端服务，确认状态条已显示「已连接」后重试"
    AppError.Unauthorized -> "未授权：检查 token 是否正确"
    AppError.NotConnected -> "连接未就绪"
    is AppError.Timeout -> "后端响应超时（$operation），稍后重试"
    is AppError.Protocol -> "响应格式异常：$detail"
    is AppError.Server -> "后端返回 $code：$reason"
    is AppError.Notice -> text
    is AppError.Unknown -> summary
}
