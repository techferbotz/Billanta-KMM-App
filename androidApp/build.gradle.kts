import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Firebase (Crashlytics + Cloud Messaging) reads its project from google-services.json, which comes
// from the Firebase console and is not in the repo yet. The google-services plugin fails the whole
// build without it, so both Firebase plugins are applied only once the file is there. Until then the
// app builds and runs normally, with Firebase simply inactive — hence the loud warning.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
    apply(plugin = libs.plugins.firebaseCrashlytics.get().pluginId)
} else {
    logger.warn(
        "w: androidApp/google-services.json is missing, so Firebase is OFF in this build: no crash " +
            "reports and no push notifications. Download it from the Firebase console (Project " +
            "settings → Your apps → com.ferbotz.billanta) and put it in androidApp/.",
    )
}

// A debug build's API host: `-Pbillanta.debugBaseUrl=…` on the command line, else the same key in
// local.properties (which is not in the repo); see buildTypes.
val debugBaseUrl: String = providers.gradleProperty("billanta.debugBaseUrl").orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
        ?.getProperty("billanta.debugBaseUrl")
        .orEmpty()

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    // Not used directly — the app has no Fragments. It is declared because several transitive
    // dependencies still *request* fragment 1.0.0–1.2.5, and lint's
    // InvalidFragmentVersionForActivityResult reads those requested versions rather than the one
    // Gradle actually resolves (1.5.7), failing the release build over a version nothing uses.
    // Declaring a current version makes the graph say what was already true.
    implementation(libs.androidx.fragment)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // Google sign-in via Credential Manager (yields the idToken POST /auth/google verifies).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.android)

    // Crash reports and push notifications. Both stay inert without google-services.json (above).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)
    // NotificationCompat, for posting a push that arrives while the app is open.
    implementation(libs.androidx.core.ktx)
}

android {
    namespace = "com.ferbotz.billanta"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ferbotz.billanta"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            // Points a debug build at another API host — a staging deploy, or a stand-in server
            // on this machine (the emulator reaches it as 10.0.2.2) — without touching the code.
            // Set `billanta.debugBaseUrl` in local.properties; unset means production. Release
            // builds never read it.
            buildConfigField("String", "API_BASE_URL_OVERRIDE", "\"$debugBaseUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL_OVERRIDE", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}