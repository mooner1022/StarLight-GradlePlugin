package dev.mooner.slplugin.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the parts of a DEX file needed to verify a plugin's entry class:
 * class definitions, their superclasses and their constructors.
 * See https://source.android.com/docs/core/runtime/dex-format
 */
internal class DexFile(bytes: ByteArray) {

    class ClassDef(
        /** Type descriptor, e.g. `Lcom/example/MyPlugin;` */
        val descriptor: String,
        val accessFlags: Int,
        /** Superclass type descriptor, or null for java.lang.Object. */
        val superDescriptor: String?,
        internal val classDataOffset: Int,
    ) {
        val isPublic get() = accessFlags and ACC_PUBLIC != 0
        val isAbstract get() = accessFlags and (ACC_ABSTRACT or ACC_INTERFACE) != 0
    }

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private val stringIdsOffset: Int
    private val typeIdsOffset: Int
    private val protoIdsOffset: Int
    private val methodIdsOffset: Int

    val classes: List<ClassDef>

    init {
        require(bytes.size >= HEADER_SIZE && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() && bytes[2] == 'x'.code.toByte()) {
            "Not a DEX file"
        }
        stringIdsOffset = buffer.getInt(0x3C)
        typeIdsOffset = buffer.getInt(0x44)
        protoIdsOffset = buffer.getInt(0x4C)
        methodIdsOffset = buffer.getInt(0x5C)
        val classDefsSize = buffer.getInt(0x60)
        val classDefsOffset = buffer.getInt(0x64)
        classes = List(classDefsSize) { index ->
            val base = classDefsOffset + index * CLASS_DEF_SIZE
            val superIndex = buffer.getInt(base + 8)
            ClassDef(
                descriptor = typeDescriptor(buffer.getInt(base)),
                accessFlags = buffer.getInt(base + 4),
                superDescriptor = if (superIndex == NO_INDEX) null else typeDescriptor(superIndex),
                classDataOffset = buffer.getInt(base + 24),
            )
        }
    }

    /** Whether [classDef] declares a public constructor without parameters (what `Class.newInstance()` needs). */
    fun hasPublicNoArgConstructor(classDef: ClassDef): Boolean {
        if (classDef.classDataOffset == 0)
            return false
        val reader = Reader(classDef.classDataOffset)
        val staticFields = reader.uleb128()
        val instanceFields = reader.uleb128()
        val directMethods = reader.uleb128()
        reader.uleb128() // virtual methods
        repeat((staticFields + instanceFields) * 2) { reader.uleb128() }

        var methodIndex = 0
        repeat(directMethods) {
            methodIndex += reader.uleb128()
            val accessFlags = reader.uleb128()
            reader.uleb128() // code offset
            val methodId = methodIdsOffset + methodIndex * 8
            val protoIndex = buffer.getShort(methodId + 2).toInt() and 0xFFFF
            val name = string(buffer.getInt(methodId + 4))
            val parametersOffset = buffer.getInt(protoIdsOffset + protoIndex * 12 + 8)
            if (name == "<init>" && parametersOffset == 0 && accessFlags and ACC_PUBLIC != 0)
                return true
        }
        return false
    }

    private fun typeDescriptor(typeIndex: Int): String =
        string(buffer.getInt(typeIdsOffset + typeIndex * 4))

    private fun string(stringIndex: Int): String {
        val reader = Reader(buffer.getInt(stringIdsOffset + stringIndex * 4))
        val length = reader.uleb128()
        return reader.mutf8(length)
    }

    private inner class Reader(var position: Int) {

        fun uleb128(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = buffer.get(position++).toInt()
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0)
                    return result
                shift += 7
            }
        }

        /** Decodes a MUTF-8 string of [length] UTF-16 code units. */
        fun mutf8(length: Int): String {
            val chars = CharArray(length)
            for (i in 0 until length) {
                val a = buffer.get(position++).toInt() and 0xFF
                chars[i] = when {
                    a < 0x80 -> a.toChar()
                    a and 0xE0 == 0xC0 -> {
                        val b = buffer.get(position++).toInt() and 0x3F
                        (((a and 0x1F) shl 6) or b).toChar()
                    }
                    else -> {
                        val b = buffer.get(position++).toInt() and 0x3F
                        val c = buffer.get(position++).toInt() and 0x3F
                        (((a and 0x0F) shl 12) or (b shl 6) or c).toChar()
                    }
                }
            }
            return String(chars)
        }
    }

    companion object {
        const val ACC_PUBLIC = 0x1
        const val ACC_INTERFACE = 0x200
        const val ACC_ABSTRACT = 0x400

        private const val HEADER_SIZE = 0x70
        private const val CLASS_DEF_SIZE = 32
        private const val NO_INDEX = -1

        fun descriptorOf(className: String): String =
            "L" + className.replace('.', '/') + ";"
    }
}
