import org.gradle.plugin.compatibility.compatibility
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
            compatibility {
                features {
                    // Covered by the functional tests, which store and reuse the configuration cache.
                    configurationCache = true
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
    // The plugin runs inside the consumer's Gradle, on its embedded Kotlin compiler and stdlib. The minimum
    // supported Gradle is 8.11 (the StarLight app's), which reads classes compiled at level 2.2; Gradle 8.0-8.5
    // cannot. Its stdlib is 2.0.21, so avoid stdlib APIs newer than that: the functional tests run on Gradle 8.11.1.
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

// The agent docs processor: a copy of the StarLight app's :docs-processor, which runs inside the consumer's KSP, never
// in Gradle. It is packed into its own jar and shipped inside the plugin's, as dev/mooner/slplugin/docs/processor.jar,
// because Gradle instruments the plugin's own jar for the configuration cache. See AgentDocsIntegration.
val docsProcessor: SourceSet = sourceSets.create("docsProcessor")

dependencies {
    compileOnly(libs.android.gradle.api)
    implementation(libs.asm)

    // KSP provides its API and Kotlin's stdlib; AgentDocsIntegration adds the JSON library to the ksp classpath.
    "docsProcessorCompileOnly"(libs.ksp.api)
    "docsProcessorCompileOnly"(libs.kotlinx.serialization.json)
    testImplementation(libs.ksp.api)
    testImplementation(libs.kotlinx.serialization.json)
}

// The processor's tests, in the test source set, see its internals.
kotlin.target.compilations.named("test") {
    associateWith(kotlin.target.compilations.getByName("docsProcessor"))
}

val docsProcessorJar = tasks.register<Jar>("docsProcessorJar") {
    from(docsProcessor.output)
    archiveFileName = "processor.jar"
    destinationDirectory = layout.buildDirectory.dir("docsProcessor")
}

tasks.processResources {
    from(docsProcessorJar) { into("dev/mooner/slplugin/docs") }
    // The JSON library added to the consumer's ksp classpath: the one the processor is compiled against.
    val serialization = libs.kotlinx.serialization.json.get().toString()
    inputs.property("kotlinxSerializationJson", serialization)
    filesMatching("dev/mooner/slplugin/docs/processor.properties") {
        expand("kotlinxSerializationJson" to serialization)
    }
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
                    systemProperty("slplugin.agp.host", libs.versions.agp.host.get())
                    systemProperty("slplugin.host.compileSdk", libs.versions.compileSdk.get())
                    systemProperty("slplugin.host.buildTools", libs.versions.buildTools.get())
                    systemProperty("slplugin.kotlin", libs.versions.kotlin.get())
                    systemProperty("slplugin.ksp", libs.versions.ksp.get())
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
