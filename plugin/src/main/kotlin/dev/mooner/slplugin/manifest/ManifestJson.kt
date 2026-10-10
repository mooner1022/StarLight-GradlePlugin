package dev.mooner.slplugin.manifest

import groovy.json.JsonParserType
import groovy.json.JsonSlurper

/** Reads and writes `starlight.json` without pulling a JSON library onto the consumer's build classpath. */
internal object ManifestJson {

    fun parse(text: String): Any? =
        JsonSlurper().parseText(text)

    /** Accepts unquoted keys and strings like the host's parser (`isLenient = true`), but also some broken JSON. */
    fun parseLenient(text: String): Any? =
        JsonSlurper().setType(JsonParserType.LAX).parseText(text)

    /**
     * Pretty-prints [value] with `\n` line endings and no trailing newline. The host rejects a manifest
     * that does not end with `}` once its lines are joined, so nothing may follow the closing brace.
     */
    fun write(value: Any?): String =
        StringBuilder().also { it.appendValue(value, "") }.toString()

    private fun StringBuilder.appendValue(value: Any?, indent: String) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Number, is Boolean -> append(value.toString())
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    append("{}")
                    return
                }
                append("{\n")
                value.entries.forEachIndexed { index, (key, entryValue) ->
                    append(indent).append("  ")
                    appendString(key.toString())
                    append(": ")
                    appendValue(entryValue, "$indent  ")
                    if (index < value.size - 1)
                        append(',')
                    append('\n')
                }
                append(indent).append('}')
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) {
                    append("[]")
                    return
                }
                append("[\n")
                items.forEachIndexed { index, item ->
                    append(indent).append("  ")
                    appendValue(item, "$indent  ")
                    if (index < items.size - 1)
                        append(',')
                    append('\n')
                }
                append(indent).append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (char < ' ')
                        append("\\u").append(String.format("%04x", char.code))
                    else
                        append(char)
            }
        }
        append('"')
    }
}
