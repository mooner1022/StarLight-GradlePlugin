package dev.mooner.slplugin.tasks

import dev.mooner.slplugin.api.ApiUsageReport
import dev.mooner.slplugin.manifest.ManifestJson
import dev.mooner.slplugin.manifest.ManifestValidator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/** Generates `assets/starlight.json` from the `starlight { manifest { ... } }` block. */
@CacheableTask
abstract class GenerateStarlightManifest : DefaultTask() {

    @get:Input @get:Optional
    abstract val pluginId: Property<String>

    @get:Input @get:Optional
    abstract val pluginName: Property<String>

    @get:Input @get:Optional
    abstract val mainClass: Property<String>

    @get:Input @get:Optional
    abstract val customClassLoader: Property<String>

    @get:Input @get:Optional
    abstract val pluginVersion: Property<String>

    @get:Input @get:Optional
    abstract val apiVersion: Property<String>

    @get:Input
    abstract val authors: ListProperty<String>

    @get:Input @get:Optional
    abstract val pluginDescription: Property<String>

    @get:Input @get:Optional
    abstract val category: Property<String>

    @get:Input
    abstract val dependencies: ListProperty<String>

    @get:Input
    abstract val usesNativeLibrary: Property<Boolean>

    /** When absent, derived from the content of [assetSources] so StarLight re-extracts assets whenever they change. */
    @get:Input @get:Optional
    abstract val assetRevision: Property<Int>

    /** The variant's asset source folders: hashed for [assetRevision] and checked for a conflicting hand-written manifest. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val assetSources: ConfigurableFileCollection

    /**
     * What [ScanStarlightApiUsage] found the code needs from PluginCore: `api_version` when it isn't set, and
     * `target_api_version`.
     */
    @get:InputFile @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiUsageReport: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val usage = apiUsageReport.orNull?.asFile?.takeIf { it.isFile }?.let(ApiUsageReport::readFrom) ?: ApiUsageReport.UNKNOWN
        val assetFiles = assetSources.files
            .filter(File::isDirectory)
            .flatMap { root -> root.walkTopDown().filter(File::isFile).map { it.relativeTo(root).invariantSeparatorsPath to it } }
            .sortedBy { it.first }
        assetFiles.firstOrNull { it.first == MANIFEST_FILE }?.let { (_, file) ->
            throw GradleException(
                "Found a hand-written $file while starlight { manifest { ... } } generates the manifest. " +
                    "Delete the file or remove the manifest block."
            )
        }

        // Unset, api_version is the lowest PluginCore the code runs on: what it uses, or the oldest release recorded.
        val resolvedApiVersion = apiVersion.orNull ?: (usage.required ?: usage.baseline)?.toString()
        val required = linkedMapOf(
            "id" to pluginId.isPresent,
            "name" to pluginName.isPresent,
            "mainClass" to mainClass.isPresent,
            "version" to pluginVersion.isPresent,
            "apiVersion" to (resolvedApiVersion != null),
            "description" to pluginDescription.isPresent,
        )
        val missing = required.filterValues { !it }.keys
        if (missing.isNotEmpty())
            throw GradleException(
                "starlight { manifest { ... } } is missing: ${missing.joinToString()}." +
                    (if ("version" in missing) " (version defaults to android.defaultConfig.versionName)" else "") +
                    (if ("apiVersion" in missing) " (apiVersion is worked out from the code only with PluginCore 1.1.0 or " +
                        "later, which records when its API was added)" else "")
            )

        val manifest = linkedMapOf<String, Any?>(
            "id" to pluginId.get(),
            "name" to pluginName.get(),
            "main_class" to mainClass.get(),
        )
        customClassLoader.orNull?.let { manifest["custom_classloader"] = it }
        manifest["version"] = pluginVersion.get()
        manifest["assetRevision"] = assetRevision.orNull ?: computeAssetRevision(assetFiles)
        manifest["api_version"] = resolvedApiVersion
        usage.pluginCore?.let { manifest["target_api_version"] = it.toString() }
        manifest["dependency"] = dependencies.get()
        manifest["uses_native_lib"] = usesNativeLibrary.get()
        manifest["authors"] = authors.get()
        manifest["description"] = pluginDescription.get()
        category.orNull?.let { manifest["category"] = it }

        ManifestReporting.reportOrThrow(
            logger,
            "starlight { manifest { ... } }",
            ManifestValidator.validate(manifest, usage.pluginCore ?: ManifestValidator.KNOWN_HOST_API),
        )

        val output = outputDirectory.get().asFile
        output.mkdirs()
        File(output, MANIFEST_FILE).writeText(ManifestJson.write(manifest), Charsets.UTF_8)
    }

    private fun computeAssetRevision(assetFiles: List<Pair<String, File>>): Int {
        if (assetFiles.isEmpty())
            return 0
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        for ((path, file) in assetFiles) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0)
                        break
                    digest.update(buffer, 0, read)
                }
            }
        }
        val hash = digest.digest()
        val revision = ((hash[0].toInt() and 0x7F) shl 24) or
            ((hash[1].toInt() and 0xFF) shl 16) or
            ((hash[2].toInt() and 0xFF) shl 8) or
            (hash[3].toInt() and 0xFF)
        return if (revision == 0) 1 else revision
    }

    internal companion object {
        const val MANIFEST_FILE = "starlight.json"
    }
}
