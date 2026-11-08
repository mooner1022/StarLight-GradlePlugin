package dev.mooner.slplugin.docs

import dev.mooner.slplugin.apk.PluginApk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentDocsCheckerTest {

    @TempDir
    lateinit var dir: File

    private fun apk(vararg entries: Pair<String, String>): PluginApk {
        val file = File(dir, "plugin.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return PluginApk.read(file)
    }

    private val docs = """{"format":1,"module":"demo","packages":[],"classes":{"a.B":{"kind":"class"},"a.C":{"kind":"class"}}}"""
    private val guide = "---\ntitle: Setup\nsummary: Connecting the bot.\n---\n\n## Token\n"

    @Test
    fun `summarizes the docs and guides StarLight reads`() {
        val report = AgentDocsChecker.check(
            apk("starlight/agent/demo.api.json" to docs, "assets/agent/guides/setup.md" to guide, "assets/agent/guides/more/faq.md" to guide),
            id = "demo",
            expected = true,
        )
        assertEquals(emptyList<Any>(), report.issues)
        assertEquals("StarLight AI docs: 2 classes in starlight/agent/demo.api.json, 2 guides (demo:more/faq, demo:setup)", report.summary)
    }

    @Test
    fun `reports docs StarLight wouldn't find`() {
        val other = AgentDocsChecker.check(apk("starlight/agent/old_id.api.json" to docs), id = "demo", expected = true)
        assertEquals(2, other.issues.size, other.issues.toString())
        assertTrue("starlight/agent/old_id.api.json is ignored" in other.issues[0].message)
        assertTrue("KSP is applied, but" in other.issues[1].message)
        assertNull(other.summary)

        val module = AgentDocsChecker.check(apk("starlight/agent/demo.api.json" to docs.replace("\"demo\"", "\"x\"")), "demo", false)
        assertTrue("docs of module x, not of plugin demo" in module.issues.single().message)

        val format = AgentDocsChecker.check(apk("starlight/agent/demo.api.json" to docs.replace("\"format\":1", "\"format\":2")), "demo", false)
        assertTrue("format 2" in format.issues.single().message)

        // Without KSP, no docs is fine.
        assertEquals(emptyList<Any>(), AgentDocsChecker.check(apk("assets/starlight.json" to "{}"), "demo", expected = false).issues)
    }

    @Test
    fun `reports guides StarLight skips`() {
        assertNull(AgentDocsChecker.guideProblem(guide))
        assertNull(AgentDocsChecker.guideProblem(guide.replace("\n", "\r\n")))
        assertTrue("doesn't start with front matter" in AgentDocsChecker.guideProblem("# Setup\n")!!)
        assertTrue("byte order mark" in AgentDocsChecker.guideProblem("﻿$guide")!!)
        assertTrue("isn't closed" in AgentDocsChecker.guideProblem("---\ntitle: A\nsummary: B\n")!!)
        assertTrue("no summary" in AgentDocsChecker.guideProblem("---\ntitle: A\n---\n")!!)

        val report = AgentDocsChecker.check(apk("assets/agent/guides/setup.md" to "# Setup\n"), "demo", expected = false)
        assertTrue("StarLight skips guide setup (assets/agent/guides/setup.md)" in report.issues.single().message)
        assertNull(report.summary)
    }
}
