plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

android {
    namespace = "com.laviavi.adsbandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laviavi.adsbandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "1.6.13"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        // Robolectric needs a real Android resource/manifest context even for
        // SQLite-only tests (AppDatabaseMigrationTests) — Room's SQLite bindings
        // are the real Android framework classes, not available on plain JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Pure-Kotlin receiver core. Historically these classes were duplicated into
    // :app by hand; new shared code lives here only. Phase 1 folded the rest in.
    implementation(project(":core:receiver"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.icons)
    implementation(libs.compose.activity)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.navigation)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.osmdroid.android)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.coroutines.android)
    implementation(libs.play.services.location)
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)

    // Room migration testing (AppDatabaseMigrationTests): Robolectric provides a
    // real Android SQLite engine in the JVM, which Room's migration path needs -
    // there is no plain-JVM SQLite driver wired into this project. Robolectric's
    // runner is JUnit4; junit-vintage-engine bridges it onto the JUnit Platform
    // this module already runs everything else through.
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.junit4)
    testImplementation("androidx.test:core:1.6.1")
    testRuntimeOnly(libs.junit.vintage.engine)

    // Compose layout assertions (AircraftRowLayoutTests) run on Robolectric rather
    // than a device: the Live row's column alignment is a measurement property, so
    // it can be asserted off-device and stays in the fast unit-test suite.
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

ksp {
    // Exports the schema at every version from here on, so a future migration
    // can be tested against the exact prior schema instead of reconstructing it
    // by hand — the gap that made this version's migrations harder to verify.
    arg("room.schemaLocation", "$projectDir/schemas")
}
