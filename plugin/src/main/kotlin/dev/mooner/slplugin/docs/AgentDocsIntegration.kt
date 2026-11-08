package dev.mooner.slplugin.docs

import dev.mooner.slplugin.manifest.ManifestJson
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.work.DisableCachingByDefault
import java.security.MessageDigest
import java.util.Properties

/**
 * The docs StarLight AI reads about a plugin's script API (StarLight's docs/agent-docs.md §9). When the plugin's build
 * applies KSP, this runs the agent docs processor bundled with this plugin, which writes the KDoc and Kotlin signatures
 * of the plugin's public classes to `starlight/agent/<plugin id>.api.json` in the APK.
 *
 * KSP is left to the plugin's build, because each KSP release supports a range of Android Gradle Plugin versions
 * (2.3.13 refuses AGP before 8.12) and this plugin supports AGP 8.0 and later.
 */
internal object AgentDocsIntegration {

    const val KSP_PLUGIN = "com.google.devtools.ksp"

    /** The processor's option for the module the docs are of: the plugin's ID. */
    const val OPTION_MODULE = "starlight.docs.module"

    private const val PROCESSOR_JAR = "processor.jar"

    /** A class Kotlin's compile tasks extend, with AGP's built-in Kotlin too. */
    private const val KOTLIN_COMPILE = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"
    private const val KSP_TASK = "com.google.devtools.ksp.gradle.KspAATask"

    fun configure(project: Project, pluginId: Provider<String>) {
        enableParameterNames(project)
        project.pluginManager.withPlugin(KSP_PLUGIN) {
            val extract = project.tasks.register("extractStarlightDocsProcessor", ExtractDocsProcessor::class.java) { task ->
                task.description = "Extracts the StarLight agent docs processor this plugin runs with KSP."
                task.checksum.set(processorChecksum)
                task.processorJar.set(project.layout.buildDirectory.file("intermediates/starlight/docs-processor/$PROCESSOR_JAR"))
            }
            // Every variant's processor classpath extends `ksp`, which KSP creates with the Kotlin source sets.
            project.configurations.matching { it.name == "ksp" }.configureEach { ksp ->
                project.dependencies.add(ksp.name, project.files(extract.flatMap { it.processorJar }))
                project.dependencies.add(ksp.name, serializationJson)
            }
            setModule(project, pluginId)
            runKspOneAtATime(project)
        }
    }

    /**
     * Compiles with parameter names, which StarLight AI shows for members the docs don't cover: Java code without
     * KDoc, and plugins built without KSP.
     */
    private fun enableParameterNames(project: Project) {
        project.tasks.withType(JavaCompile::class.java).configureEach { task ->
            if ("-parameters" !in task.options.compilerArgs)
                task.options.compilerArgs.add("-parameters")
        }
        project.tasks.configureEach { task ->
            if (extends(task, KOTLIN_COMPILE))
                runCatching {
                    // KotlinJvmCompile.compilerOptions.javaParameters, through reflection: Kotlin's Gradle plugin may
                    // be loaded by a class loader this plugin doesn't see.
                    val options = task.javaClass.getMethod("getCompilerOptions").invoke(task)
                    @Suppress("UNCHECKED_CAST")
                    (options.javaClass.getMethod("getJavaParameters").invoke(options) as Property<Boolean>).set(true)
                }
        }
    }

    /** `ksp { arg("starlight.docs.module", pluginId) }`, lazily, through reflection for the same reason. */
    private fun setModule(project: Project, pluginId: Provider<String>) {
        val ksp = project.extensions.getByName("ksp")
        val arg = ksp.javaClass.methods.firstOrNull { method ->
            method.name == "arg" && method.parameterTypes.contentEquals(arrayOf(String::class.java, Provider::class.java))
        } ?: throw IllegalStateException("StarLight agent docs need KSP 2.3 or later, whose ksp { } takes arg(String, Provider).")
        arg.invoke(ksp, OPTION_MODULE, pluginId)
    }

    /**
     * Runs KSP tasks one at a time, as the StarLight app's build does. KSP analyses in the Gradle daemon, and with
     * several KSP tasks at once (debug and release) one can run without the Android classes. The processor then fails
     * the build on unresolved types rather than writing docs whose keys don't match.
     */
    private fun runKspOneAtATime(project: Project) {
        val service = project.gradle.sharedServices.registerIfAbsent("starlightKspOneAtATime", KspOneAtATime::class.java) {
            it.maxParallelUsages.set(1)
        }
        project.tasks.configureEach { task ->
            if (extends(task, KSP_TASK))
                task.usesService(service)
        }
    }

    private fun extends(task: Task, className: String): Boolean =
        generateSequence<Class<*>>(task.javaClass) { it.superclass }.any { it.name == className }

    /** The plugin's ID: from `starlight { manifest { } }`, or the hand-written `src/main/assets/starlight.json`. */
    fun pluginId(project: Project, generatedId: Provider<String>): Provider<String> {
        val handWritten = project.providers
            .fileContents(project.layout.projectDirectory.file("src/main/assets/starlight.json"))
            .asText
            .map { text -> (runCatching { ManifestJson.parseLenient(text) }.getOrNull() as? Map<*, *>)?.get("id") as? String ?: "" }
        // Without an ID the processor stops with "starlight.docs.module isn't set"; packaging names the real problem.
        return generatedId.orElse(handWritten).orElse("")
    }

    private val bundledProcessor: ByteArray by lazy {
        AgentDocsIntegration::class.java.getResourceAsStream(PROCESSOR_JAR)?.use { it.readBytes() }
            ?: throw IllegalStateException("dev/mooner/slplugin/docs/$PROCESSOR_JAR is missing from the StarLight Gradle plugin.")
    }

    private val processorChecksum: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bundledProcessor).joinToString("") { "%02x".format(it) }
    }

    /** `org.jetbrains.kotlinx:kotlinx-serialization-json:<version>`, which the processor writes its JSON with. */
    private val serializationJson: String by lazy {
        Properties().apply {
            AgentDocsIntegration::class.java.getResourceAsStream("processor.properties")!!.use(::load)
        }.getProperty("kotlinxSerializationJson")
    }

    /** Writes the processor jar bundled with this plugin, for the ksp classpath. */
    @DisableCachingByDefault(because = "Copies a file out of the plugin's jar")
    abstract class ExtractDocsProcessor : DefaultTask() {

        /** Of the bundled jar, so a new one is extracted when the plugin changes. */
        @get:Input
        abstract val checksum: Property<String>

        @get:OutputFile
        abstract val processorJar: RegularFileProperty

        @TaskAction
        fun extract() {
            processorJar.get().asFile.apply { parentFile.mkdirs() }.writeBytes(bundledProcessor)
        }
    }

    abstract class KspOneAtATime : BuildService<BuildServiceParameters.None>
}
