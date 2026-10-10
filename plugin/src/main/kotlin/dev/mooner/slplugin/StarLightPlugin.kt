package dev.mooner.slplugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class StarLightPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("starlight", StarlightExtension::class.java)

        project.pluginManager.withPlugin(ANDROID_APPLICATION) {
            AndroidIntegration.configure(project, extension)
        }
        project.afterEvaluate {
            if (!project.pluginManager.hasPlugin(ANDROID_APPLICATION))
                throw GradleException(
                    "io.github.mooner1022.slplugin requires the Android application plugin. " +
                        "Apply '$ANDROID_APPLICATION' to ${project.path} as well."
                )
        }
    }

    private companion object {
        const val ANDROID_APPLICATION = "com.android.application"
    }
}
