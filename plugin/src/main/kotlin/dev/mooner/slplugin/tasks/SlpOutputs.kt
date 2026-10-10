package dev.mooner.slplugin.tasks

import dev.mooner.slplugin.manifest.ManifestJson
import java.io.File

/** Describes the `.slp` files of one variant, so the deploy task can pick the right one for a device. */
internal data class SlpOutputs(
    val id: String,
    val version: String,
    val variant: String,
    val outputs: List<Output>,
) {
    data class Output(val file: String, val abi: String?)

    fun writeTo(directory: File) {
        val json = linkedMapOf(
            "id" to id,
            "version" to version,
            "variant" to variant,
            "outputs" to outputs.map { linkedMapOf("file" to it.file, "abi" to it.abi) },
        )
        File(directory, FILE_NAME).writeText(ManifestJson.write(json), Charsets.UTF_8)
    }

    companion object {
        const val FILE_NAME = "starlight-outputs.json"

        fun readFrom(directory: File): SlpOutputs {
            val json = ManifestJson.parse(File(directory, FILE_NAME).readText(Charsets.UTF_8)) as Map<*, *>
            return SlpOutputs(
                id = json["id"] as String,
                version = json["version"] as String,
                variant = json["variant"] as String,
                outputs = (json["outputs"] as List<*>).map { output ->
                    output as Map<*, *>
                    Output(output["file"] as String, output["abi"] as String?)
                },
            )
        }
    }
}
