plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "io.appsynk.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Google Advertising ID
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")

    // Play Install Referrer
    implementation("com.android.installreferrer:installreferrer:2.2")

    // Reliable background delivery — persistent event queue + retry with network constraints
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Secure local storage (EncryptedSharedPreferences). 1.1.0-alpha06 is intentional: the 1.0.0
    // stable release has known key-rotation bugs — do NOT downgrade to 1.0.0.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ProcessLifecycleOwner — app foreground/background for session & app_open tracking
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Robolectric: only the HMAC test needs an Android runtime (android.util.Base64).
    testImplementation("org.robolectric:robolectric:4.12.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.appsynk"
                artifactId = "sdk"
                version = "1.0.0"
            }
        }
    }
}
