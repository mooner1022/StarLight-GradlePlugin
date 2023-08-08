plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.slplugin)
}

android {
    namespace = "org.sample.app"
    compileSdk = 33
    defaultConfig {
        applicationId = "org.sample.app"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain(11)
}

starlightPlugin {
    outputDirectory.set(File("E:\\IdeaProjects\\android-gradle-plugin-template\\sample\\build\\outputs\\sl2"))
    device {
        address = "192.168.219.164"
        port = 9010
        password = "foobar"
    }
}

dependencies {
    implementation(dependencies.platform(libs.compose.bom))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.bundles.compose)
}
