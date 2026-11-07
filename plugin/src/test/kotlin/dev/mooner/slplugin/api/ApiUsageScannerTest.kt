package dev.mooner.slplugin.api

import dev.mooner.slplugin.manifest.StarlightVersion
import dev.mooner.slplugin.manifest.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiUsageScannerTest {

    private val temp: Path = Files.createTempDirectory("api-usage")

    private lateinit var coreJar: File
    private lateinit var usages: List<ApiUsage>

    private val v110 = StarlightVersion(1, 1, 0)
    private val v120 = StarlightVersion(1, 2, 0)

    @BeforeAll
    fun compile() {
        val root = temp.toFile()
        val coreClasses = javac(root.resolve("core"), CORE_SOURCES, classpath = null)
        coreJar = root.resolve("plugincore.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                coreClasses.walkTopDown().filter(File::isFile).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(coreClasses).invariantSeparatorsPath))
                    zip.write(file.readBytes())
                }
                zip.putNextEntry(ZipEntry(PluginCoreApi.API_FILE))
                zip.write(CORE_API_JSON.toByteArray())
            }
        }
        val pluginClasses = javac(root.resolve("plugin"), PLUGIN_SOURCES, classpath = coreClasses)
        val core = PluginCoreApi.read(coreJar)!!
        usages = ApiUsageScanner(core).scan(
            pluginClasses.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map(File::readBytes)
        )
    }

    @AfterAll
    fun deleteTemp() {
        temp.toFile().deleteRecursively()
    }

    private fun usedIn(method: String) = usages.filter { it.location.startsWith("test.plugin.$method ") }

    @Test
    fun `counts a call to a member PluginCore added`() {
        val usage = usedIn("Direct.run").single()
        assertEquals("ProjectInfoBuilder.setAllowedEventIds(Set)", usage.api)
        assertEquals(v110, usage.since)
        assertTrue(usage.location.endsWith("(Direct.java:6)"), usage.location)
    }

    @Test
    fun `finds the declaring PluginCore class through the plugin's subclass`() {
        assertEquals("ProjectEvent.getScriptArguments()", usedIn("ThroughSubclass.run").single().api)
        assertEquals("ProjectEvent.getScriptArguments()", usedIn("MethodRef.ref").single().api)
    }

    @Test
    fun `leaves out overrides and their super calls`() {
        assertEquals(emptyList<ApiUsage>(), usages.filter { it.location.startsWith("test.plugin.Overrides") })
        assertEquals(emptyList<ApiUsage>(), usedIn("CallsOverride.run"))
    }

    @Test
    fun `leaves out code marked RequiresPluginCore, with its lambdas and local classes`() {
        assertEquals(emptyList<ApiUsage>(), usages.filter { it.location.startsWith("test.plugin.Guarded.") || it.location.startsWith("test.plugin.Guarded$") })
        assertEquals(emptyList<ApiUsage>(), usages.filter { it.location.startsWith("test.plugin.ClassLevel") })
    }

    @Test
    fun `still counts what marked code uses beyond its version`() {
        val usage = usedIn("GuardedTooLow.run").single()
        assertEquals("ProjectInfoBuilder.setFuture(String)", usage.api)
        assertEquals(v120, usage.since)
    }

    @Test
    fun `counts classes PluginCore added`() {
        assertEquals(setOf("NewThing", "new NewThing()"), usedIn("UsesNewClass.make").map { it.api }.toSet())
        assertTrue(usages.any { it.location == "test.plugin.ExtendsNew (ExtendsNew.java)" && it.api == "NewThing" }, usages.toString())
    }

    @Test
    fun `ignores what the baseline has`() {
        assertEquals(emptyList<ApiUsage>(), usedIn("Baseline.run"))
    }

    @Test
    fun `reads the API file from an AAR too`() {
        val aar = temp.toFile().resolve("plugincore.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(coreJar.readBytes())
        }
        val core = PluginCoreApi.read(aar)
        assertNotNull(core)
        assertEquals(v120, core!!.version)
        assertEquals(StarlightVersion(1, 0, 1), core.baseline)
        assertTrue("dev/mooner/starlight/plugincore/project/event/ProjectEvent" in core.classes)
    }

    @Test
    fun `knows nothing from a PluginCore without the API file`() {
        val jar = temp.toFile().resolve("old.jar")
        ZipOutputStream(jar.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("a.txt")) }
        assertNull(PluginCoreApi.read(jar))
    }

    @Test
    fun `checks api_version against the usages`() {
        val report = ApiUsageReport(v120, StarlightVersion(1, 0, 1), usages)
        assertEquals(v120, report.required)

        val tooLow = ApiVersionCheck.issues(v110, report).single()
        assertEquals(Severity.ERROR, tooLow.severity)
        assertTrue(tooLow.message.contains("GuardedTooLow.run"), tooLow.message)
        assertTrue(!tooLow.message.contains("Direct.run"), "1.1.0 API is fine with api_version 1.1.0")

        assertEquals(emptyList<Any>(), ApiVersionCheck.issues(v120, report))
        assertNull(ApiVersionCheck.note(v120, report))
        assertTrue(ApiVersionCheck.note(StarlightVersion(1, 3, 0), report)!!.contains("higher than the code needs"))
    }

    @Test
    fun `writes and reads the report`() {
        val file = temp.toFile().resolve("report/api-usage.json")
        ApiUsageReport(v120, StarlightVersion(1, 0, 1), usages).writeTo(file)
        val read = ApiUsageReport.readFrom(file)
        assertEquals(v120, read.pluginCore)
        assertEquals(usages, read.usages)

        ApiUsageReport.UNKNOWN.writeTo(file)
        assertTrue(!ApiUsageReport.readFrom(file).isKnown)
    }

    private fun javac(output: File, sources: Map<String, String>, classpath: File?): File {
        val sourceRoot = output.resolveSibling(output.name + "-src")
        val files = sources.map { (path, code) -> sourceRoot.resolve(path).apply { parentFile.mkdirs(); writeText(code.trimIndent()) } }
        output.mkdirs()
        val options = mutableListOf("-d", output.path, "-g", "--release", "8")
        if (classpath != null)
            options += listOf("-cp", classpath.path)
        val errors = ByteArrayOutputStream()
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, errors, *(options + files.map(File::getPath)).toTypedArray())
        check(result == 0) { "javac failed:\n$errors" }
        return output
    }

    private companion object {

        val CORE_SOURCES = mapOf(
            "dev/mooner/starlight/plugincore/RequiresPluginCore.java" to """
                package dev.mooner.starlight.plugincore;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
                public @interface RequiresPluginCore { String version(); }
            """,
            "dev/mooner/starlight/plugincore/NewThing.java" to """
                package dev.mooner.starlight.plugincore;
                public class NewThing { public NewThing() {} }
            """,
            "dev/mooner/starlight/plugincore/project/event/ProjectEvent.java" to """
                package dev.mooner.starlight.plugincore.project.event;
                public abstract class ProjectEvent {
                    public String getName() { return ""; }
                    public Object[] getScriptArguments() { return new Object[0]; }
                }
            """,
            "dev/mooner/starlight/plugincore/project/ProjectInfoBuilder.java" to """
                package dev.mooner.starlight.plugincore.project;
                public class ProjectInfoBuilder {
                    public void setName(String name) {}
                    public void setAllowedEventIds(java.util.Set<String> ids) {}
                    public void setFuture(String value) {}
                }
            """,
        )

        val CORE_API_JSON = """
            {
              "format": 1,
              "version": "1.2.0",
              "baseline": "1.0.1",
              "classes": {
                "dev/mooner/starlight/plugincore/NewThing": "1.1.0",
                "dev/mooner/starlight/plugincore/RequiresPluginCore": "1.1.0"
              },
              "members": {
                "dev/mooner/starlight/plugincore/NewThing#<init>()V": "1.1.0",
                "dev/mooner/starlight/plugincore/project/event/ProjectEvent#getScriptArguments()[Ljava/lang/Object;": "1.1.0",
                "dev/mooner/starlight/plugincore/project/ProjectInfoBuilder#setAllowedEventIds(Ljava/util/Set;)V": "1.1.0",
                "dev/mooner/starlight/plugincore/project/ProjectInfoBuilder#setFuture(Ljava/lang/String;)V": "1.2.0"
              }
            }
        """.trimIndent()

        /** On one line, so that line 3 of each source is its class declaration. */
        private const val IMPORTS = "import dev.mooner.starlight.plugincore.*; " +
            "import dev.mooner.starlight.plugincore.project.ProjectInfoBuilder; " +
            "import dev.mooner.starlight.plugincore.project.event.ProjectEvent;"

        val PLUGIN_SOURCES = mapOf(
            "test/plugin/Direct.java" to """
                package test.plugin;
                $IMPORTS
                public class Direct {
                    void run(ProjectInfoBuilder b) {
                        b.setName("x");
                        b.setAllowedEventIds(null);
                    }
                }
            """,
            "test/plugin/MyEvent.java" to """
                package test.plugin;
                $IMPORTS
                public class MyEvent extends ProjectEvent {}
            """,
            "test/plugin/ThroughSubclass.java" to """
                package test.plugin;
                $IMPORTS
                public class ThroughSubclass {
                    void run(MyEvent e) { e.getScriptArguments(); }
                }
            """,
            "test/plugin/MethodRef.java" to """
                package test.plugin;
                $IMPORTS
                public class MethodRef {
                    java.util.function.Supplier<Object[]> ref(MyEvent e) { return e::getScriptArguments; }
                }
            """,
            "test/plugin/Overrides.java" to """
                package test.plugin;
                $IMPORTS
                public class Overrides extends ProjectEvent {
                    @Override public Object[] getScriptArguments() { return super.getScriptArguments(); }
                }
            """,
            "test/plugin/CallsOverride.java" to """
                package test.plugin;
                $IMPORTS
                public class CallsOverride {
                    void run(Overrides o) { o.getScriptArguments(); }
                }
            """,
            "test/plugin/Guarded.java" to """
                package test.plugin;
                $IMPORTS
                public class Guarded {
                    @RequiresPluginCore(version = "1.1.0")
                    void run(ProjectInfoBuilder b) {
                        b.setAllowedEventIds(null);
                        Runnable lambda = () -> b.setAllowedEventIds(null);
                        lambda.run();
                        new Object() { void call() { b.setAllowedEventIds(null); } }.call();
                    }
                }
            """,
            "test/plugin/GuardedTooLow.java" to """
                package test.plugin;
                $IMPORTS
                public class GuardedTooLow {
                    @RequiresPluginCore(version = "1.1.0")
                    void run(ProjectInfoBuilder b) { b.setFuture("x"); }
                }
            """,
            "test/plugin/ClassLevel.java" to """
                package test.plugin;
                $IMPORTS
                @RequiresPluginCore(version = "1.1.0")
                public class ClassLevel {
                    void a(ProjectInfoBuilder b) { b.setAllowedEventIds(null); }
                }
            """,
            "test/plugin/UsesNewClass.java" to """
                package test.plugin;
                $IMPORTS
                public class UsesNewClass {
                    Object make() { return new NewThing(); }
                }
            """,
            "test/plugin/ExtendsNew.java" to """
                package test.plugin;
                $IMPORTS
                public class ExtendsNew extends NewThing {}
            """,
            "test/plugin/Baseline.java" to """
                package test.plugin;
                $IMPORTS
                public class Baseline {
                    void run(ProjectInfoBuilder b, MyEvent e) { b.setName("x"); e.getName(); }
                }
            """,
        )
    }
}
