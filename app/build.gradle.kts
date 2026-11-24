import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.zebsi.tmuxremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zebsi.tmuxremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0"
        // Phones only; keeps the speech library out of the APK three more times.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Release signing comes from keystore.properties (never committed).
        // Without it, release builds are signed with the debug key.
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.all { it.testLogging { showStandardStreams = true } }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("org.connectbot:sshlib:2.2.52")
    // Offline speech recognition (models in assets/speech, see tools/fetch_speech_models.sh).
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
}
