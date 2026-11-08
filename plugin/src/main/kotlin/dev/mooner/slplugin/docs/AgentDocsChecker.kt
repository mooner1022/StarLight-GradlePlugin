package dev.mooner.slplugin.docs

import dev.mooner.slplugin.apk.PluginApk
import dev.mooner.slplugin.manifest.ManifestIssue
import dev.mooner.slplugin.manifest.ManifestJson
import dev.mooner.slplugin.manifest.Severity

/** Checks the docs StarLight AI reads from a plugin's package the way StarLight reads them. */
internal object AgentDocsChecker {

    /** The doc file format StarLight reads; it skips files of another one. */
    const val FORMAT = 1

    class Report(val issues: List<ManifestIssue>, val summary: String?)

    /**
     * @param id the plugin's ID, whose doc file StarLight reads.
     * @param expected whether KSP runs the docs processor, so the doc file should be there.
     */
    fun check(apk: PluginApk, id: String, expected: Boolean): Report {
        val issues = mutableListOf<ManifestIssue>()
        val entry = PluginApk.AGENT_DOCS_DIR + "$id.api.json"

        for (other in apk.agentDocs.keys - entry)
            issues += warning("$other is ignored: StarLight reads the docs of plugin $id from $entry only.")

        var classes: Int? = null
        val text = apk.agentDocs[entry]
        if (text != null) {
            val docs = runCatching { ManifestJson.parse(text) }.getOrNull() as? Map<*, *>
            when {
                docs == null ->
                    issues += warning("$entry isn't a JSON object, so StarLight AI shows the plugin's API without its docs.")
                (docs["format"] as? Number)?.toInt() != FORMAT ->
                    issues += warning("$entry has format ${docs["format"]}, and StarLight reads format $FORMAT only.")
                docs["module"] != id ->
                    issues += warning("$entry is the docs of module ${docs["module"]}, not of plugin $id.")
                else ->
                    classes = (docs["classes"] as? Map<*, *>)?.size ?: 0
            }
        } else if (expected) {
            issues += warning(
                "KSP is applied, but ${apk.file.name} has no $entry, so StarLight AI shows the plugin's API without its docs."
            )
        }

        val guides = apk.guides.mapNotNull { (name, guideText) ->
            val guideId = name.removePrefix(PluginApk.GUIDES_DIR).removeSuffix(".md")
            val problem = guideProblem(guideText)
            if (problem != null) {
                issues += warning("StarLight skips guide $guideId ($name): $problem")
                null
            } else guideId
        }

        val summary = listOfNotNull(
            classes?.let { "$it ${if (it == 1) "class" else "classes"} in $entry" },
            guides.takeIf { it.isNotEmpty() }?.let { "${it.size} ${if (it.size == 1) "guide" else "guides"} ($id:${it.sorted().joinToString(", $id:")})" },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "StarLight AI docs: ")
        return Report(issues, summary)
    }

    /** Why StarLight wouldn't list a guide: it needs front matter with a `title` and a `summary`. */
    internal fun guideProblem(text: String): String? {
        if (text.startsWith("﻿"))
            return "it starts with a byte order mark, so its front matter isn't read. Save it as UTF-8 without BOM."
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---"))
            return "it doesn't start with front matter (---, then title: and summary:, then ---)."
        val header = normalized.removePrefix("---").substringBefore("\n---", missingDelimiterValue = "")
        if (header.isEmpty() && "\n---" !in normalized.removePrefix("---"))
            return "its front matter isn't closed with ---."
        val fields = header.lines()
            .mapNotNull { line -> line.substringBefore(':', "").trim().takeIf(String::isNotEmpty)?.let { it to line.substringAfter(':').trim() } }
            .toMap()
        val missing = listOf("title", "summary").filter { fields[it].isNullOrEmpty() }
        return if (missing.isEmpty()) null else "its front matter has no ${missing.joinToString(" or ")}."
    }

    private fun warning(message: String) = ManifestIssue(Severity.WARNING, message)
}
