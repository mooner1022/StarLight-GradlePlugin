plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied: AGP 9's built-in Kotlin uses the Kotlin Gradle plugin on the classpath, so this pins it to
    // the Kotlin version of the StarLight app instead of the one bundled with AGP.
    alias(libs.plugins.kotlin.android) apply false
}
