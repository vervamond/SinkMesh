plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun getGitHash(): String {
    return try {
        val process = Runtime.getRuntime().exec("git rev-parse --short HEAD")
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

val optionalSigningStoreFilePath =
    providers.gradleProperty("SIGNING_STORE_FILE")
        .orElse(providers.environmentVariable("SIGNING_STORE_FILE"))
        .orNull
        ?.takeIf { it.isNotBlank() }
val signingStorePassword =
    providers.gradleProperty("SIGNING_STORE_PASSWORD")
        .orElse(providers.environmentVariable("SIGNING_STORE_PASSWORD"))
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?: "android"
val signingKeyAlias =
    providers.gradleProperty("SIGNING_KEY_ALIAS")
        .orElse(providers.environmentVariable("SIGNING_KEY_ALIAS"))
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?: "androiddebugkey"
val signingKeyPassword =
    providers.gradleProperty("SIGNING_KEY_PASSWORD")
        .orElse(providers.environmentVariable("SIGNING_KEY_PASSWORD"))
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?: "android"
val hasStableDebugSigning = optionalSigningStoreFilePath != null
val stableDebugStoreFile = optionalSigningStoreFilePath?.let { file(it) }

if (hasStableDebugSigning) {
    val configuredStableDebugStoreFile =
        stableDebugStoreFile
            ?: error("SIGNING_STORE_FILE is set but no keystore path could be resolved.")
    if (!configuredStableDebugStoreFile.isFile) {
        error(
            "Configured signing keystore path is not a regular file: ${configuredStableDebugStoreFile.absolutePath}. " +
                    "Please ensure the file exists and points to a valid keystore."
        )
    }
}

android {
    namespace = "com.delamcode.datatransfer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.delamcode.datatransfer"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasStableDebugSigning) {
            create("stableDebug") {
                storeFile = checkNotNull(stableDebugStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig =
                if (hasStableDebugSigning) {
                    signingConfigs.getByName("stableDebug")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    @Suppress("AvoidDuplicateDependencies")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    @Suppress("AvoidDuplicateDependencies")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.core)
    implementation(libs.androidx.documentfile)
}