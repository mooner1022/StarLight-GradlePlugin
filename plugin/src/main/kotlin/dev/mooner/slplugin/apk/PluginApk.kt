package dev.mooner.slplugin.apk

import dev.mooner.slplugin.manifest.ManifestIssue
import dev.mooner.slplugin.manifest.ManifestValidator
import dev.mooner.slplugin.manifest.Severity
import java.io.File
import java.util.zip.ZipFile

/** The parts of a plugin APK that StarLight looks at when it loads a `.slp`. */
internal class PluginApk private constructor(
    val file: File,
    /** Raw `starlight.json` as the host would pick it: `assets/` first, then the deprecated `res/raw/`. */
    val manifestText: String?,
    val manifestEntry: String?,
    val hasAssetsManifest: Boolean,
    val hasLegacyManifest: Boolean,
    val nativeLibraries: List<String>,
    val dexFiles: List<DexFile>,
) {
    companion object {
        const val ASSETS_MANIFEST = "assets/starlight.json"
        const val LEGACY_MANIFEST = "res/raw/starlight.json"

        private val DEX_ENTRY = Regex("""classes\d*\.dex""")

        fun read(file: File): PluginApk = ZipFile(file).use { zip ->
            val assets = zip.getEntry(ASSETS_MANIFEST)
            val legacy = zip.getEntry(LEGACY_MANIFEST)
            val manifestEntry = assets ?: legacy
            val entries = zip.entries().toList()
            PluginApk(
                file = file,
                manifestText = manifestEntry?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } },
                manifestEntry = manifestEntry?.name,
                hasAssetsManifest = assets != null,
                hasLegacyManifest = legacy != null,
                nativeLibraries = entries.map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") },
                dexFiles = entries.filter { DEX_ENTRY.matches(it.name) }
                    .map { entry -> DexFile(zip.getInputStream(entry).use { it.readBytes() }) },
            )
        }
    }
}

internal class PluginApkReport(
    val issues: List<ManifestIssue>,
    val manifest: Map<String, Any?>?,
)

/** Validates a built plugin APK the way the StarLight host will load it. */
internal object PluginApkChecker {

    private const val STARLIGHT_PLUGIN = "Ldev/mooner/starlight/plugincore/plugin/StarlightPlugin;"
    private const val OBJECT = "Ljava/lang/Object;"

    /** Libraries the host app already ships; plugin copies are never used because the host's class loader wins. */
    private val HOST_PROVIDED_PACKAGES = listOf(
        "Ldev/mooner/starlight/plugincore/" to "PluginCore",
        "Ldev/mooner/configdsl/" to "ConfigDSL",
        "Lkotlin/" to "kotlin-stdlib/kotlin-reflect",
        "Lkotlinx/coroutines/" to "kotlinx-coroutines",
        "Lkotlinx/serialization/" to "kotlinx-serialization",
        "Landroidx/core/" to "androidx.core",
        "Landroidx/appcompat/" to "androidx.appcompat",
        "Landroidx/recyclerview/" to "androidx.recyclerview",
        "Landroidx/navigation/" to "androidx.navigation",
        "Landroidx/lifecycle/" to "androidx.lifecycle",
        "Landroidx/constraintlayout/" to "androidx.constraintlayout",
        "Lcom/google/android/material/" to "material",
    )

    fun check(apk: PluginApk, expectedManifest: String?): PluginApkReport {
        val issues = mutableListOf<ManifestIssue>()
        val text = apk.manifestText
            ?: return PluginApkReport(
                listOf(error("${apk.file.name} has no ${PluginApk.ASSETS_MANIFEST}. Add src/main/assets/starlight.json or configure starlight { manifest { ... } }.")),
                null,
            )
        if (apk.hasAssetsManifest && apk.hasLegacyManifest)
            issues += warning("${PluginApk.LEGACY_MANIFEST} is ignored because ${PluginApk.ASSETS_MANIFEST} exists. Delete src/main/res/raw/starlight.json.")
        else if (!apk.hasAssetsManifest)
            issues += warning("${PluginApk.LEGACY_MANIFEST} is deprecated by StarLight. Move it to src/main/assets/starlight.json.")
        if (expectedManifest != null && text != expectedManifest)
            issues += error(
                "The starlight.json packaged in ${apk.file.name} is not the one generated from starlight { manifest { ... } }. " +
                    "Remove hand-written starlight.json files from your asset and res/raw folders."
            )

        val validation = ManifestValidator.validateText(text)
        issues += validation.issues
        val manifest = validation.manifest ?: return PluginApkReport(issues, null)

        val classes = apk.dexFiles.flatMap { dex -> dex.classes.map { it.descriptor to (dex to it) } }.toMap()
        (manifest["main_class"] as? String)?.let { checkMainClass(it, classes, issues) }
        (manifest["custom_classloader"] as? String)?.let { name ->
            if (DexFile.descriptorOf(name) !in classes)
                issues += error("custom_classloader $name is not in ${apk.file.name}.")
        }

        val usesNativeLibrary = manifest["uses_native_lib"] == true
        if (apk.nativeLibraries.isNotEmpty() && !usesNativeLibrary)
            issues += warning(
                "${apk.file.name} contains native libraries (${apk.nativeLibraries.take(3).joinToString()}${if (apk.nativeLibraries.size > 3) ", ..." else ""}) " +
                    "but uses_native_lib is false, so StarLight will not extract them."
            )
        else if (apk.nativeLibraries.isEmpty() && usesNativeLibrary)
            issues += warning("uses_native_lib is true but ${apk.file.name} contains no native libraries.")

        val bundled = HOST_PROVIDED_PACKAGES.mapNotNull { (prefix, library) ->
            val count = classes.keys.count { it.startsWith(prefix) }
            if (count > 0) "$library ($count classes)" else null
        }
        if (bundled.isNotEmpty())
            issues += warning(
                "${apk.file.name} bundles libraries the StarLight host already provides: ${bundled.joinToString()}. " +
                    "The host's copies are always used, so these only add size. Declare them as compileOnly, " +
                    "or set starlight { sdk { excludeHostKotlinLibraries = true } } for the Kotlin runtime."
            )
        return PluginApkReport(issues, manifest)
    }

    private fun checkMainClass(
        className: String,
        classes: Map<String, Pair<DexFile, DexFile.ClassDef>>,
        issues: MutableList<ManifestIssue>,
    ) {
        val (dex, mainClass) = classes[DexFile.descriptorOf(className)]
            ?: run {
                issues += error(
                    "main_class $className is not in the APK. Check the class name. If minification is enabled, " +
                        "keep it with: -keep class $className { public <init>(); }"
                )
                return
            }
        if (!mainClass.isPublic || mainClass.isAbstract)
            issues += error("main_class $className must be a public, non-abstract class.")
        else if (!dex.hasPublicNoArgConstructor(mainClass))
            issues += error("main_class $className needs a public constructor without parameters; StarLight creates it by reflection.")

        var current: DexFile.ClassDef? = mainClass
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.descriptor)) {
            val parent = current.superDescriptor
            when {
                parent == STARLIGHT_PLUGIN -> return
                parent == null || parent == OBJECT -> {
                    issues += error("main_class $className does not extend dev.mooner.starlight.plugincore.plugin.StarlightPlugin.")
                    return
                }
                else -> current = classes[parent]?.second
                    ?: run {
                        issues += warning("Could not verify that main_class $className extends StarlightPlugin: its superclass ${toClassName(parent)} is not in the APK.")
                        return
                    }
            }
        }
    }

    private fun toClassName(descriptor: String) =
        descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun error(message: String) = ManifestIssue(Severity.ERROR, message)

    private fun warning(message: String) = ManifestIssue(Severity.WARNING, message)
}
