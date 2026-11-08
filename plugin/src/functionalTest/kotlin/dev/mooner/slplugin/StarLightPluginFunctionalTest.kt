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
            pluginCore = file("libs/plugincore-stub.jar")
        }
        manifest {
            id = "fixture"
            name = "Fixture"
            mainClass = "dev.test.fixture.FixturePlugin"
            apiVersion = "0.3.0"
            authors.add("tester")
            description = "Functional test fixture"
            category = "library"
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
        assertEquals("library", manifest["category"])
        assertNotEquals(0, manifest["assetRevision"], "assetRevision should be derived from the assets")

        val outputs = JsonSlurper().parse(fixture.slp("debug/starlight-outputs.json")) as Map<*, *>
        assertEquals("fixture", outputs["id"])

        val rerun = fixture.runner("assembleDebugSlp", "--configuration-cache").build()
        assertTrue("Configuration cache entry reused" in rerun.output, rerun.output)
        assertEquals(TaskOutcome.UP_TO_DATE, rerun.task(":assembleDebugSlp")?.outcome)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mooner.slplugin.AgpTarget#all")
    fun `works out api_version from the PluginCore API the code uses`(target: AgpTarget) {
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("api-version-${target.agp}", target)
        fun block(apiVersion: String?) = """
            sdk {
                pluginCore = file("libs/plugincore-recording.jar")
            }
            manifest {
                id = "fixture"
                name = "Fixture"
                mainClass = "dev.test.fixture.FixturePlugin"
                ${apiVersion?.let { "apiVersion = \"$it\"" }.orEmpty()}
                authors.add("tester")
                description = "Functional test fixture"
            }
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        fun manifest() = JsonSlurper().parseText(
            Fixture.zipEntries(fixture.slp("debug/fixture-debug.slp")).getValue("assets/starlight.json").toString(Charsets.UTF_8)
        ) as Map<String, Any?>

        // Nothing newer than the baseline: the oldest release the PluginCore records.
        fixture.starlightBlock = block(apiVersion = null)
        fixture.runner("assembleDebugSlp").build()
        assertEquals("1.0.1", manifest()["api_version"])
        assertEquals("1.1.0", manifest()["target_api_version"])

        // A call to a member PluginCore 1.1.0 added.
        fixture.file(
            "src/main/java/dev/test/fixture/UsesNewApi.java",
            """
            package dev.test.fixture;

            public class UsesNewApi {
                void run(FixturePlugin plugin) {
                    plugin.addedLater();
                }
            }
            """.trimIndent(),
        )
        fixture.runner("assembleDebugSlp").build()
        assertEquals("1.1.0", manifest()["api_version"])

        // Declared lower than that: the build names the use.
        fixture.starlightBlock = block(apiVersion = "1.0.1")
        val result = fixture.runner("assembleDebugSlp").buildAndFail()
        assertTrue("'api_version' 1.0.1 is lower than PluginCore 1.1.0" in result.output, result.output)
        assertTrue("dev.test.fixture.UsesNewApi.run (UsesNewApi.java:5): StarlightPlugin.addedLater()" in result.output, result.output)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mooner.slplugin.AgpTarget#all")
    fun `rejects a hand-written manifest the host would misread`(target: AgpTarget) {
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("handwritten-${target.agp}", target)
        fixture.starlightBlock = """sdk { pluginCore = file("libs/plugincore-stub.jar") }"""
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
    fun `requires target_api_version in a hand-written manifest`() {
        val target = AgpTarget.HOST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("target-api-version", target)
        // A PluginCore that records its version (1.1.0), as from 1.1.0 on.
        fixture.starlightBlock = """sdk { pluginCore = file("libs/plugincore-recording.jar") }"""
        fun manifest(extra: String) = fixture.file(
            "src/main/assets/starlight.json",
            """
            {
              "id": "fixture",
              "name": "Fixture",
              "main_class": "dev.test.fixture.FixturePlugin",
              "version": "1.0.0",
              "api_version": "1.0.1",$extra
              "authors": ["tester"],
              "description": "Functional test fixture"
            }
            """.trimIndent(),
        )

        manifest("")
        val missing = fixture.runner("assembleDebugSlp").buildAndFail()
        assertTrue("starlight.json has no 'target_api_version'. Add \"target_api_version\": \"1.1.0\"" in missing.output, missing.output)

        manifest("\n  \"target_api_version\": \"1.1.0\",")
        val result = fixture.runner("assembleDebugSlp").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleDebugSlp")?.outcome, result.output)
    }

    @Test
    fun `writes the plugin's KDoc for StarLight AI when KSP is applied`() {
        // KSP 2.3.13 refuses AGP before 8.12, so plugins on older AGP build without docs.
        val target = AgpTarget.LATEST
        Fixture.assumeAndroidSdk(target)
        val fixture = Fixture("agent-docs", target)
        // As in the sample: AGP's built-in Kotlin with the Kotlin of the StarLight app.
        fixture.extraPlugins = """
            id("org.jetbrains.kotlin.android") version "${System.getProperty("slplugin.kotlin")}" apply false
            id("com.google.devtools.ksp") version "${System.getProperty("slplugin.ksp")}"
        """.trimIndent()
        fixture.starlightBlock = manifestBlock
        fixture.file(
            "src/main/kotlin/dev/test/fixture/Greeter.kt",
            """
            package dev.test.fixture

            /** Greets people. */
            class Greeter {
                /**
                 * A greeting for [name].
                 *
                 * @param name Who to greet.
                 */
                fun greet(name: String): String = "Hello, " + name
            }
            """.trimIndent(),
        )
        fixture.file("src/main/assets/agent/guides/greetings.md", "---\ntitle: Greetings\nsummary: How to greet.\n---\n\n## Use\n")
        fixture.file("src/main/assets/agent/guides/notes.md", "# Notes without front matter\n")

        fixture.runner("help").build()
        val result = fixture.runner("assembleDebugSlp", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleDebugSlp")?.outcome, result.output)

        @Suppress("UNCHECKED_CAST")
        fun docs(id: String) = JsonSlurper().parseText(
            Fixture.zipEntries(fixture.slp("debug/$id-debug.slp")).getValue("starlight/agent/$id.api.json").toString(Charsets.UTF_8)
        ) as Map<String, Any?>

        val docs = docs("fixture")
        assertEquals(1, docs["format"])
        assertEquals("fixture", docs["module"])
        val greeter = (docs["classes"] as Map<*, *>)["dev.test.fixture.Greeter"] as Map<*, *>
        val greet = (greeter["members"] as Map<*, *>)["greet(Ljava/lang/String;)Ljava/lang/String;"] as Map<*, *>
        assertEquals("A greeting for `name`.", (greet["doc"] as Map<*, *>)["summary"])
        assertTrue("StarLight AI docs: " in result.output && "1 guide (fixture:greetings)" in result.output, result.output)
        assertTrue("StarLight skips guide notes" in result.output, result.output)

        val rerun = fixture.runner("assembleDebugSlp", "--configuration-cache").build()
        assertTrue("Configuration cache entry reused" in rerun.output, rerun.output)

        // A hand-written manifest names the docs too.
        fixture.starlightBlock = """
            sdk {
                pluginCore = file("libs/plugincore-stub.jar")
            }
        """.trimIndent()
        fixture.file(
            "src/main/assets/starlight.json",
            """{"id":"handwritten","name":"Hand","main_class":"dev.test.fixture.FixturePlugin","version":"1.0.0","api_version":"0.3.0","authors":["tester"],"description":"Hand-written"}""",
        )
        fixture.runner("assembleDebugSlp").build()
        assertEquals("handwritten", docs("handwritten")["module"])
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
        fixture.dependenciesBlock = """implementation("org.jetbrains.kotlin:kotlin-stdlib:${System.getProperty("slplugin.kotlin")}")"""

        val bundled = fixture.runner("assembleDebugSlp").build()
        assertTrue("bundles libraries the StarLight host already provides: kotlin-stdlib" in bundled.output, bundled.output)

        fixture.starlightBlock = manifestBlock.replace("sdk {", "sdk {\n    excludeHostKotlinLibraries = true")
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
