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
import java.net.HttpURLConnection
import java.net.URL

abstract class PluginPushTask : DefaultTask() {
    init {
        description = "Build and push plugin to target device"

        group = BasePlugin.BUILD_GROUP

        val buildTask = project.tasks.getByName("buildPlugin")
        dependsOn(buildTask)
    }

    @get:Optional
    @get:OutputFile
    abstract val outputDirectory: RegularFileProperty

    @get:Input
    abstract val host: Property<String>

    @get:Input
    abstract val port: Property<Int>

    @get:Input
    abstract val password: Property<String>

    @TaskAction
    fun checkConnection() {
        val port = port.orElse(9000).get()
        val host = host.get()
        val url = URL("http://$host:$port/bridge/ping")

        val password = password.get()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authentication", "Basic $password")
        }

        try {
            connection.connect()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to test connection to ${url.host}: ${e.message}")
        }

        if (connection.responseCode != 200)
            throw BadResponseException(connection.responseCode, connection.responseMessage)

        logger.lifecycle("Received response from remote device: ${connection.responseMessage}")
    }

    private fun getOutputDir(dir: String): File =
        project.buildDir
            .resolve("outputs")
            .resolve(dir)
}