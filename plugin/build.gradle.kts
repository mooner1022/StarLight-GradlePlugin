import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publish)
    signing
}

group = "io.github.mooner1022.slplugin"
version = libs.versions.slplugin.get()

gradlePlugin {
    website = "https://github.com/mooner1022/StarLight-GradlePlugin"
    vcsUrl = "https://github.com/mooner1022/StarLight-GradlePlugin"
    plugins {
        create("StarLightPlugin") {
            id = "io.github.mooner1022.slplugin"
            displayName = "StarLight Gradle Plugin"
            description = "Builds, validates and deploys StarLight plugins (.slp)"
            tags = listOf("starlight", "android")
            implementationClass = "dev.mooner.slplugin.StarLightPlugin"
        }
    }
}

kotlin {
    jvmToolchain(17)
    // The plugin runs on the Kotlin stdlib embedded in the consumer's Gradle, so keep the API level low.
    // 2.0 is the lowest level supported by the Kotlin compiler that can read Gradle 9's API jars.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

dependencies {
    compileOnly(libs.android.gradle.api)
}

val functionalTestRepo = layout.buildDirectory.dir("functional-test-repo")

publishing {
    repositories {
        maven {
            name = "functionalTest"
            url = uri(functionalTestRepo)
        }
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
        }
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(gradleTestKit())
            }
            targets.all {
                testTask.configure {
                    // Fixtures resolve the plugin from a local Maven repo, exactly like a real consumer would.
                    dependsOn("publishAllPublicationsToFunctionalTestRepository")
                    inputs.files(tasks.named("jar"))
                    systemProperty("slplugin.testRepo", functionalTestRepo.get().asFile.absolutePath)
                    systemProperty("slplugin.version", version.toString())
                    systemProperty("slplugin.agp.min", libs.versions.agp.api.get())
                    systemProperty("slplugin.agp.latest", libs.versions.agp.latest.get())
                    systemProperty("slplugin.testKitDir", layout.projectDirectory.dir(".gradle/testkit").asFile.absolutePath)
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("functionalTest"))
}

// Sign only for Plugin Portal releases. CI passes the key through environment variables;
// locally, signing.* properties in ~/.gradle/gradle.properties are picked up automatically.
val publishingToPortal = gradle.startParameter.taskNames.any { it.endsWith("publishPlugins") }
signing {
    val key = providers.environmentVariable("SIGNING_PGP_KEY").orNull
    if (key != null)
        useInMemoryPgpKeys(key, providers.environmentVariable("SIGNING_PGP_PASSWORD").orNull)
    isRequired = publishingToPortal
}
tasks.withType<Sign>().configureEach {
    onlyIf("signing is only needed for publishPlugins") { publishingToPortal }
}
