package dev.mooner.slplugin.api

import dev.mooner.slplugin.manifest.ManifestJson
import dev.mooner.slplugin.manifest.StarlightVersion
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The shape of a class: its supertypes and the members it declares, keyed as in `plugincore-api.json`: methods as
 * `name(parameters)return` and fields as `name:type`, with JVM descriptors.
 */
internal class ClassShape(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val members: Set<String>,
)

/**
 * What a PluginCore build says about its API: the version that added each class and member newer than its baseline,
 * from `starlight/plugincore-api.json`, and the shape of its classes, to find which class declares a member a plugin
 * reaches through a subclass. The json's format is shared with StarLight's `generateApiSince` task
 * (StarLight's docs/plugincore-compatibility.md).
 */
internal class PluginCoreApi(
    /** The PluginCore version this is, which plugins built against it target. */
    val version: StarlightVersion,
    /** The oldest release the file records; nothing is known of what came before it. */
    val baseline: StarlightVersion,
    private val classSince: Map<String, StarlightVersion>,
    private val memberSince: Map<String, StarlightVersion>,
    val classes: Map<String, ClassShape>,
) {

    /** The version that added the class [name], or null when the baseline has it or it isn't PluginCore's. */
    fun sinceOfClass(name: String): StarlightVersion? = classSince[name]

    /** The version that added [member] of the class [declaringClass], or null when the baseline has it. */
    fun sinceOfMember(declaringClass: String, member: String): StarlightVersion? = memberSince["$declaringClass#$member"]

    companion object {

        const val API_FILE = "starlight/plugincore-api.json"
        const val FORMAT = 1
        private const val PACKAGE = "dev/mooner/starlight/plugincore/"

        /**
         * Reads [file], a PluginCore AAR or jar. Null when it has no `plugincore-api.json` (PluginCore before 1.1.0, or a
         * stand-in), in which case nothing can be worked out from it.
         */
        fun read(file: File): PluginCoreApi? {
            var json: String? = null
            val classes = mutableMapOf<String, ClassShape>()

            fun readJar(input: InputStream) {
                val zip = ZipInputStream(input)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.isDirectory -> {}
                        entry.name == API_FILE -> json = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith(PACKAGE) && entry.name.endsWith(".class") ->
                            shapeOf(zip.readBytes()).let { classes[it.name] = it }
                    }
                }
            }

            file.inputStream().buffered().use { input ->
                val zip = ZipInputStream(input)
                var isAar = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "classes.jar") {
                        isAar = true
                        readJar(ByteArrayInputStream(zip.readBytes()))
                    }
                }
                if (!isAar)
                    file.inputStream().buffered().use(::readJar)
            }
            return json?.let { parse(it, classes) }
        }

        internal fun parse(json: String, classes: Map<String, ClassShape>): PluginCoreApi {
            val root = ManifestJson.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("$API_FILE is not a JSON object")
            val format = (root["format"] as? Number)?.toInt()
            require(format == FORMAT) {
                "$API_FILE has format $format, but this Gradle plugin reads format $FORMAT. Update the StarLight Gradle plugin."
            }
            fun version(key: String) = (root[key] as? String)?.let(StarlightVersion::parseOrNull)
                ?: throw IllegalArgumentException("$API_FILE has no valid '$key'")
            fun versions(key: String): Map<String, StarlightVersion> =
                (root[key] as? Map<*, *>).orEmpty().entries.associate { (name, since) ->
                    name.toString() to (since?.toString()?.let(StarlightVersion::parseOrNull)
                        ?: throw IllegalArgumentException("$API_FILE: '$name' has no valid version"))
                }
            return PluginCoreApi(version("version"), version("baseline"), versions("classes"), versions("members"), classes)
        }

        /** Reads the shape of a class, without its code. */
        fun shapeOf(bytes: ByteArray): ClassShape {
            val members = mutableSetOf<String>()
            var name = ""
            var superName: String? = null
            var interfaces = emptyList<String>()
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, className: String, signature: String?, superClass: String?, superInterfaces: Array<out String>?) {
                    name = className
                    superName = superClass
                    interfaces = superInterfaces?.toList().orEmpty()
                }

                override fun visitField(access: Int, fieldName: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    members += "$fieldName:$descriptor"
                    return null
                }

                override fun visitMethod(access: Int, methodName: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    members += "$methodName$descriptor"
                    return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return ClassShape(name, superName, interfaces, members)
        }
    }
}
