plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.google.android.diskusage"
    compileSdk = 37
    // NDK r28+ links with 16 KB page alignment by default
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.google.android.diskusage"
        minSdk = 23
        targetSdk = 37
        versionCode = 5001
        versionName = "5.0-alpha1"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    androidResources {
        // Lets the user select the app language in the system settings
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    packaging {
        jniLibs {
            // libscan.so is an executable launched from nativeLibraryDir,
            // so native libraries must be extracted on install.
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    testOptions {
        // FileSystemEntry touches android.graphics in its static initializer
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

base {
    archivesName = "diskusage-v${android.defaultConfig.versionName}"
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.appiconloader)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.toast)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
