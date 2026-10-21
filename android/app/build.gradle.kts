plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "io.github.jherobred.lodisave"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.jherobred.lodisave"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.4"
        // Phones, plus x86_64 for the emulator. Each ABI carries its own copy of Python, so a quick
        // emulator build can pass -Plodi.abis=x86_64.
        ndk {
            abiFilters += providers.gradleProperty("lodi.abis").orNull?.split(',') ?: listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release key exists, so it still installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Compressed native libraries keep the download small. Python's are most of the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// yt-dlp isn't bundled: the app downloads its official zipapp on first use and keeps it current,
// as the Windows app does. Python only has to match the build machine's version.
chaquopy {
    defaultConfig {
        version = "3.14"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}
