package dev.mooner.slplugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileNotFoundException

abstract class PluginBuildTask : DefaultTask() {

    init {
        description = "Build the plugin as .slp file"

        group = BasePlugin.BUILD_GROUP

        val assembleTask = project.tasks.getByName("assembleDebug")
        dependsOn(assembleTask)
    }

    @get:Optional
    @get:OutputFile
    abstract val outputDirectory: RegularFileProperty

    @TaskAction
    fun checkDirectory() {
        val dir = (outputDirectory.orNull?.asFile ?: getOutputDir("starlight")).also { dir ->
            if (!dir.exists() || !dir.isDirectory)
                dir.mkdirs()
        }
        logger.lifecycle("Checking output directory: $dir")
    }

    @TaskAction
    fun checkBuildOutput() {
        val dir = getOutputDir("apk").resolve("debug")
        logger.lifecycle("Checking build result directory: $dir")
        if (!dir.exists()) {
            val msg = "Unable to locate apk build output directory: $dir"
            throw FileNotFoundException(msg)
        }
    }

    @TaskAction
    fun renameFile() {
        val sourceDir = getOutputDir("apk").resolve("debug")

        val outputDir = (outputDirectory.orNull?.asFile ?: getOutputDir("starlight"))
        val outputFile = File(outputDir, "plugin.slp")
        if (outputFile.exists())
            outputFile.delete()

        val sourceFile = sourceDir.listFiles()
            ?.find { it.name.endsWith("-debug.apk") }
            .let { file ->
                if (file == null || !file.exists())
                    throw FileNotFoundException("Unable to locate output apk from directory: ${file?.parent}")
                file
            }

        sourceFile.copyTo(outputFile)
        sourceFile.delete()
    }

    private fun getOutputDir(dir: String): File =
        project.buildDir
            .resolve("outputs")
            .resolve(dir)
}
