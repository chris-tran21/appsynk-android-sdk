// Top-level build file for AppSynk Android SDK
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

// Version catalog shorthand for CI reference
ext["sdkVersion"] = "1.0.0"
ext["compileSdkVersion"] = 34
ext["minSdkVersion"] = 21
