plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * One signing key for every build (local and CI) so a new APK installs over the old one.
 * CI restores app/keystore/daptap.jks from the DAPTAP_KEYSTORE_BASE64 secret; locally the
 * file is created by scripts/make-keystore.sh. Without it, Gradle's per-machine debug key is used.
 */
val keystoreFile = file("keystore/daptap.jks")
val localKeyPassword: String? = file("keystore/password.txt").takeIf { it.exists() }?.readText()?.trim()
val ciBuildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0

android {
    namespace = "com.keshav.daptap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keshav.daptap"
        minSdk = 26
        targetSdk = 35
        versionCode = 1 + ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
        buildConfigField("String", "WEB_BASE", "\"https://codinggenius0001.github.io/namedrop-android/\"")
        buildConfigField("String", "RELAY_BASE", "\"https://ntfy.sh/\"")
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("daptap") {
                storeFile = keystoreFile
                storePassword = System.getenv("DAPTAP_KEYSTORE_PASSWORD") ?: localKeyPassword
                keyAlias = System.getenv("DAPTAP_KEY_ALIAS") ?: "daptap"
                keyPassword = System.getenv("DAPTAP_KEY_PASSWORD") ?: localKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("daptap")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("daptap")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

composeCompiler {
    enableStrongSkippingMode = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    debugImplementation(libs.androidx.ui.tooling)
}
