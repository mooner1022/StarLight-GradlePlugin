# StarLight-GradlePlugin
### Gradle plugin for developing plugins of [StarLight](https://github.com/mooner1022/StarLight)

A StarLight plugin is an Android APK renamed to `.slp`, with a `starlight.json` manifest in its assets.
This plugin builds that file, checks it the same way StarLight checks it when loading plugins, and
deploys it to a device. Problems that StarLight would only reveal at runtime (or silently ignore)
become build errors instead.

- Packages every build variant as `<id>-<variant>[-<abi>].slp`
- Generates `starlight.json` from the build script, or validates a hand-written one
- Works out `api_version` from the PluginCore API the code uses, and fails the build when a declared one is too low
- Verifies that `main_class` is in the APK, extends `StarlightPlugin` and has a public no-arg constructor
- With KSP, puts the plugin's KDoc into the package, so StarLight AI knows the plugin's script API
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
    id("io.github.mooner1022.slplugin") version "0.5.1"
}
```
Groovy DSL:
```groovy
plugins {
    id 'com.android.application'
    id 'io.github.mooner1022.slplugin' version '0.5.1'
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
        // apiVersion = "1.0.0"              // lowest PluginCore the plugin needs; worked out from the code when unset
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
| `scan<Variant>StarlightApiUsage` | Finds the PluginCore API the code uses, for `api_version` (runs as part of the above) |
| `extractStarlightDocsProcessor` | Extracts the docs processor for KSP (only with KSP applied, runs as part of the build) |
| `verifyStarlightSdk` | Checks that the SDK files exist (runs before every build) |

Typical loop: `gradlew deployDebugSlp -Pstarlight.restartApp=true`, then `gradlew starlightLogcat`.

### Choosing a device
`deploy.device` takes anything `adb -s` accepts. For wireless debugging, pair once with `adb pair <ip>:<port>`
and use the address shown under *Wireless debugging*; the plugin runs `adb connect` for `ip:port` values.
To keep personal addresses out of the build script, pass `-Pstarlight.device=...` or set the
`STARLIGHT_DEVICE` environment variable; both take precedence over the build script.

### api_version and older StarLight versions
StarLight loads a plugin only when its PluginCore is at least the plugin's `api_version`, and a plugin that calls API its
host doesn't have fails when that code runs. So `api_version` should be the lowest PluginCore the code actually needs,
not the one it is built against. With PluginCore 1.1.0 or later, whose AAR records which version added each class and
member (`starlight/plugincore-api.json`), this plugin works it out:

- It reads the module's compiled classes and finds the PluginCore API they use, also through subclasses of your own.
  Overriding a newer PluginCore method doesn't count: an older host just doesn't call it.
- Without `apiVersion`, the generated manifest gets the newest version among that API, or, when the code uses nothing
  newer, the oldest release the PluginCore records (1.0.1).
- With `apiVersion`, or a hand-written manifest, the build fails when the code uses API newer than it, naming each use.
- `target_api_version` names the PluginCore you build against. A generated manifest gets it. A hand-written one must
  have it, or the build fails and names the value to add. StarLight doesn't read it yet; it is for applying a behavior
  change only to the plugins that target the version that made it.

To use newer API and still load on older hosts, check the version first and mark the code that runs only on newer hosts
with `@RequiresPluginCore`. What it uses, up to that version, is left out, with the lambdas and local classes inside it:
```kotlin
override fun onEnable() {
    if (Info.PLUGINCORE_VERSION >= Version(1, 1, 0))   // Info and Version exist in every PluginCore
        createProject()
}

@RequiresPluginCore("1.1.0")
private fun createProject() = Session.projectManager.newProject(events = events) {
    name = "my-bot"; mainScript = "my-bot.js"; languageId = "js_neon"
    allowedEventIds = setOf("my_plugin.*")             // added in PluginCore 1.1.0
}
```
Only the plugin module's own classes are read: mark code in other modules, or calls made through reflection, yourself.
With an older PluginCore AAR nothing can be worked out, and `apiVersion` stays required.

### Docs for StarLight AI
StarLight AI, the app's assistant, writes scripts from what the plugins loaded on the device offer. It always sees a
plugin's APIs and events from reflection: their names, members and types. With docs, it also sees what they do.
These are the docs the StarLight app has for its own API, made the same way, and StarLight with PluginCore 1.1.0 or
later reads them from the plugin's package.

- **KDoc:** apply KSP, and this plugin runs StarLight's docs processor with it. The processor writes the KDoc and Kotlin
  signatures of the module's public classes to `starlight/agent/<id>.api.json` in the APK:
  ```kotlin
  plugins {
      id("com.android.application")
      id("com.google.devtools.ksp") version "2.3.13"     // needs AGP 8.12 or newer
      id("io.github.mooner1022.slplugin") version "0.5.1"
  }
  ```
  KSP is left to your build, because each KSP release supports a range of AGP versions and this plugin supports
  AGP 8.0 and later. Plugins without KSP build as before, without docs.
- **Guides:** Markdown under `src/main/assets/agent/guides/`, for what belongs to no single class: setup, concepts,
  limits. Each starts with front matter, and the agent opens one as `<plugin id>:<path without .md>`:
  ```markdown
  ---
  title: Setup
  summary: Connecting the bot: the token, intents and the setting that turns it on.
  ---
  ```

Write the KDoc for someone writing a script. The first sentence is the summary the agent sees in lists; the paragraphs
after it are shown in full descriptions. Document each parameter with `@param`, and the arguments a script's event
function receives on the `ProjectEvent` class, one `@param` each. `@suppress` hides a public member from the agent.
`@see guide:<id>` links one of the plugin's guides (`guide:starlight:<id>` one of the app's), and so does a Markdown
link to `guide:<id>` in a guide.

```kotlin
/**
 * Sends messages to Discord channels; a global in every project while the plugin is loaded.
 *
 * Messages over 2,000 characters are split. Fails while the bot is offline, see guide:setup.
 *
 * @see guide:setup
 */
class DiscordApi {
    /**
     * Sends [text] to a channel; whether Discord accepted it.
     *
     * @param channelId The channel's ID, from Discord's developer mode.
     * @param text The message, in Discord's Markdown.
     */
    fun send(channelId: Long, text: String): Boolean = …
}
```

The build reports what the package has, and the guides StarLight would skip:
```
i: [StarLight] StarLight AI docs: 3 classes in starlight/agent/my_plugin.api.json, 1 guide (my_plugin:setup)
```
Notes:
- Docs are matched to classes and members by their names, so with minification keep the classes scripts use, as
  scripts need them by name anyway.
- The plugin also compiles with parameter names (`-parameters`, Kotlin's `-java-parameters`), which the agent shows for
  members without docs.

### What is validated
The checks mirror how StarLight's PluginCore reads and loads plugins:
- `starlight.json` is in `assets/` (`res/raw/` is deprecated) and has no BOM. With an `api_version` of 0.3.x it must
  also start with `{` and end with `}` with no trailing blank lines, because StarLight with PluginCore 0.3.x loads
  such plugins too and rejects anything else
- required keys `id`, `name`, `main_class`, `version`, `api_version`, `authors`, `description` and their types
- versions use `major.minor.patch[.build]`
- `api_version` is within the range StarLight loads: from 0.3.0 up to the PluginCore you build against
  (1.1.0 when its AAR doesn't say; newer values only produce a warning)
- `api_version` is not lower than the PluginCore API the code uses (see above); `target_api_version` is a version
  no older than `api_version`, and a hand-written manifest has it when the PluginCore records its version (1.1.0 or later)
- the id only uses `A-Z`, `a-z`, `0-9`, `.`, `_` and `-`, and is not reserved (`global`, `starlight`, `system`, `core`)
- `category` is a string; unknown categories are reported, because StarLight lists those plugins under `other`
- dependency strings: `id`, `id@1.2.3`, `id@*?`. An optional dependency without a version (`id?`) is reported,
  because the host keeps the `?` in the id and never matches it
- unknown keys are reported; `depend` is an error, because the host ignores it and drops the dependencies
- the docs for StarLight AI are where StarLight reads them (`starlight/agent/<id>.api.json`, format 1), and each guide
  has front matter with a `title` and a `summary`
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

### Migrating from 0.5.0
- With PluginCore 1.1.0 or later, a hand-written `starlight.json` must have `target_api_version`, the PluginCore you build
  against, or the build fails: add `"target_api_version": "1.1.0"`, or the version the error names. Generated manifests
  already have it.

### Migrating from 0.4.x
- Nothing to change. Apply KSP to add docs for StarLight AI (see above).
- Java and Kotlin code is now compiled with parameter names.

### Migrating from 0.3.x
- With PluginCore 1.1.0 or later, the build fails when the code uses PluginCore API newer than `api_version`. Raise it,
  or mark the code as above. `apiVersion` may now be left out of the `manifest { }` block.
- Generated manifests get `target_api_version`; it is also accepted in hand-written ones.
- `api_version` 1.1.0 no longer produces a warning.

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
