package dev.mooner.slplugin.tasks

import dev.mooner.slplugin.api.ApiUsageReport
import dev.mooner.slplugin.api.ApiUsageScanner
import dev.mooner.slplugin.api.PluginCoreApi
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Finds which PluginCore API the variant's code uses, and the version that added it, so that `api_version` can be the
 * lowest PluginCore the code runs on. Reads the plugin module's compiled classes and the `plugincore-api.json` of the
 * PluginCore it is built against; writes an [ApiUsageReport] for the manifest and the .slp check.
 */
@CacheableTask
abstract class ScanStarlightApiUsage : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ListProperty<Directory>

    @get:InputFile @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginCore: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val output = report.get().asFile
        val core = pluginCore.orNull?.asFile?.takeIf { it.isFile }?.let(PluginCoreApi::read)
        if (core == null) {
            logger.info("[StarLight] The PluginCore this plugin is built against has no ${PluginCoreApi.API_FILE}; " +
                "api_version isn't worked out from the code.")
            ApiUsageReport.UNKNOWN.writeTo(output)
            return
        }
        // No sequence { } builder: compiled by this Kotlin, it needs stdlib classes Gradle 8.11's Kotlin 2.0 lacks.
        val fromDirectories = classDirectories.get().asSequence()
            .flatMap { directory -> directory.asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".class") } }
            .map(File::readBytes)
        val fromJars = classJars.get().asSequence().flatMap { jar -> classesIn(jar.asFile) }
        ApiUsageReport(core.version, core.baseline, ApiUsageScanner(core).scan(fromDirectories + fromJars)).writeTo(output)
    }

    private fun classesIn(jar: File): List<ByteArray> =
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                .toList()
        }
}
