package dev.mooner.slplugin.api

import dev.mooner.slplugin.manifest.ManifestIssue
import dev.mooner.slplugin.manifest.Severity
import dev.mooner.slplugin.manifest.StarlightVersion

/** Compares a manifest's `api_version` with the PluginCore API the plugin's code uses. */
internal object ApiVersionCheck {

    private const val LISTED = 10
    private const val TARGET = "target_api_version"

    /** An error when [apiVersion] is lower than what [report] found the code needs; older hosts would fail at run time. */
    fun issues(apiVersion: StarlightVersion, report: ApiUsageReport): List<ManifestIssue> {
        val beyond = report.beyond(apiVersion)
        if (beyond.isEmpty())
            return emptyList()
        val required = report.required!!
        val listed = beyond.take(LISTED).joinToString("\n") { "      ${it.location}: ${it.api} (PluginCore ${it.since})" }
        val more = if (beyond.size > LISTED) "\n      … and ${beyond.size - LISTED} more" else ""
        return listOf(ManifestIssue(Severity.ERROR,
            "'api_version' $apiVersion is lower than PluginCore $required, which the code uses:\n$listed$more\n" +
                "    StarLight with an older PluginCore would load the plugin and fail when this code runs. Raise api_version " +
                "to $required, or call this code only after checking Info.PLUGINCORE_VERSION, from code marked " +
                "@RequiresPluginCore(\"$required\")."
        ))
    }

    /**
     * An error when [manifest] has no `target_api_version` although the PluginCore the plugin is built against is known
     * ([ApiUsageReport.pluginCore], from PluginCore 1.1.0 on). Generated manifests always have it, so this is about
     * hand-written ones. Without a known PluginCore there is nothing to require or to suggest.
     */
    fun targetIssues(manifest: Map<String, Any?>, report: ApiUsageReport): List<ManifestIssue> {
        val pluginCore = report.pluginCore ?: return emptyList()
        if (TARGET in manifest)
            return emptyList()
        return listOf(ManifestIssue(Severity.ERROR,
            "starlight.json has no '$TARGET'. Add \"$TARGET\": \"$pluginCore\", the PluginCore the plugin is built " +
                "against, or generate the manifest with starlight { manifest { ... } }, which writes it. StarLight will " +
                "use it to apply a behavior change only to the plugins that target the version that made it."
        ))
    }

    /**
     * A note when [apiVersion] is higher than the code needs, which keeps hosts that could run the plugin from loading it;
     * null otherwise. It may be on purpose, as when the plugin relies on a newer host's behavior.
     */
    fun note(apiVersion: StarlightVersion, report: ApiUsageReport): String? {
        val lowest = report.required ?: report.baseline ?: return null
        if (apiVersion <= lowest)
            return null
        val needs = if (report.required != null) "PluginCore $lowest" else "nothing newer than PluginCore $lowest"
        return "api_version $apiVersion is higher than the code needs ($needs), so StarLight with an older PluginCore " +
            "won't load the plugin. Lower it unless the plugin relies on how newer hosts behave."
    }
}
