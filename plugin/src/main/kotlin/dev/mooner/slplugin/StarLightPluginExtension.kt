package dev.mooner.slplugin

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

abstract class StarLightPluginExtension @Inject constructor(project: Project) {

    private val objects = project.objects

    val outputDirectory: RegularFileProperty = objects.fileProperty()
}
