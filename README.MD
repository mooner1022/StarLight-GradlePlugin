# StarLight-GradlePlugin
### Gradle plugin for developing plugins of [StarLight](https://github.com/mooner1022/StarLight)

A StarLight plugin is an Android APK renamed to `.slp`, with a `starlight.json` manifest in its assets.
This plugin builds that file, checks it the same way StarLight checks it when loading plugins, and
deploys it to a device. Problems that StarLight would only reveal at runtime (or silently ignore)
become build errors instead.

- Packages every build variant as `<id>-<variant>[-<abi>].slp`
- Generates `starlight.json` from the build script, or validates a hand-written one
- Verifies that `main_class` is in the APK, extends `StarlightPlugin` and has a public no-arg constructor
- Adds the PluginCore / ConfigDSL SDK as `compileOnly` and flags libraries the host already provides
- Pushes the plugin with adb (USB or wireless debugging), restarts StarLight and streams its logcat

### Requirements
- Gradle 8.11 or newer
- Android Gradle Plugin 8.0 or newer, applied as `com.android.application`
  (tested with AGP 8.0.2 and 8.9.3 on Gradle 8.11.1, and AGP 9.4.1 on Gradle 9.8)
- JDK 17 or newer
- Kotlin plugins: the Kotlin version of the StarLight app (currently 2.4.20), or at least the previous minor version.
  PluginCore is compiled with it, and older Kotlin compilers cannot read its classes. With AGP 9's built-in Kotlin,
  pin the version by adding `id("org.jetbrains.kotlin.android") version "2.4.20" apply false` to the root build script.

### Setup
Kotlin DSL:
```kotlin
plugins {
    id("com.android.application")
    id("io.github.mooner1022.slplugin") version "0.3.1"
}
```
Groovy DSL:
```groovy
plugins {
    id 'com.android.application'
    id 'io.github.mooner1022.slplugin' version '0.3.1'
}
```

### Configuration
```kotlin
starlight {
    sdk {
        // Added as compileOnly. Build them from the StarLight repository with
        // ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        // Leave the Kotlin runtime (stdlib, reflect, coroutines, serialization) out of the .slp.
        // StarLight ships it and always uses its own copy.
        excludeHostKotlinLibraries = true
    }

    // Optional: generates assets/starlight.json. Without this block, src/main/assets/starlight.json is validated.
    manifest {
        id = "my_plugin"
        name = "My Plugin"
        mainClass = "com.example.MyPlugin"
        apiVersion = "1.0.0"                 // PluginCore you build against; StarLight needs at least this version
        authors.add("me")
        description = "What it does"
        category = "utility"                 // messenger, engine, library, utility, devtools or other (the default)
        // version = "1.0.0"                 // defaults to android.defaultConfig.versionName
        // usesNativeLibrary = true          // when the plugin ships .so files
        // assetRevision = 3                 // defaults to a hash of the asset files
        dependency("nodejs")                 // required, any version
        dependency("v8", version = "0.0.3")  // required, same major.minor
        dependency("python", optional = true)
    }

    deploy {
        device = "192.168.0.10:5555"         // adb serial or ip:port; optional with a single device
        // restartApp = true                 // force-stops StarLight after deploying (it has no hot reload)
    }
}
```

### Tasks
| Task | Description |
|---|---|
| `assemble<Variant>Slp` | Builds the variant, validates it and writes `build/outputs/starlight/<variant>/<id>-<variant>[-<abi>].slp` |
| `assembleSlp` | All variants |
| `deploy<Variant>Slp` | Pushes the `.slp` to `/sdcard/StarLight/plugins/<id>.slp`, picking the ABI split that matches the device |
| `restartStarlight` | Force-stops and relaunches StarLight |
| `starlightLogcat` | Streams StarLight's logcat (Ctrl+C to stop) |
| `generate<Variant>StarlightManifest` | Generates `starlight.json` (only with a `manifest { }` block) |
| `verifyStarlightSdk` | Checks that the SDK files exist (runs before every build) |

Typical loop: `gradlew deployDebugSlp -Pstarlight.restartApp=true`, then `gradlew starlightLogcat`.

### Choosing a device
`deploy.device` takes anything `adb -s` accepts. For wireless debugging, pair once with `adb pair <ip>:<port>`
and use the address shown under *Wireless debugging*; the plugin runs `adb connect` for `ip:port` values.
To keep personal addresses out of the build script, pass `-Pstarlight.device=...` or set the
`STARLIGHT_DEVICE` environment variable; both take precedence over the build script.

### What is validated
The checks mirror how StarLight's PluginCore reads and loads plugins:
- `starlight.json` is in `assets/` (`res/raw/` is deprecated) and has no BOM. With an `api_version` of 0.3.x it must
  also start with `{` and end with `}` with no trailing blank lines, because StarLight with PluginCore 0.3.x loads
  such plugins too and rejects anything else
- required keys `id`, `name`, `main_class`, `version`, `api_version`, `authors`, `description` and their types
- versions use `major.minor.patch[.build]`
- `api_version` is within the range StarLight loads: from 0.3.0 up to the host's PluginCore version
  (1.0.0 is the newest this plugin knows, so newer values only produce a warning)
- the id only uses `A-Z`, `a-z`, `0-9`, `.`, `_` and `-`, and is not reserved (`global`, `starlight`, `system`, `core`)
- `category` is a string; unknown categories are reported, because StarLight lists those plugins under `other`
- dependency strings: `id`, `id@1.2.3`, `id@*?`. An optional dependency without a version (`id?`) is reported,
  because the host keeps the `?` in the id and never matches it
- unknown keys are reported; `depend` is an error, because the host ignores it and drops the dependencies
- the main class exists in the APK, is a public non-abstract subclass of `StarlightPlugin` and has a public no-arg constructor
- `uses_native_lib` matches whether the APK contains `.so` files
- warnings for bundled copies of libraries the host provides (PluginCore, ConfigDSL, Kotlin, core AndroidX, Material)

Notes:
- With a hand-written manifest, bump `assetRevision` yourself when assets change; StarLight only re-extracts
  assets when it changes.
- If you enable minification, keep the main class: `-keep class com.example.MyPlugin { public <init>(); }`.
  With `excludeHostKotlinLibraries`, R8 may also need `-dontwarn kotlin.**`.
- StarLight does not verify signatures, so unsigned release builds work.
- When several `.slp` files have the same id, StarLight loads the one with the highest `version`. The deploy task
  writes `<id>.slp`, so delete older copies saved under other names.

### Migrating from 0.2.0
- An id with characters other than `A-Z`, `a-z`, `0-9`, `.`, `_` and `-` is now an error (it was a warning),
  because StarLight with PluginCore 1.0.0 refuses to load such plugins.
- `api_version` is checked against the range StarLight loads (0.3.0 up to the host's PluginCore) instead of the
  host's major.minor, so `1.0.0` no longer produces a warning.
- Optionally add `category` to the `manifest { }` block.

### Migrating from 0.1.0
- The extension is now `starlight { }` instead of `starlightPlugin { }`.
- `buildPlugin` is replaced by `assembleDebugSlp` (and `assemble<Variant>Slp` for other variants). The output is
  `build/outputs/starlight/<variant>/<id>-<variant>.slp` instead of `build/outputs/starlight/plugin.slp`.
- `buildAndPushPlugin` and `device { host / port / password }` are replaced by `deploy<Variant>Slp` and
  `deploy { device }`, which use adb. The old task only pinged an HTTP endpoint that no StarLight build provides.
- Move `res/raw/starlight.json` to `src/main/assets/`, or replace it with a `manifest { }` block.
- Rename `depend` to `dependency` in existing manifests, or the build fails.
- If the device still has a `plugin.slp` from 0.1.0, delete it; the deploy task warns about it.

### Developing this plugin
```
./gradlew -p plugin check              # unit + functional tests (functional tests need an Android SDK)
./gradlew :testApp:assembleDebugSlp    # sample plugin, using the plugin from source
```
Publishing (`./gradlew -p plugin publishPlugins`) signs the artifacts. Put `gradle.publish.key`/`secret` and the
`signing.*` properties in `~/.gradle/gradle.properties`, or provide `SIGNING_PGP_KEY` and `SIGNING_PGP_PASSWORD`
as environment variables on CI.

###### This plugin is made in basis of [android-gradle-plugin-template](https://github.com/irgaly/android-gradle-plugin-template)
