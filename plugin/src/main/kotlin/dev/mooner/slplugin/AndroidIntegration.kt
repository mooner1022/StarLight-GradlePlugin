package dev.mooner.slplugin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import dev.mooner.slplugin.tasks.AdbTask
import dev.mooner.slplugin.tasks.DeploySlp
import dev.mooner.slplugin.tasks.GenerateStarlightManifest
import dev.mooner.slplugin.tasks.PackageSlp
import dev.mooner.slplugin.tasks.RestartStarlight
import dev.mooner.slplugin.tasks.StarlightLogcat
import dev.mooner.slplugin.tasks.VerifyStarlightSdk
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

internal object AndroidIntegration {

    private const val GROUP = "starlight"

    /** Kotlin runtime modules the StarLight host ships through PluginCore. */
    private val HOST_KOTLIN_MODULES = listOf(
        "org.jetbrains.kotlin" to "kotlin-stdlib",
        "org.jetbrains.kotlin" to "kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin" to "kotlin-stdlib-jdk8",
        "org.jetbrains.kotlin" to "kotlin-stdlib-common",
        "org.jetbrains.kotlin" to "kotlin-reflect",
        "org.jetbrains.kotlinx" to "kotlinx-coroutines-core",
        "org.jetbrains.kotlinx" to "kotlinx-coroutines-core-jvm",
        "org.jetbrains.kotlinx" to "kotlinx-coroutines-android",
        "org.jetbrains.kotlinx" to "kotlinx-serialization-core",
        "org.jetbrains.kotlinx" to "kotlinx-serialization-core-jvm",
        "org.jetbrains.kotlinx" to "kotlinx-serialization-json",
        "org.jetbrains.kotlinx" to "kotlinx-serialization-json-jvm",
        "org.jetbrains" to "annotations",
    )

    fun configure(project: Project, extension: StarlightExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val sdk = extension.sdk

        project.dependencies.add(
            "compileOnly",
            project.files(project.provider { listOfNotNull(sdk.pluginCore.orNull, sdk.configDsl.orNull) }),
        )
        val verifySdk = project.tasks.register("verifyStarlightSdk", VerifyStarlightSdk::class.java) { task ->
            task.group = GROUP
            task.description = "Checks that the StarLight SDK files configured in starlight { sdk { ... } } exist."
            task.pluginCore.set(sdk.pluginCore)
            task.configDsl.set(sdk.configDsl)
        }
        project.tasks.matching { it.name == "preBuild" }.configureEach { it.dependsOn(verifySdk) }

        val adb = components.sdkComponents.adb
        val providers = project.providers
        // Command line and environment beat the build script, so a shared build file needs no personal device address.
        val device = providers.gradleProperty("starlight.device")
            .orElse(providers.environmentVariable("STARLIGHT_DEVICE"))
            .orElse(extension.deploy.device)
        val restartApp = providers.gradleProperty("starlight.restartApp").map { it.toBoolean() }
            .orElse(extension.deploy.restartApp)

        fun AdbTask.configureAdb() {
            group = GROUP
            adbExecutable.set(adb)
            this.device.set(device)
            appPackage.set(extension.deploy.appPackage)
        }

        components.onVariants(components.selector().all(), Action { variant ->
            configureVariant(project, extension, variant) { task ->
                task.configureAdb()
                task.remoteDirectory.set(extension.deploy.remoteDirectory)
                task.restartApp.set(restartApp)
            }
        })

        project.tasks.register("assembleSlp") { task ->
            task.group = GROUP
            task.description = "Assembles every variant as a StarLight plugin (.slp)."
            task.dependsOn(project.tasks.withType(PackageSlp::class.java))
        }
        project.tasks.register("restartStarlight", RestartStarlight::class.java) { task ->
            task.configureAdb()
            task.description = "Force-stops and relaunches StarLight so it loads new plugins."
        }
        project.tasks.register("starlightLogcat", StarlightLogcat::class.java) { task ->
            task.configureAdb()
            task.description = "Streams StarLight's logcat output."
        }
    }

    private fun configureVariant(
        project: Project,
        extension: StarlightExtension,
        variant: ApplicationVariant,
        configureDeploy: (DeploySlp) -> Unit,
    ) {
        val taskSuffix = variant.name.replaceFirstChar(Char::uppercaseChar)

        if (extension.sdk.excludeHostKotlinLibraries.get()) {
            HOST_KOTLIN_MODULES.forEach { (group, module) ->
                variant.runtimeConfiguration.exclude(mapOf("group" to group, "module" to module))
            }
        }

        val generatedManifest: Provider<RegularFile>? =
            if (extension.generatesManifest()) {
                val manifest = extension.manifest
                val versionName: Provider<String> = variant.outputs.first().versionName.map { it }
                val generate = project.tasks.register("generate${taskSuffix}StarlightManifest", GenerateStarlightManifest::class.java) { task ->
                    task.group = GROUP
                    task.description = "Generates starlight.json for the ${variant.name} variant."
                    task.pluginId.set(manifest.id)
                    task.pluginName.set(manifest.name)
                    task.mainClass.set(manifest.mainClass)
                    task.customClassLoader.set(manifest.customClassLoader)
                    task.pluginVersion.set(manifest.version.orElse(versionName))
                    task.apiVersion.set(manifest.apiVersion)
                    task.authors.set(manifest.authors)
                    task.pluginDescription.set(manifest.description)
                    task.category.set(manifest.category)
                    task.dependencies.set(manifest.dependencies)
                    task.usesNativeLibrary.set(manifest.usesNativeLibrary)
                    task.assetRevision.set(manifest.assetRevision)
                    task.assetSources.from(assetSourceDirectories(project, variant))
                }
                variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateStarlightManifest::outputDirectory)
                generate.flatMap { it.outputDirectory.file(GenerateStarlightManifest.MANIFEST_FILE) }
            } else null

        val packageSlp = project.tasks.register("assemble${taskSuffix}Slp", PackageSlp::class.java) { task ->
            task.group = GROUP
            task.description = "Assembles the ${variant.name} variant as a StarLight plugin (.slp) and validates it."
            task.apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            task.builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            task.variantName.set(variant.name)
            task.outputDirectory.set(project.layout.buildDirectory.dir("outputs/starlight/${variant.name}"))
            generatedManifest?.let { task.generatedManifest.set(it) }
        }

        project.tasks.register("deploy${taskSuffix}Slp", DeploySlp::class.java) { task ->
            task.description = "Pushes the ${variant.name} .slp to StarLight's plugin folder on a device."
            task.slpDirectory.set(packageSlp.flatMap { it.outputDirectory })
            configureDeploy(task)
        }
    }

    /** Conventional asset folders of the variant's source sets, from lowest to highest priority. */
    private fun assetSourceDirectories(project: Project, variant: ApplicationVariant): List<Directory> {
        val flavors = variant.productFlavors.map { it.second }
        val sourceSets = buildList {
            add("main")
            addAll(flavors)
            if (flavors.size > 1)
                add(variant.flavorName.orEmpty())
            variant.buildType?.let { add(it) }
            if (flavors.isNotEmpty())
                add(variant.name)
        }
        return sourceSets.filter(String::isNotEmpty).distinct()
            .map { project.layout.projectDirectory.dir("src/$it/assets") }
    }
}
