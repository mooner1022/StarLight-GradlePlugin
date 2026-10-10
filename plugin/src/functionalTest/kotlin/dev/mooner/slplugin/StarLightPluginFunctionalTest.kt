package dev.mooner.slplugin

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class StarLightPluginFunctionalTest {

    private val manifestBlock = """
        sdk {
            pluginCore.set(file("libs/plugincore-stub.jar"))
        }
        manifest {
            id.set("fixture")
            name.set("Fixture")
            mainClass.set("dev.test.fixture.FixturePlugin")
            apiVersion.set("0.3.0")
            authors.add("tester")
            description.set("Functional test fixture")
            dependency("other", optional = true)
        }
    """.trimIndent()

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mooner.slplugin.AgpTarget#all")
    fun `generates the manifest and packages a validated slp`(target: AgpTarget) {
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("generate-${target.agp}", target)
        fixture.starlightBlock = manifestBlock
        fixture.file("src/main/assets/data.txt", "asset")

        // AGP 8.0 creates a lock file in the Gradle user home on its first run, which invalidates a cache entry stored
        // in the same build. Warm up first so the configuration cache assertions below are about this plugin.
        fixture.runner("help").build()
        val result = fixture.runner("assembleDebugSlp", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleDebugSlp")?.outcome, result.output)

        val entries = Fixture.zipEntries(fixture.slp("debug/fixture-debug.slp"))
        assertTrue("assets/data.txt" in entries)
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parseText(entries.getValue("assets/starlight.json").toString(Charsets.UTF_8)) as Map<String, Any?>
        assertEquals("fixture", manifest["id"])
        assertEquals("1.2.3", manifest["version"], "version should default to versionName")
        assertEquals(listOf("other@*?"), manifest["dependency"])
        assertNotEquals(0, manifest["assetRevision"], "assetRevision should be derived from the assets")

        val outputs = JsonSlurper().parse(fixture.slp("debug/starlight-outputs.json")) as Map<*, *>
        assertEquals("fixture", outputs["id"])

        val rerun = fixture.runner("assembleDebugSlp", "--configuration-cache").build()
        assertTrue("Configuration cache entry reused" in rerun.output, rerun.output)
        assertEquals(TaskOutcome.UP_TO_DATE, rerun.task(":assembleDebugSlp")?.outcome)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mooner.slplugin.AgpTarget#all")
    fun `rejects a hand-written manifest the host would misread`(target: AgpTarget) {
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("handwritten-${target.agp}", target)
        fixture.starlightBlock = """sdk { pluginCore.set(file("libs/plugincore-stub.jar")) }"""
        fixture.file(
            "src/main/assets/starlight.json",
            """
            {
              "id": "fixture",
              "name": "Fixture",
              "main_class": "dev.test.fixture.Missing",
              "version": "1.0",
              "api_version": "0.3.0",
              "depend": ["other"],
              "authors": ["tester"],
              "description": "Functional test fixture"
            }
            """.trimIndent(),
        )

        val result = fixture.runner("assembleDebugSlp").buildAndFail()
        assertTrue("Rename it to 'dependency'" in result.output, result.output)
        assertTrue("\"1.0\" is not a valid StarLight version" in result.output, result.output)
        assertTrue("main_class dev.test.fixture.Missing is not in the APK" in result.output, result.output)
    }

    @Test
    fun `rejects a main class that does not extend StarlightPlugin`() {
        val target = AgpTarget.LATEST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("not-a-plugin", target)
        fixture.starlightBlock = manifestBlock.replace("dev.test.fixture.FixturePlugin", "dev.test.fixture.NotAPlugin")

        val result = fixture.runner("assembleDebugSlp").buildAndFail()
        assertTrue("does not extend dev.mooner.starlight.plugincore.plugin.StarlightPlugin" in result.output, result.output)
    }

    @Test
    fun `warns about the bundled Kotlin runtime and can exclude it`() {
        val target = AgpTarget.LATEST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("kotlin-runtime", target)
        fixture.starlightBlock = manifestBlock
        fixture.dependenciesBlock = """implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")"""

        val bundled = fixture.runner("assembleDebugSlp").build()
        assertTrue("bundles libraries the StarLight host already provides: kotlin-stdlib" in bundled.output, bundled.output)

        fixture.starlightBlock = manifestBlock.replace("sdk {", "sdk {\n    excludeHostKotlinLibraries.set(true)")
        val excluded = fixture.runner("assembleDebugSlp").build()
        assertFalse("already provides" in excluded.output, excluded.output)
        val classes = Fixture.zipEntries(fixture.slp("debug/fixture-debug.slp")).getValue("classes.dex")
        assertFalse("Lkotlin/Unit;" in String(classes, Charsets.ISO_8859_1))
    }

    @Test
    fun `explains a missing SDK file before compiling`() {
        val target = AgpTarget.LATEST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("missing-sdk", target)
        fixture.starlightBlock = manifestBlock.replace("libs/plugincore-stub.jar", "libs/PluginCore-release.aar")

        val result = fixture.runner("assembleDebugSlp").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":verifyStarlightSdk")?.outcome, result.output)
        assertTrue("starlight.sdk.pluginCore" in result.output, result.output)
    }

    @Test
    fun `deploy reports an unknown device without touching any device`() {
        val target = AgpTarget.LATEST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("deploy-unknown-device", target)
        fixture.starlightBlock = manifestBlock

        // adb -s with a serial no device can have, so this never pushes to a real device.
        val result = fixture.runner("deployDebugSlp", "-Pstarlight.device=slplugin-no-such-device").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":deployDebugSlp")?.outcome, result.output)
        assertTrue("slplugin-no-such-device" in result.output, result.output)
        assertTrue("Connect a device with USB debugging" in result.output, result.output)
    }

    @Test
    fun `registers the StarLight tasks`() {
        val target = AgpTarget.LATEST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("tasks", target)
        fixture.starlightBlock = manifestBlock

        val result = fixture.runner("tasks", "--group", "starlight").build()
        listOf(
            "assembleDebugSlp", "assembleReleaseSlp", "assembleSlp", "deployDebugSlp", "deployReleaseSlp",
            "generateDebugStarlightManifest", "restartStarlight", "starlightLogcat", "verifyStarlightSdk",
        ).forEach { task -> assertTrue(task in result.output, "$task missing:\n${result.output}") }
    }

    @Test
    fun `requires the Android application plugin`() {
        val fixture = Fixture("no-android", AgpTarget.LATEST)
        fixture.applyAndroid = false

        val result = fixture.runner("help").buildAndFail()
        assertTrue("requires the Android application plugin" in result.output, result.output)
    }
}
