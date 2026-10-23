pluginManagement {
    // Resolves io.github.mooner1022.slplugin from the local plugin build instead of the Plugin Portal.
    includeBuild("plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "StarLight-GradlePlugin"
include(":testApp")
