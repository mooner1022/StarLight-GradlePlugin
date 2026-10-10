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
 * (`PluginLoader`, `PluginInfo`, `PluginDependency`, `Version` in PluginCore), plus mistakes the host
 * silently ignores, such as misspelled keys.
 */
internal object ManifestValidator {

    /** PluginCore API line of the current StarLight host. Plugins with another major.minor are not loaded. */
    val KNOWN_HOST_API = StarlightVersion(0, 3, 2)

    val RESERVED_IDS = setOf("global", "starlight", "system", "core")

    private val KNOWN_KEYS = setOf(
        "id", "name", "main_class", "custom_classloader", "version", "assetRevision",
        "api_version", "dependency", "uses_native_lib", "authors", "description",
    )

    private val CLASS_NAME = Regex("""[\p{L}_$][\p{L}\p{N}_$]*(\.[\p{L}_$][\p{L}\p{N}_$]*)*""")

    /** Validates the raw file content, including the formatting quirks of the host's reader. */
    fun validateText(text: String): ManifestValidation {
        val issues = mutableListOf<ManifestIssue>()
        val hostView = joinLinesLikeHost(text)
        when {
            hostView.startsWith('﻿') ->
                issues += error("starlight.json starts with a byte order mark (BOM), which StarLight rejects. Save it as UTF-8 without BOM.")
            !hostView.startsWith("{") ->
                issues += error("starlight.json must start with '{' (StarLight rejects leading whitespace or other characters).")
        }
        if (!hostView.endsWith("}"))
            issues += error("starlight.json must end with '}' (StarLight rejects trailing blank lines, whitespace or other characters).")

        val content = text.removePrefix("﻿")
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
        issues += validate(manifest)
        return ManifestValidation(issues, manifest)
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
                id.any { it.isWhitespace() || it == '@' || it == '?' } ->
                    issues += warning("'id' \"$id\" contains whitespace, '@' or '?', so other plugins cannot depend on it.")
            }
        }
        requiredString("name")?.let { if (it.isBlank()) issues += error("'name' must not be blank.") }
        requiredString("main_class")?.let { checkClassName("main_class", it, issues) }
        requiredString("version")?.let { checkVersion("version", it, issues) }
        requiredString("api_version")?.let { value ->
            val apiVersion = checkVersion("api_version", value, issues)
            if (apiVersion != null && !apiVersion.isCompatibleWith(KNOWN_HOST_API))
                issues += warning(
                    "'api_version' $value targets PluginCore ${apiVersion.major}.${apiVersion.minor}.x, but the current " +
                        "StarLight host provides ${KNOWN_HOST_API.major}.${KNOWN_HOST_API.minor}.x. The host only loads " +
                        "plugins whose api_version has the same major.minor."
                )
        }
        requiredString("description")

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

    /** The host reads the manifest line by line and joins the lines with '\n' (PluginCore `InputStream.readString`). */
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
