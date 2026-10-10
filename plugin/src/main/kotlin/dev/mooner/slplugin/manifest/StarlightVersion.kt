package dev.mooner.slplugin.manifest

/**
 * Version string as parsed by the StarLight host (`dev.mooner.starlight.plugincore.version.Version`):
 * `major.minor.patch[.build]`, where the first three parts are integers and `build` is a free-form tag.
 */
internal data class StarlightVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: String? = null,
) {
    /** The host only loads a plugin whose api_version has the same major and minor as its PluginCore. */
    fun isCompatibleWith(other: StarlightVersion): Boolean =
        major == other.major && minor == other.minor

    override fun toString(): String =
        listOfNotNull(major, minor, patch, build).joinToString(".")

    companion object {
        fun parseOrNull(value: String): StarlightVersion? {
            val parts = value.split(".")
            if (parts.size < 3)
                return null
            val numbers = parts.take(3).map { part ->
                if (part.isEmpty() || part.any { it !in '0'..'9' })
                    return null
                part.toIntOrNull() ?: return null
            }
            return StarlightVersion(numbers[0], numbers[1], numbers[2], parts.getOrNull(3))
        }
    }
}
