plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jackdengler.quiet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jackdengler.quiet"
        minSdk = 28
        targetSdk = 35
        // CI passes the run number so every build installs over the previous one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.${versionCode}"
        // photos.json is read from this repo's main branch.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "jackdengler/tv-launcher"
        buildConfigField("String", "REPO", "\"$repo\"")
    }

    // Two apps from one codebase: the full launcher, and "Quiet Scores", which is only the
    // fantasy overlay (no home screen, no Home-button takeover).
    flavorDimensions += "app"
    productFlavors {
        create("launcher") {
            dimension = "app"
            buildConfigField("boolean", "LAUNCHER", "true")
            buildConfigField("String", "APK_NAME", "\"quiet.apk\"")
            resValue("string", "app_name", "Quiet")
            resValue("string", "a11y_label", "Quiet Home")
        }
        create("scores") {
            dimension = "app"
            applicationIdSuffix = ".scores"
            buildConfigField("boolean", "LAUNCHER", "false")
            buildConfigField("String", "APK_NAME", "\"quiet-scores.apk\"")
            resValue("string", "app_name", "Quiet Scores")
            resValue("string", "a11y_label", "Quiet Scores overlay")
        }
    }

    signingConfigs {
        // Committed on purpose: the repo is public and the key only guarantees
        // that every build carries the same signature so updates install in place.
        create("quiet") {
            storeFile = rootProject.file("keystore/quiet.jks")
            storePassword = "quietquiet"
            keyAlias = "quiet"
            keyPassword = "quietquiet"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("quiet")
        }
        debug {
            signingConfig = signingConfigs.getByName("quiet")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.zxing.core)
    implementation(libs.androidsvg)
}
