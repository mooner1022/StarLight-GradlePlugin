package dev.mooner.slplugin.api

import dev.mooner.slplugin.manifest.StarlightVersion
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** A use of PluginCore API newer than the baseline. */
internal data class ApiUsage(
    /** Where the plugin uses it, such as `com.example.MyPlugin.onEnable (MyPlugin.kt:42)`. */
    val location: String,
    /** What it uses, such as `ProjectInfoBuilder.setAllowedEventIds(Set)`. */
    val api: String,
    /** The PluginCore version that added it. */
    val since: StarlightVersion,
)

/**
 * Finds the PluginCore API a plugin's compiled classes use that is newer than [core]'s baseline, so the plugin's
 * `api_version` can be the lowest PluginCore that has all of it.
 *
 * Counted: calls, field reads and writes, method references, supertypes and type uses (`new`, casts, `instanceof`,
 * class literals) of PluginCore classes and members, also through a subclass of the plugin's own.
 *
 * Not counted:
 * - overriding a PluginCore method, which an older host just doesn't call;
 * - an override's `super` call to the method it overrides, which runs only when the override is called;
 * - what code marked `@RequiresPluginCore(version)` uses up to that version, with the lambdas and local classes inside it.
 */
internal class ApiUsageScanner(private val core: PluginCoreApi) {

    /** A class, or a member when [name] is set, the plugin's code reaches. */
    private class Reference(
        val owner: String,
        val name: String?,
        val descriptor: String?,
        val isField: Boolean,
        val line: Int,
    )

    private class Method(val name: String, val descriptor: String, access: Int) {
        val key get() = name + descriptor
        var requires: StarlightVersion? = null
        val references = mutableListOf<Reference>()
        /** The methods of the same class its `invokedynamic`s point at, keyed as [key]: lambda bodies or method references. */
        val lambdaBodies = mutableListOf<String>()

        /**
         * Whether this is a lambda's body, which runs only through the lambda: Java's are synthetic (`lambda$run$0`),
         * Kotlin's are named `run$lambda$0`. A method a method reference points at (`this::helper`) is neither, and may
         * also be called directly.
         */
        val isLambdaBody = access and Opcodes.ACC_SYNTHETIC != 0 || "\$lambda\$" in name
    }

    private class ScannedClass(val shape: ClassShape) {
        val name get() = shape.name
        var requires: StarlightVersion? = null
        var sourceFile: String? = null
        /** For a local or anonymous class: the class and method (`name+descriptor`, or null in an initializer) it is in. */
        var enclosingClass: String? = null
        var enclosingMethod: String? = null
        val methods = linkedMapOf<String, Method>()
    }

    fun scan(classFiles: Sequence<ByteArray>): List<ApiUsage> {
        val classes = classFiles.map(::read).associateBy { it.name }
        // A lambda's body is a method of the same class; it counts as part of the method that creates the lambda.
        val lambdaCreator = mutableMapOf<String, String>()
        for (scanned in classes.values)
            for (method in scanned.methods.values)
                for (body in method.lambdaBodies)
                    if (scanned.methods[body]?.isLambdaBody == true)
                        lambdaCreator["${scanned.name}#$body"] = method.key

        fun requirementOf(className: String, methodKey: String?, depth: Int = 0): StarlightVersion? {
            if (depth > MAX_NESTING)
                return null
            val scanned = classes[className] ?: return null
            val method = methodKey?.let(scanned.methods::get)
            method?.requires?.let { return it }
            if (method != null)
                lambdaCreator["$className#${method.key}"]?.let { creator ->
                    requirementOf(className, creator, depth + 1)?.let { return it }
                }
            scanned.requires?.let { return it }
            val enclosing = scanned.enclosingClass ?: return null
            return requirementOf(enclosing, scanned.enclosingMethod, depth + 1)
        }

        val usages = linkedSetOf<ApiUsage>()
        for (scanned in classes.values) {
            val classRequirement = requirementOf(scanned.name, null)
            for (supertype in listOfNotNull(scanned.shape.superName) + scanned.shape.interfaces) {
                val since = core.sinceOfClass(supertype) ?: continue
                if (classRequirement == null || since > classRequirement)
                    usages += ApiUsage(location(scanned, null, 0), simpleName(supertype), since)
            }
            for (method in scanned.methods.values) {
                val requirement = requirementOf(scanned.name, method.key)
                for (reference in method.references) {
                    val (api, since) = resolve(reference, classes) ?: continue
                    if (requirement == null || since > requirement)
                        usages += ApiUsage(location(scanned, method, reference.line), api, since)
                }
            }
        }
        return usages.sortedWith(compareByDescending<ApiUsage> { it.since }.thenBy { it.location })
    }

    /**
     * What [reference] reaches in PluginCore and the version that added it; null when that is older than the baseline,
     * the plugin's own, or not PluginCore's. A member is looked up from its owner through the supertypes, the plugin's
     * classes and PluginCore's, as the runtime resolves it.
     */
    private fun resolve(reference: Reference, classes: Map<String, ScannedClass>): Pair<String, StarlightVersion>? {
        val name = reference.name
            ?: return core.sinceOfClass(reference.owner)?.let { simpleName(reference.owner) to it }
        val member = if (reference.isField) "$name:${reference.descriptor}" else "$name${reference.descriptor}"
        val queue = ArrayDeque(listOf(reference.owner))
        val seen = hashSetOf<String>()
        while (queue.isNotEmpty()) {
            val className = queue.removeFirst()
            if (!seen.add(className))
                continue
            val shape = classes[className]?.shape ?: core.classes[className] ?: continue
            if (member in shape.members) {
                if (className in classes)
                    return null
                val since = core.sinceOfMember(className, member) ?: core.sinceOfClass(className) ?: return null
                return describe(className, name, reference.descriptor, reference.isField) to since
            }
            shape.superName?.let(queue::addLast)
            queue.addAll(shape.interfaces)
        }
        // A member of java.lang.Object, say, on a class PluginCore added.
        return core.sinceOfClass(reference.owner)?.let { simpleName(reference.owner) to it }
    }

    private fun read(bytes: ByteArray): ScannedClass {
        val scanned = ScannedClass(PluginCoreApi.shapeOf(bytes))
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {

            override fun visitSource(source: String?, debug: String?) {
                scanned.sourceFile = source
            }

            override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
                scanned.enclosingClass = owner
                scanned.enclosingMethod = if (name != null && descriptor != null) name + descriptor else null
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                requirementVisitor(descriptor) { scanned.requires = it }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val method = Method(name, descriptor, access)
                scanned.methods[method.key] = method
                return ReferenceCollector(scanned.name, method)
            }
        }, ClassReader.SKIP_FRAMES)
        return scanned
    }

    private inner class ReferenceCollector(private val className: String, private val method: Method) : MethodVisitor(Opcodes.ASM9) {

        private var line = 0

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            requirementVisitor(descriptor) { method.requires = it }

        override fun visitLineNumber(line: Int, start: Label) {
            this.line = line
        }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            // An override calling the method it overrides: runs only when the host calls the override.
            if (opcode == Opcodes.INVOKESPECIAL && name != "<init>" && name == method.name && descriptor == method.descriptor)
                return
            addMember(owner, name, descriptor, isField = false)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
            addMember(owner, name, descriptor, isField = true)

        override fun visitTypeInsn(opcode: Int, type: String) =
            addClass(type)

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) =
            addClass(descriptor)

        override fun visitLdcInsn(value: Any?) {
            when (value) {
                is Type -> if (value.sort == Type.OBJECT || value.sort == Type.ARRAY) addClass(value.descriptor)
                is Handle -> addHandle(value)
            }
        }

        override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any?) {
            for (argument in bootstrapMethodArguments) {
                if (argument !is Handle)
                    continue
                if (argument.owner == className)
                    method.lambdaBodies += argument.name + argument.desc
                else
                    addHandle(argument)
            }
        }

        private fun addHandle(handle: Handle) =
            addMember(handle.owner, handle.name, handle.desc, isField = handle.tag <= Opcodes.H_PUTSTATIC)

        private fun addMember(owner: String, name: String, descriptor: String, isField: Boolean) {
            if (!owner.startsWith("["))
                method.references += Reference(owner, name, descriptor, isField, line)
        }

        /** [type] is an internal name, or an array or object descriptor. */
        private fun addClass(type: String) {
            val element = type.trimStart('[')
            val name = when {
                element.startsWith("L") && element.endsWith(";") -> element.substring(1, element.length - 1)
                type.startsWith("[") -> return
                else -> element
            }
            method.references += Reference(name, null, null, isField = false, line = line)
        }
    }

    /** Reads `@RequiresPluginCore(version)` and hands its version to [onVersion]; null for other annotations. */
    private fun requirementVisitor(descriptor: String, onVersion: (StarlightVersion) -> Unit): AnnotationVisitor? {
        if (descriptor != REQUIRES_PLUGIN_CORE)
            return null
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                if (name == "version")
                    (value as? String)?.let(StarlightVersion::parseOrNull)?.let(onVersion)
            }
        }
    }

    private fun location(scanned: ScannedClass, method: Method?, line: Int): String {
        val where = buildString {
            append(scanned.name.replace('/', '.'))
            if (method != null)
                append('.').append(method.name)
        }
        val source = scanned.sourceFile ?: return where
        return if (line > 0) "$where ($source:$line)" else "$where ($source)"
    }

    private fun describe(owner: String, name: String, descriptor: String?, isField: Boolean): String {
        val type = simpleName(owner)
        if (isField || descriptor == null)
            return "$type.$name"
        val parameters = Type.getArgumentTypes(descriptor).joinToString { simpleName(it.className.replace('.', '/')) }
        return if (name == "<init>") "new $type($parameters)" else "$type.$name($parameters)"
    }

    private fun simpleName(internalName: String) =
        internalName.substringAfterLast('/').replace('$', '.')

    private companion object {
        const val REQUIRES_PLUGIN_CORE = "Ldev/mooner/starlight/plugincore/RequiresPluginCore;"
        /** Bounds following enclosing code, as a guard against cycles. */
        const val MAX_NESTING = 64
    }
}
