package dev.mooner.slplugin.manifest

internal enum class Severity { ERROR, WARNING }

internal data class ManifestIssue(val severity: Severity, val message: String)

internal class ManifestValidation(
    val issues: List<ManifestIssue>,
    /** The parsed manifest, or null when it could not be read as a JSON object. */
    val manifest: Map<String, Any?>?,
) {
    val errors: List<ManifestIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ManifestIssue> get() = issues.filter { it.severity == Severity.WARNING }
}

/**
 * Checks a `starlight.json` against the rules the StarLight host applies when it loads a plugin
 * (`PluginInstaller`, `PluginLoader`, `PluginInfo`, `PluginDependency`, `ApiCompatibility`, `Version` in PluginCore),
 * plus mistakes the host silently ignores, such as misspelled keys.
 */
internal object ManifestValidator {

    /** Newest PluginCore known to this plugin (`Info.PLUGINCORE_VERSION`). Hosts refuse plugins with a newer api_version. */
    val KNOWN_HOST_API = StarlightVersion(1, 0, 0)

    /** Oldest api_version the host still loads (`Info.MIN_PLUGIN_API_VERSION`). */
    val MIN_HOST_API = StarlightVersion(0, 3, 0)

    val RESERVED_IDS = setOf("global", "starlight", "system", "core")

    /** `PluginCategory` ids. The host matches them ignoring case and surrounding whitespace, and falls back to `other`. */
    val CATEGORIES = listOf("messenger", "engine", "library", "utility", "devtools", "other")

    private val KNOWN_KEYS = setOf(
        "id", "name", "main_class", "custom_classloader", "version", "assetRevision",
        "api_version", "dependency", "uses_native_lib", "authors", "description", "category",
    )

    private val ID = Regex("[A-Za-z0-9._-]+")

    private val CLASS_NAME = Regex("""[\p{L}_$][\p{L}\p{N}_$]*(\.[\p{L}_$][\p{L}\p{N}_$]*)*""")

    /** Validates the raw file content, including the formatting quirks of the host's reader. */
    fun validateText(text: String): ManifestValidation {
        val issues = mutableListOf<ManifestIssue>()
        if (text.startsWith('\uFEFF'))
            issues += error("starlight.json starts with a byte order mark (BOM), which StarLight rejects. Save it as UTF-8 without BOM.")

        val content = text.removePrefix("\uFEFF")
        val parsed = try {
            ManifestJson.parse(content)
        } catch (strict: Exception) {
            try {
                ManifestJson.parseLenient(content).also {
                    issues += warning("starlight.json is not valid JSON (${strict.message?.lineSequence()?.first()}). StarLight parses it leniently, but keep it valid JSON.")
                }
            } catch (e: Exception) {
                issues += error("starlight.json is not valid JSON: ${strict.message}")
                return ManifestValidation(issues, null)
            }
        }
        if (parsed !is Map<*, *>) {
            issues += error("starlight.json must contain a JSON object.")
            return ManifestValidation(issues, null)
        }
        @Suppress("UNCHECKED_CAST")
        val manifest = parsed as Map<String, Any?>
        if (isLoadedByLegacyHost(manifest["api_version"])) {
            val hostView = joinLinesLikeHost(content)
            if (!hostView.startsWith("{"))
                issues += error("starlight.json must start with '{'. $LEGACY_HOST_NOTE leading whitespace or other characters.")
            if (!hostView.endsWith("}"))
                issues += error("starlight.json must end with '}'. $LEGACY_HOST_NOTE trailing blank lines, whitespace or other characters.")
        }
        issues += validate(manifest)
        return ManifestValidation(issues, manifest)
    }

    private const val LEGACY_HOST_NOTE = "StarLight with PluginCore 0.3.x, which also loads plugins with api_version 0.3.x, rejects"

    /**
     * Hosts with PluginCore 0.3.x read the manifest line by line and require it to start with '{' and end with '}'.
     * They only load plugins whose api_version is 0.3.x; newer hosts parse the file as it is.
     */
    private fun isLoadedByLegacyHost(apiVersion: Any?): Boolean {
        val version = (apiVersion as? String)?.let(StarlightVersion::parseOrNull) ?: return true
        return version.major == 0 && version.minor == 3
    }

    fun validate(manifest: Map<String, Any?>): List<ManifestIssue> {
        val issues = mutableListOf<ManifestIssue>()

        fun requiredString(key: String): String? {
            if (key !in manifest) {
                issues += error("Missing required key '$key'.")
                return null
            }
            val value = manifest[key]
            if (value !is String) {
                issues += error("'$key' must be a string, but was ${describe(value)}.")
                return null
            }
            return value
        }

        val id = requiredString("id")
        if (id != null) {
            when {
                id.isBlank() -> issues += error("'id' must not be blank.")
                id.trim().lowercase() in RESERVED_IDS ->
                    issues += error("'id' \"$id\" is reserved by StarLight (${RESERVED_IDS.joinToString()}).")
                !ID.matches(id) ->
                    issues += error("'id' \"$id\" may only contain the letters A-Z and a-z, digits, '.', '_' and '-'. StarLight refuses other ids.")
            }
        }
        requiredString("name")?.let { if (it.isBlank()) issues += error("'name' must not be blank.") }
        requiredString("main_class")?.let { checkClassName("main_class", it, issues) }
        requiredString("version")?.let { checkVersion("version", it, issues) }
        requiredString("api_version")?.let { value ->
            val apiVersion = checkVersion("api_version", value, issues)
            when {
                apiVersion == null -> {}
                apiVersion > KNOWN_HOST_API ->
                    issues += warning(
                        "'api_version' $value is newer than PluginCore $KNOWN_HOST_API, the newest StarLight host known to " +
                            "this Gradle plugin. StarLight refuses plugins whose api_version is newer than its PluginCore."
                    )
                apiVersion < MIN_HOST_API ->
                    issues += warning(
                        "'api_version' $value is older than $MIN_HOST_API, the oldest PluginCore API StarLight still loads. " +
                            "Build against a newer PluginCore and raise api_version."
                    )
            }
        }
        requiredString("description")

        if ("category" in manifest) {
            val category = manifest["category"]
            if (category !is String)
                issues += error("'category' must be a string, but was ${describe(category)}.")
            else if (CATEGORIES.none { it.equals(category.trim(), ignoreCase = true) })
                issues += warning("'category' \"$category\" is not a StarLight category (${CATEGORIES.joinToString()}), so StarLight lists the plugin under 'other'.")
        }

        if ("authors" !in manifest) {
            issues += error("Missing required key 'authors'.")
        } else {
            val authors = manifest["authors"]
            if (authors !is List<*> || authors.any { it !is String })
                issues += error("'authors' must be an array of strings, but was ${describe(authors)}.")
            else if (authors.isEmpty())
                issues += warning("'authors' is empty.")
        }

        manifest["custom_classloader"]?.let { value ->
            if (value !is String)
                issues += error("'custom_classloader' must be a string or null, but was ${describe(value)}.")
            else
                checkClassName("custom_classloader", value, issues)
        }
        if ("assetRevision" in manifest && manifest["assetRevision"] !is Int)
            issues += error("'assetRevision' must be an integer, but was ${describe(manifest["assetRevision"])}.")
        if ("uses_native_lib" in manifest && manifest["uses_native_lib"] !is Boolean)
            issues += error("'uses_native_lib' must be true or false, but was ${describe(manifest["uses_native_lib"])}.")

        if ("dependency" in manifest) {
            val dependencies = manifest["dependency"]
            if (dependencies !is List<*> || dependencies.any { it !is String })
                issues += error("'dependency' must be an array of strings, but was ${describe(dependencies)}.")
            else
                dependencies.forEach { checkDependency(it as String, id, issues) }
        }

        for (key in manifest.keys - KNOWN_KEYS) {
            val value = manifest[key]
            issues += when (key) {
                "depend" ->
                    if (value is List<*> && value.isEmpty())
                        warning("'depend' is not a StarLight key and is ignored. Remove it (dependencies go in 'dependency').")
                    else
                        error("'depend' is not a StarLight key, so these dependencies are silently ignored by the host. Rename it to 'dependency'.")
                "main" ->
                    warning("'main' is no longer read by StarLight. Use 'main_class'.")
                else ->
                    warning("Unknown key '$key' is ignored by StarLight.")
            }
        }
        return issues
    }

    /**
     * Mirrors PluginDependency(String): `id`, `id@version` or `id@*`, with a trailing `?` for optional dependencies.
     */
    private fun checkDependency(dependency: String, ownId: String?, issues: MutableList<ManifestIssue>) {
        if (dependency.isBlank()) {
            issues += error("'dependency' contains a blank entry.")
            return
        }
        val atIndex = dependency.indexOf('@')
        val dependencyId = if (atIndex == -1) dependency else dependency.substring(0, atIndex)
        if (atIndex == -1) {
            if (dependency.endsWith("?"))
                issues += warning(
                    "Dependency \"$dependency\": without '@', StarLight keeps the '?' as part of the id and never finds " +
                        "\"${dependency.removeSuffix("?")}\". Write \"${dependency.removeSuffix("?")}@*?\" instead."
                )
        } else {
            val version = dependency.substring(atIndex + 1).replace("?", "")
            if (version != "*" && StarlightVersion.parseOrNull(version) == null)
                issues += error(
                    "Dependency \"$dependency\": '$version' is not a valid version (expected major.minor.patch or *). " +
                        "StarLight would silently treat it as '*'."
                )
        }
        if (ownId != null && dependencyId.removeSuffix("?") == ownId)
            issues += error("Plugin '$ownId' must not depend on itself.")
    }

    private fun checkVersion(key: String, value: String, issues: MutableList<ManifestIssue>): StarlightVersion? =
        StarlightVersion.parseOrNull(value).also {
            if (it == null)
                issues += error("'$key' \"$value\" is not a valid StarLight version (expected major.minor.patch[.build], e.g. 1.0.0).")
        }

    private fun checkClassName(key: String, value: String, issues: MutableList<ManifestIssue>) {
        if (!CLASS_NAME.matches(value))
            issues += error("'$key' \"$value\" is not a valid fully qualified class name.")
    }

    /** PluginCore 0.3.x reads the manifest line by line and joins the lines with '\n' (`InputStream.readString`). */
    internal fun joinLinesLikeHost(text: String): String {
        val lines = text.lines()
        val trimmed =
            if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1)
            else lines
        return trimmed.joinToString("\n")
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is String -> "a string"
        is Boolean -> "a boolean"
        is Number -> "a number"
        is List<*> -> "an array"
        is Map<*, *> -> "an object"
        else -> value::class.java.simpleName
    }

    private fun error(message: String) = ManifestIssue(Severity.ERROR, message)

    private fun warning(message: String) = ManifestIssue(Severity.WARNING, message)
}
