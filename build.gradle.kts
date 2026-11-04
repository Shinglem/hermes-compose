// ============================================================================
//  hermes-compose · 根构建脚本
//  ---------------------------------------------------------------------------
//  只声明插件（apply false），实际应用在 composeApp；
//  编译选项集中在模块内，避免根脚本隐式改所有子项目。
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
}
