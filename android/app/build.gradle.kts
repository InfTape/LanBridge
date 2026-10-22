plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseStore = providers.environmentVariable("LANBRIDGE_RELEASE_KEYSTORE").orNull
android {
    namespace = "dev.lanbridge"
    compileSdk = 37
    defaultConfig { applicationId = "dev.lanbridge"; minSdk = 31; targetSdk = 36; versionCode = 41; versionName = "1.10.2" }
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if(releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("LANBRIDGE_RELEASE_PASSWORD").get()
            keyAlias = "lanbridge"
            keyPassword = storePassword
        }
    }
    buildTypes { release {
        isDebuggable = false
        isMinifyEnabled = false
        if(releaseStore != null) signingConfig = signingConfigs.getByName("release")
    } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
