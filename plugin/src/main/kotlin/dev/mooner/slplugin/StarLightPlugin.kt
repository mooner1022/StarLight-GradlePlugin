package dev.mooner.slplugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionAware

class StarLightPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("starlightPlugin", StarLightPluginExtension::class.java)
        val deviceExtension = (target.extensions.getByName("starlightPlugin") as ExtensionAware)
            .extensions.create("device", DeviceExtension::class.java)

        val buildTask = target.tasks.register("buildPlugin", PluginBuildTask::class.java)
        val pushTask  = target.tasks.register("buildAndPushPlugin", PluginPushTask::class.java)

        target.afterEvaluate {
            buildTask.configure { target ->
                target.outputDirectory.set(extension.outputDirectory)
            }
            pushTask.configure { target ->
                target.outputDirectory.set(extension.outputDirectory)
                target.host.set(deviceExtension.host)
                target.port.set(deviceExtension.port)
                target.password.set(deviceExtension.password)
            }
        }
    }
}
