import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version lives in version.properties; scripts/build-apk.sh bumps versionCode on every build.
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

// Release signing: ~/peq-signing/signing.properties (outside git). Override with -Pmaxpeq.signing=<file>.
val signingFile = (providers.gradleProperty("maxpeq.signing").orNull ?: "${System.getProperty("user.home")}/peq-signing/signing.properties")
    .let { file(it) }.takeIf { it.isFile }
val signing = signingFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "app.maxpeq"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.maxpeq"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    signingConfigs {
        if (signingFile != null && signing != null) {
            create("release") {
                storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
