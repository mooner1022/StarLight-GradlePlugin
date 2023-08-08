plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publish)
    signing
}

group = "io.github.mooner1022.slplugin"
version = libs.versions.slplugin.get()

gradlePlugin {
    website.set("https://starlight.mooner.dev/")
    vcsUrl.set("https://github.com/mooner1022/StarLight-GradlePlugin")
    plugins {
        create("StarLightPlugin") {
            id = "io.github.mooner1022.slplugin"
            displayName = "StarLight Gradle Plugin"
            description = "Gradle plugin for developing plugins of StarLight"
            tags.set(listOf("starlight"))
            implementationClass = "dev.mooner.slplugin.StarLightPlugin"
        }
    }
}

kotlin {
    jvmToolchain(11)
}

java {
    withSourcesJar()
    withJavadocJar()
}

/*
// For GItHub Actions CI signing
if (providers.environmentVariable("CI").isPresent) {
    apply(plugin = "signing")
    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(
            providers.environmentVariable("SIGNING_PGP_KEY").orNull,
            providers.environmentVariable("SIGNING_PGP_PASSWORD").orNull
        )
    }
}
 */
