package dev.mooner.slplugin.api

import dev.mooner.slplugin.manifest.ManifestJson
import dev.mooner.slplugin.manifest.StarlightVersion
import java.io.File

/**
 * What a variant's code needs from PluginCore, as [ApiUsageScanner] found it. Empty when the PluginCore it is built
 * against says nothing of its API (no `plugincore-api.json`).
 */
internal class ApiUsageReport(
    /** The PluginCore the plugin is built against: its `target_api_version`. */
    val pluginCore: StarlightVersion?,
    /** The oldest PluginCore release the file records. */
    val baseline: StarlightVersion?,
    /** Every use of PluginCore API newer than [baseline], newest first. */
    val usages: List<ApiUsage>,
) {

    /** The lowest `api_version` the code allows: null when it uses nothing newer than [baseline], or nothing is known. */
    val required: StarlightVersion? get() = usages.maxOfOrNull { it.since }

    /** Whether anything is known: the PluginCore had `plugincore-api.json`. */
    val isKnown: Boolean get() = pluginCore != null

    /** The usages that need a PluginCore newer than [apiVersion], for an error message. */
    fun beyond(apiVersion: StarlightVersion): List<ApiUsage> = usages.filter { it.since > apiVersion }

    fun writeTo(file: File) {
        val json = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "pluginCore" to pluginCore?.toString(),
            "baseline" to baseline?.toString(),
            "usages" to usages.map { linkedMapOf("since" to it.since.toString(), "api" to it.api, "at" to it.location) },
        )
        file.parentFile.mkdirs()
        file.writeText(ManifestJson.write(json), Charsets.UTF_8)
    }

    companion object {

        private const val FORMAT = 1

        val UNKNOWN = ApiUsageReport(null, null, emptyList())

        fun readFrom(file: File): ApiUsageReport {
            val json = ManifestJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return UNKNOWN
            if ((json["format"] as? Number)?.toInt() != FORMAT)
                return UNKNOWN
            fun version(value: Any?) = (value as? String)?.let(StarlightVersion::parseOrNull)
            val usages = (json["usages"] as? List<*>).orEmpty().mapNotNull { entry ->
                val usage = entry as? Map<*, *> ?: return@mapNotNull null
                ApiUsage(
                    location = usage["at"] as? String ?: return@mapNotNull null,
                    api = usage["api"] as? String ?: return@mapNotNull null,
                    since = version(usage["since"]) ?: return@mapNotNull null,
                )
            }
            return ApiUsageReport(version(json["pluginCore"]), version(json["baseline"]), usages)
        }
    }
}
