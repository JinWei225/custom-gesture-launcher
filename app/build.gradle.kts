import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from keystore.properties at the repo root — gitignored, four keys:
// storeFile, storePassword, keyAlias, keyPassword. The release workflow writes it from repository
// secrets; without it a release build is simply unsigned, which is right for every build that
// isn't a published release.
val keystoreProperties: Properties? =
    rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().also { props -> file.inputStream().use(props::load) }
    }

android {
    namespace = "dev.neffly.gesturelauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.neffly.gesturelauncher"
        minSdk = 26
        // API 36 (Android 16) is what Google Play requires of new apps and updates as of August
        // 2026. API 37 exists and is stable, but its behaviour changes only take effect on an
        // Android 17 device, and there is none here to verify them against — so that is a separate
        // step, taken when it can be tested rather than read about.
        targetSdk = 36
        // Set by the release workflow from the git tag (v1.2.3 → "1.2.3" / 10203). A local build
        // is "dev": it can't be mistaken for a published one, and Obtainium — which compares
        // version names — will always see a release as an update to it.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "dev"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 on: a home screen is cold-started more than any other app on the phone, and a
            // shrunk dex is less to load and verify each time. The two reflective lookups in the
            // app (ActivityInfo.resizeMode, miui.app.MiuiFreeFormManager) target platform classes
            // and are untouched; kotlinx.serialization is covered by proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        // Robolectric needs the merged resources to inflate real views (see the ui tests).
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The `kotlinOptions` block inside `android {}` is AGP's own and is deprecated; from Kotlin 2.x the
// compiler is configured through the Kotlin plugin itself.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // The home screen's three pages (widgets, home, notes) and the launcher-style slide between
    // them; see MainActivity.
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // Expression evaluator behind the search bar's calculator (see search/Calculator.kt).
    // Apache-2.0, no transitive dependencies, and BigDecimal-based — which is the reason for
    // picking it over the double-based alternatives: a calculator that answers 0.1 + 0.2 with
    // 0.30000000000000004 is a bug report waiting to happen.
    implementation("com.ezylang:EvalEx:3.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    testImplementation("junit:junit:4.13.2")
    // Runs real Android views on the JVM, for the layout behaviour that can't be checked as pure
    // Kotlin — how the floating card's list measures against its ceiling, in particular.
    testImplementation("org.robolectric:robolectric:4.15.1")
}
