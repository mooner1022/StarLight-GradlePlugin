package dev.mooner.slplugin.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestValidatorTest {

    private val valid = linkedMapOf<String, Any?>(
        "id" to "sample",
        "name" to "Sample",
        "main_class" to "dev.example.SamplePlugin",
        "version" to "1.0.0",
        "api_version" to "0.3.0",
        "authors" to listOf("mooner"),
        "description" to "Sample plugin",
    )

    private fun validate(vararg overrides: Pair<String, Any?>, remove: Set<String> = emptySet()) =
        ManifestValidator.validate(valid.filterKeys { it !in remove } + overrides)

    private fun List<ManifestIssue>.errors() = filter { it.severity == Severity.ERROR }.map { it.message }
    private fun List<ManifestIssue>.warnings() = filter { it.severity == Severity.WARNING }.map { it.message }

    @Test
    fun `accepts a complete manifest`() {
        assertEquals(emptyList<ManifestIssue>(), validate())
    }

    @Test
    fun `reports every missing required key`() {
        val errors = validate(remove = setOf("id", "main_class", "authors")).errors()
        assertEquals(3, errors.size, errors.toString())
        assertTrue(errors.all { it.startsWith("Missing required key") })
    }

    @Test
    fun `rejects reserved ids case-insensitively`() {
        assertTrue(validate("id" to " Core ").errors().single().contains("reserved"))
    }

    @Test
    fun `rejects versions the host cannot parse`() {
        assertEquals(1, validate("version" to "1.0").errors().size)
        assertEquals(1, validate("version" to "0.0.1b7").errors().size)
        assertEquals(1, validate("api_version" to 1.0).errors().size)
        assertEquals(emptyList<String>(), validate("version" to "1.2.3.240820").errors())
    }

    @Test
    fun `warns when api_version targets another PluginCore line`() {
        val issues = validate("api_version" to "0.2.0.240203")
        assertEquals(emptyList<String>(), issues.errors())
        assertTrue(issues.warnings().single().contains("same major.minor"))
    }

    @Test
    fun `turns the silently ignored depend key into an error`() {
        assertTrue(validate("depend" to listOf("other")).errors().single().contains("Rename it to 'dependency'"))
        assertEquals(emptyList<String>(), validate("depend" to emptyList<String>()).errors())
        assertTrue(validate("listeners" to emptyList<String>()).warnings().single().contains("Unknown key 'listeners'"))
    }

    @Test
    fun `checks dependency strings like the host parses them`() {
        assertEquals(emptyList<ManifestIssue>(), validate("dependency" to listOf("a", "b@1.2.3", "c@*?", "d@0.1.0?")))
        assertTrue(validate("dependency" to listOf("a?")).warnings().single().contains("a@*?"))
        assertTrue(validate("dependency" to listOf("a@latest")).errors().single().contains("not a valid version"))
        assertTrue(validate("dependency" to listOf("sample")).errors().single().contains("itself"))
    }

    @Test
    fun `checks optional key types`() {
        val errors = validate("assetRevision" to "1", "uses_native_lib" to "yes", "custom_classloader" to "not a class").errors()
        assertEquals(3, errors.size, errors.toString())
    }

    @Test
    fun `rejects formatting the host reader cannot handle`() {
        val json = ManifestJson.write(valid)
        assertEquals(emptyList<ManifestIssue>(), ManifestValidator.validateText(json).issues)
        assertEquals(emptyList<ManifestIssue>(), ManifestValidator.validateText(json + "\n").issues)
        assertEquals(emptyList<ManifestIssue>(), ManifestValidator.validateText(json.replace("\n", "\r\n") + "\r\n").issues)

        assertTrue(ManifestValidator.validateText(json + "\n\n").issues.single().message.contains("must end with '}'"))
        assertTrue(ManifestValidator.validateText("  $json").issues.single().message.contains("must start with '{'"))
        assertTrue(ManifestValidator.validateText("﻿$json").issues.single().message.contains("byte order mark"))
    }

    @Test
    fun `accepts lenient json with a warning`() {
        val validation = ManifestValidator.validateText(
            "{ id: sample, name: Sample, main_class: dev.example.SamplePlugin, version: \"1.0.0\", " +
                "api_version: \"0.3.0\", authors: [mooner], description: Sample }"
        )
        assertEquals(emptyList<ManifestIssue>(), validation.errors)
        assertTrue(validation.warnings.single().message.contains("not valid JSON"))
    }

    @Test
    fun `reports json that is not an object`() {
        val validation = ManifestValidator.validateText("[\"sample\"]")
        assertNull(validation.manifest)
        assertTrue(validation.errors.any { "JSON object" in it.message }, validation.errors.toString())
    }
}
