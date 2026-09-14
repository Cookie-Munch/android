plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "net.cookiemunch"
version = "0.1.0"

android {
    namespace = "net.cookiemunch"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // EncryptedSharedPreferences-backed secure storage. The MasterKey.Builder API used
    // by EncryptedPrefsConsentStorage requires the 1.1.0 line (not 1.0.0's MasterKeys).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    // Local JVM unit tests. `org.json` is only stubbed in android.jar, so a real
    // implementation is supplied here for the serialization tests to run without a device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
