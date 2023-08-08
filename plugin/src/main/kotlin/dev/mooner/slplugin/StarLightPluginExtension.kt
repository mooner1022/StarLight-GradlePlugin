package dev.mooner.slplugin

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

const val DEFAULT_OUTPUT_FILE = "plugin.slp"

abstract class StarLightPluginExtension @Inject constructor(project: Project) {

    private val objects = project.objects

    val outputDirectory: RegularFileProperty = objects.fileProperty().convention(
        project.layout.buildDirectory.file(DEFAULT_OUTPUT_FILE)
    )
}
