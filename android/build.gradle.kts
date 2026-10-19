plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself. Declaring the Kotlin plugin here only pins the compiler version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.chaquopy) apply false
}
