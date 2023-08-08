# StarLight-GradlePlugin
### Gradle Plugin for developing plugins of [StarLight](https://github.com/mooner1022/StarLight)

### Setup
Kotlin DSL:
```kotlin
plugins {
    id("io.github.mooner1022.slplugin") version "0.1.0"
}
```
Groovy DSL:
```groovy
plugins {
    id 'io.github.mooner1022.slplugin' version '0.1.0'
}
```

### Build configuration
Kotlin DSL:
```kotlin
starlightPlugin {
    // Optional
    outputDirectory.set(File("FULL_PATH_TO_DIRECTORY"))
    
    // Mandatory if using task: buildAndPushPlugin
    device {
        host = "192.168.219.164"
        port = 9010
        password = "foobar"
    }
}
```

### Building plugin
Run `gradlew buildPlugin`

### Testing the plugin
Run `gradlew buildAndPushPlugin`

**`device` should be configured to use this task*

###### This plugin is made in basis of [android-gradle-plugin-template](https://github.com/irgaly/android-gradle-plugin-template)