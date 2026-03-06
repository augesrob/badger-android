import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.badger.trucks"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.badger.access"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "11.0"

        // Secrets injected from local.properties (dev) or GitHub Secrets (CI)
        val localProps = Properties().also { props ->
            val f = rootProject.file("local.properties")
            if (f.exists()) props.load(f.inputStream())
        }
        fun secret(key: String): String = System.getenv(key) ?: localProps.getProperty(key, "")

        buildConfigField("String", "SUPABASE_URL",  "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_KEY",  "\"${secret("SUPABASE_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY","\"${secret("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GITHUB_TOKEN",  "\"${secret("GH_TOKEN")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("badger.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "badger123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "badger"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "badger123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources.excludes.add("META-INF/services/io.ktor.client.engine.HttpClientEngine")
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    // Image loading (avatar)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Supabase & Ktor - Explicit versions
    val supabaseVersion = "3.1.4"
    implementation("io.github.jan-tennert.supabase:postgrest-kt:$supabaseVersion")
    implementation("io.github.jan-tennert.supabase:realtime-kt:$supabaseVersion")
    implementation("io.github.jan-tennert.supabase:auth-kt:$supabaseVersion")
    implementation("io.github.jan-tennert.supabase:storage-kt:$supabaseVersion")

    val ktorVersion = "3.1.1"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
