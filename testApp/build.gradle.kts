plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.slplugin)
}

android {
    namespace = "dev.mooner.slplugin.sample"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        // Used as the plugin version unless starlight.manifest.version is set.
        versionName = "1.0.0"
    }
}

starlight {
    sdk {
        // Build these from the StarLight repository: ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        excludeHostKotlinLibraries = true
    }
    manifest {
        id = "sample"
        name = "Sample Plugin"
        mainClass = "dev.mooner.slplugin.sample.SamplePlugin"
        apiVersion = "0.3.0"
        authors.add("mooner")
        description = "Sample plugin built with io.github.mooner1022.slplugin"
    }
    deploy {
        // Or pass -Pstarlight.device=<serial or ip:port>, or set STARLIGHT_DEVICE.
        // device = "192.168.0.10:5555"
        restartApp = true
    }
}
