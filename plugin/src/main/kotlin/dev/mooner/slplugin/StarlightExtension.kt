package dev.mooner.slplugin

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * ```
 * starlight {
 *     sdk { pluginCore = file("libs/PluginCore-release.aar") }
 *     manifest { id = "my_plugin"; ... }   // omit to validate src/main/assets/starlight.json instead
 *     deploy { device = "192.168.0.10:5555" }
 * }
 * ```
 */
abstract class StarlightExtension @Inject constructor(objects: ObjectFactory) {

    val sdk: SdkSpec = objects.newInstance(SdkSpec::class.java)

    val manifest: ManifestSpec = objects.newInstance(ManifestSpec::class.java)

    val deploy: DeploySpec = objects.newInstance(DeploySpec::class.java)

    private var manifestBlockUsed = false

    /** Whether `starlight.json` is generated from [manifest] rather than written by hand. */
    internal fun generatesManifest(): Boolean = manifestBlockUsed

    fun sdk(action: Action<in SdkSpec>) =
        action.execute(sdk)

    /** Generates `assets/starlight.json` from this block instead of using a hand-written file. */
    fun manifest(action: Action<in ManifestSpec>) {
        manifestBlockUsed = true
        action.execute(manifest)
    }

    fun deploy(action: Action<in DeploySpec>) =
        action.execute(deploy)
}

abstract class SdkSpec {

    /** `PluginCore-release.aar`, added as a compileOnly dependency. */
    abstract val pluginCore: RegularFileProperty

    /** `ConfigDSL-release.aar`, added as a compileOnly dependency. PluginCore's API depends on it. */
    abstract val configDsl: RegularFileProperty

    /**
     * Leaves the Kotlin runtime (stdlib, reflect, coroutines, serialization) out of the `.slp`.
     * StarLight already ships these and always uses its own copies.
     */
    abstract val excludeHostKotlinLibraries: Property<Boolean>

    init {
        excludeHostKotlinLibraries.convention(false)
    }
}

abstract class ManifestSpec {

    abstract val id: Property<String>

    abstract val name: Property<String>

    /** Fully qualified name of the class extending `StarlightPlugin`. */
    abstract val mainClass: Property<String>

    /** Defaults to `android.defaultConfig.versionName`. Must be `major.minor.patch[.build]`. */
    abstract val version: Property<String>

    /** PluginCore API version the plugin is built against, e.g. `0.3.0`. */
    abstract val apiVersion: Property<String>

    abstract val authors: ListProperty<String>

    abstract val description: Property<String>

    /** Raw StarLight dependency strings (`id`, `id@1.0.0`, `id@*?`). Prefer [dependency]. */
    abstract val dependencies: ListProperty<String>

    abstract val usesNativeLibrary: Property<Boolean>

    /** StarLight re-extracts plugin assets when this changes. Defaults to a hash of the asset files. */
    abstract val assetRevision: Property<Int>

    abstract val customClassLoader: Property<String>

    init {
        usesNativeLibrary.convention(false)
    }

    /**
     * Declares a dependency on another StarLight plugin.
     * @param version required version (only its major.minor is checked by StarLight), or `*` for any
     * @param optional optional dependencies only affect load order
     */
    @JvmOverloads
    fun dependency(id: String, version: String = "*", optional: Boolean = false) {
        dependencies.add(
            when {
                optional -> "$id@$version?"
                version == "*" -> id
                else -> "$id@$version"
            }
        )
    }
}

abstract class DeploySpec {

    /**
     * adb serial or `host:port` for wireless debugging. Can be overridden with `-Pstarlight.device=...`
     * or the `STARLIGHT_DEVICE` environment variable. Optional when exactly one device is connected.
     */
    abstract val device: Property<String>

    /** Restart StarLight after deploying so it loads the new plugin. Override with `-Pstarlight.restartApp=true`. */
    abstract val restartApp: Property<Boolean>

    abstract val appPackage: Property<String>

    abstract val remoteDirectory: Property<String>

    init {
        restartApp.convention(false)
        appPackage.convention("dev.mooner.starlight")
        remoteDirectory.convention("/sdcard/StarLight/plugins")
    }
}
