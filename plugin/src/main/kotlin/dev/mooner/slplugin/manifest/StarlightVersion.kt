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
) : Comparable<StarlightVersion> {

    /** Like the host, ignores [build]. */
    override fun compareTo(other: StarlightVersion): Int =
        compareValuesBy(this, other, StarlightVersion::major, StarlightVersion::minor, StarlightVersion::patch)

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
