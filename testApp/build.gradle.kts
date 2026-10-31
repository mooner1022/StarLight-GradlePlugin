plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.slplugin)
}

android {
    namespace = "dev.mooner.slplugin.sample"
    // SDK levels and bytecode target follow the StarLight app. (Build tools are left to AGP: 9.x requires 36+.)
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Used as the plugin version unless starlight.manifest.version is set.
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
        apiVersion = "0.3.2"
        authors.add("mooner")
        description = "Sample plugin built with io.github.mooner1022.slplugin"
        category = "devtools"
    }
    deploy {
        // Or pass -Pstarlight.device=<serial or ip:port>, or set STARLIGHT_DEVICE.
        // device = "192.168.0.10:5555"
        // Restarting force-stops StarLight on the device; opt in per run with -Pstarlight.restartApp=true.
        // restartApp = true
    }
}
