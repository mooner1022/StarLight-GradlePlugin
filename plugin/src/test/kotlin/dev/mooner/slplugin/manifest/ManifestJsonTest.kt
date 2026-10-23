package dev.mooner.slplugin.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManifestJsonTest {

    @Test
    fun `writes pretty json that parses back to the same values`() {
        val value = linkedMapOf(
            "id" to "sample",
            "name" to "샘플 \"plugin\"\t\\",
            "assetRevision" to 42,
            "uses_native_lib" to false,
            "dependency" to emptyList<String>(),
            "authors" to listOf("mooner", "welim"),
            "custom_classloader" to null,
        )
        val json = ManifestJson.write(value)

        assertEquals(value, ManifestJson.parse(json))
        assertEquals('}', json.last())
        assertEquals(-1, json.indexOf('\r'))
    }

    @Test
    fun `parses leniently like the host`() {
        val parsed = ManifestJson.parseLenient("{ id: sample, 'name': \"Sample\" }") as Map<*, *>
        assertEquals("sample", parsed["id"])
        assertEquals("Sample", parsed["name"])
    }

    @Test
    fun `parses versions as in the host`() {
        assertEquals(StarlightVersion(0, 3, 0, "240820"), StarlightVersion.parseOrNull("0.3.0.240820"))
        assertEquals(StarlightVersion(1, 2, 3), StarlightVersion.parseOrNull("1.2.3"))
        assertNull(StarlightVersion.parseOrNull("1.2"))
        assertNull(StarlightVersion.parseOrNull("1.2.x"))
        assertNull(StarlightVersion.parseOrNull("-1.2.3"))
    }
}
