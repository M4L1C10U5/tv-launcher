plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.home.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.home.tvlauncher"
        minSdk = 26
        targetSdk = 29
        versionCode = 8
        versionName = "8.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    // Optional personal overrides that never go to GitHub (see personal-example/README.md):
    // anything in ../personal/res replaces the matching file in src/main/res, e.g. your own default wallpaper.
    sourceSets.getByName("debug").res.srcDir("../personal/res")
    sourceSets.getByName("release").res.srcDir("../personal/res")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
