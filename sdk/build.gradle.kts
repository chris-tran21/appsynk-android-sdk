import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "io.appsynk.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

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

// Maven Central publishing via the Sonatype Central Portal (com.vanniktech.maven.publish).
// Credentials and the in-memory signing key are supplied at publish time through
// ORG_GRADLE_PROJECT_* environment variables (see .github/workflows/publish-maven.yml).
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("io.appsynk", "sdk", "1.0.0")

    // Release variant with a sources jar and an (empty) javadoc jar — the empty javadoc
    // jar satisfies Central's requirement, which is standard for Kotlin/Android libraries.
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )

    pom {
        name.set("AppSynk Android SDK")
        description.set(
            "Kotlin SDK for Android install tracking, event measurement, and attribution.",
        )
        inceptionYear.set("2025")
        url.set("https://github.com/appsynk/appsynk-android-sdk")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("appsynk")
                name.set("AppSynk")
                email.set("contact@appsynk.io")
                url.set("https://github.com/appsynk")
            }
        }

        scm {
            url.set("https://github.com/appsynk/appsynk-android-sdk")
            connection.set("scm:git:git://github.com/appsynk/appsynk-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/appsynk/appsynk-android-sdk.git")
        }
    }
}
