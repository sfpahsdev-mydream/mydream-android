plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sfpahsdev.mydream.data.samsunghealth"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:sleep"))
    compileOnly(fileTree(mapOf("dir" to "../../app/libs", "include" to listOf("*.aar"))))
    compileOnly(libs.gson)
    compileOnly(libs.kotlin.parcelize.runtime)
}
