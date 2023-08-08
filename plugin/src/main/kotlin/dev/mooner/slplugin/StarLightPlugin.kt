package dev.mooner.slplugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

class StarLightPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("starlightPlugin", StarLightPluginExtension::class.java)
        (target.extensions.getByName("starlightPlugin") as ExtensionAware)
            .extensions.create("device", DeviceExtension::class.java)
        val task = target.tasks.register("buildPlugin", PluginBuildTask::class.java)

        target.afterEvaluate {
            task.configure { target ->
                target.outputDirectory.set(extension.outputDirectory)
            }
        }
    }
}
