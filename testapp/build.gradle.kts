plugins {
    id("com.android.application")
}

android {
    namespace = "com.droidprobe.testapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.droidprobe.testapp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Retrofit — compileOnly so annotations exist in DEX but library internals are excluded
    compileOnly("com.squareup.retrofit2:retrofit:2.11.0")

    // OkHttp — compileOnly for Request.Builder and HttpUrl references
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
}
