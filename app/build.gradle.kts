plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shizuku.wolwake"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shizuku.wolwake"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.1.8"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    val keystorePath = System.getenv("WOL_KEYSTORE_PATH")
        ?: rootProject.file("wol-release.jks").absolutePath
    val keystoreFile = file(keystorePath)
    val storePasswordEnv = System.getenv("WOL_STORE_PASSWORD")
    val keyPasswordEnv = System.getenv("WOL_KEY_PASSWORD")
    val keyAliasEnv = System.getenv("WOL_KEY_ALIAS") ?: "wolwake"
    val hasReleaseSigning = keystoreFile.isFile &&
        !storePasswordEnv.isNullOrEmpty() &&
        !keyPasswordEnv.isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(files("libs/libzt-release.aar"))
    implementation("com.github.mwiede:jsch:0.2.18")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
