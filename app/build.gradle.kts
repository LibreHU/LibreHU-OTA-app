import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Version from git: versionName = `git describe` (rolling release tags excluded), versionCode = number of commits.
fun git(vararg args: String): String? =
    try {
        val out = ByteArrayOutputStream()
        val p = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.copyTo(out)
        if (p.waitFor() == 0) out.toString().trim() else null
    } catch (_: Exception) {
        null
    }

android {
    namespace = "org.librehu.ota"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.librehu.ota"
        // The UJC201 runs Android 9.
        minSdk = 28
        targetSdk = 35
        versionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = git("describe", "--tags", "--always", "--dirty", "--exclude", "latest-*") ?: "dev"
    }
    // One signing key for every LibreHU app, so that updates (LibreHU OTA) install over each other: LIBREHU_KEYSTORE*
    // from the CI secrets when set, else keystore/librehu.jks of the repository.
    signingConfigs {
        create("librehu") {
            storeFile = System.getenv("LIBREHU_KEYSTORE")?.let { file(it) } ?: rootProject.file("keystore/librehu.jks")
            storePassword = System.getenv("LIBREHU_KEYSTORE_PASSWORD") ?: "librehu"
            keyAlias = System.getenv("LIBREHU_KEY_ALIAS") ?: "librehu"
            keyPassword = System.getenv("LIBREHU_KEY_PASSWORD") ?: "librehu"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("librehu") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Head unit integration may use permissions granted to privileged installs only.
        disable += setOf("ProtectedPermissions", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
}
