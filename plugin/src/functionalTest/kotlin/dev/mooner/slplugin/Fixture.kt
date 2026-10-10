package dev.mooner.slplugin

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.tools.ToolProvider

/** An AGP version and the Gradle version the fixture runs it with. */
data class AgpTarget(
    val agp: String,
    val gradle: String,
    val compileSdk: Int,
    val buildTools: String,
) {
    override fun toString() = "AGP $agp / Gradle $gradle"

    companion object {
        val MIN = AgpTarget(System.getProperty("slplugin.agp.min"), "8.2", 34, "34.0.0")
        val LATEST = AgpTarget(System.getProperty("slplugin.agp.latest"), "9.8.0", 36, "36.0.0")

        @JvmStatic
        fun all() = listOf(MIN, LATEST)
    }
}

/** A minimal StarLight plugin project consuming the plugin from the local functional-test repository. */
class Fixture(name: String, private val target: AgpTarget) {

    val dir: File = File("build/functional-test-projects/$name").absoluteFile.apply {
        deleteRecursively()
        mkdirs()
    }

    var starlightBlock = ""
    var dependenciesBlock = ""
    var applyAndroid = true

    init {
        file("src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />""")
        file(
            "src/main/java/dev/test/fixture/FixturePlugin.java",
            """
            package dev.test.fixture;

            public class FixturePlugin extends dev.mooner.starlight.plugincore.plugin.StarlightPlugin {
            }
            """.trimIndent(),
        )
        file(
            "src/main/java/dev/test/fixture/NotAPlugin.java",
            """
            package dev.test.fixture;

            public class NotAPlugin {
            }
            """.trimIndent(),
        )
        stubPluginCore.copyTo(file("libs/plugincore-stub.jar"), overwrite = true)
    }

    fun file(path: String, content: String? = null): File =
        File(dir, path).apply {
            parentFile.mkdirs()
            if (content != null)
                writeText(content)
        }

    fun runner(vararg arguments: String): GradleRunner {
        writeBuildFiles()
        val runner = GradleRunner.create()
            .withProjectDir(dir)
            .withTestKitDir(File(System.getProperty("slplugin.testKitDir")))
            .withArguments(*arguments, "--stacktrace")
        val installation = localGradleInstallation(target.gradle)
        return if (installation != null) runner.withGradleInstallation(installation) else runner.withGradleVersion(target.gradle)
    }

    private fun writeBuildFiles() {
        val repository = File(System.getProperty("slplugin.testRepo")).toURI()
        file(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        val androidPlugin = if (applyAndroid) """id("com.android.application") version "${target.agp}"""" else ""
        val androidBlock =
            if (!applyAndroid) ""
            else """
            android {
                namespace = "dev.test.fixture"
                compileSdk = ${target.compileSdk}
                buildToolsVersion = "${target.buildTools}"
                defaultConfig {
                    minSdk = 26
                    versionName = "1.2.3"
                }
            }
            """.trimIndent()
        file(
            "build.gradle.kts",
            """
            |plugins {
            |    $androidPlugin
            |    id("io.github.mooner1022.slplugin") version "${System.getProperty("slplugin.version")}"
            |}
            |
            |$androidBlock
            |
            |dependencies {
            |    $dependenciesBlock
            |}
            |
            |starlight {
            |    $starlightBlock
            |}
            """.trimMargin(),
        )
        file(
            "gradle.properties",
            """
            org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
            android.suppressUnsupportedCompileSdk=${target.compileSdk}
            """.trimIndent(),
        )
        file("local.properties", "sdk.dir=${androidSdk.invariantSeparatorsPath}")
    }

    fun slp(name: String): File =
        File(dir, "build/outputs/starlight/$name")

    companion object {

        val androidSdk: File by lazy {
            val candidates = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
                System.getProperty("user.home") + "/Library/Android/sdk",
                System.getProperty("user.home") + "/Android/Sdk",
            )
            candidates.map(::File).firstOrNull { File(it, "platforms").isDirectory }
                ?: File("missing-android-sdk")
        }

        fun assumeAndroidSdk(target: AgpTarget) {
            assumeTrue(File(androidSdk, "platforms/android-${target.compileSdk}").isDirectory) {
                "Android SDK platform ${target.compileSdk} not found (set ANDROID_HOME)"
            }
            assumeTrue(File(androidSdk, "build-tools/${target.buildTools}").isDirectory) {
                "Android build-tools ${target.buildTools} not found"
            }
        }

        /** Reuses a Gradle distribution already downloaded by the wrapper instead of downloading it again. */
        private fun localGradleInstallation(version: String): File? {
            val userHome = System.getenv("GRADLE_USER_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".gradle")
            return File(userHome, "wrapper/dists/gradle-$version-bin").listFiles()
                ?.map { File(it, "gradle-$version") }
                ?.firstOrNull { File(it, "lib").isDirectory }
        }

        /** Stand-in for PluginCore with just `StarlightPlugin`, compiled on the fly. */
        private val stubPluginCore: File by lazy {
            val root = File("build/functional-test-stub").absoluteFile.apply { deleteRecursively() }
            val source = File(root, "src/dev/mooner/starlight/plugincore/plugin/StarlightPlugin.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package dev.mooner.starlight.plugincore.plugin;

                    public abstract class StarlightPlugin {
                        public StarlightPlugin() {
                        }
                    }
                    """.trimIndent()
                )
            }
            val classes = File(root, "classes").apply { mkdirs() }
            val result = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "--release", "8", "-d", classes.path, source.path)
            check(result == 0) { "Could not compile the PluginCore stub" }

            File(root, "plugincore-stub.jar").also { jar ->
                JarOutputStream(jar.outputStream()).use { output ->
                    classes.walkTopDown().filter(File::isFile).forEach { file ->
                        output.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
        }

        fun zipEntries(file: File): Map<String, ByteArray> =
            ZipFile(file).use { zip ->
                zip.entries().toList().filterNot { it.isDirectory }
                    .associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
            }
    }
}
