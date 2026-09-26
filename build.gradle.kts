plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 has built-in Kotlin support; this only pins the Kotlin version
    alias(libs.plugins.kotlin.android) apply false
}
