import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.badger.trucks"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.badger.trucks"
        minSdk = 26
        targetSdk = 34
        versionCode = 42
        versionName = "42.0"

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
        kotlinCompilerExtensionVersion = "1.5.11" // For Kotlin 1.9.23
    }

    packaging {
        resources.excludes.add("META-INF/services/io.ktor.client.engine.HttpClientEngine")
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02") // For compiler 1.5.11
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
    val supabaseVersion = "2.5.0"
    implementation("io.github.jan-tennert.supabase:gotrue-kt:$supabaseVersion")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:$supabaseVersion")
    implementation("io.github.jan-tennert.supabase:realtime-kt:$supabaseVersion")
    
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
