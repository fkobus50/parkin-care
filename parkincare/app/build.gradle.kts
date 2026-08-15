import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}


android {
    namespace = "com.example.parkincare"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.edu.wat.wcy.clinicaltrialsassistant.v2"
        minSdk = 28
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "MQTT_SERVER_URI", "\"${localProperties.getProperty("mqttServerUri", "")}\"")
        buildConfigField("String", "MQTT_TOPIC", "\"${localProperties.getProperty("mqttTopic", "")}\"")
        buildConfigField("String", "MQTT_USERNAME", "\"${localProperties.getProperty("mqttUsername", "")}\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"${localProperties.getProperty("mqttPassword", "").replace("\"", "\\\"")}\"")
        buildConfigField("String", "getReminderUrl", "\"${localProperties.getProperty("getReminderUrl", "").replace("\"", "\\\"")}\"")
        buildConfigField("String", "sendHistoryUrl", "\"${localProperties.getProperty("sendHistoryUrl", "").replace("\"", "\\\"")}\"")
        buildConfigField("String", "apiToken", "\"${localProperties.getProperty("apiToken", "").replace("\"", "\\\"")}\"")
        buildConfigField("Boolean", "MQTT_TLS_ENABLED", "${localProperties.getProperty("mqttTlsEnabled", "false")}")
        // Domyślne ścieżki wskazujące na res/raw (używane gdy brak wpisu w local.properties)
        buildConfigField("String", "MQTT_CA_CERT_PATH", "\"${localProperties.getProperty("mqttCaCertPath", "res/raw/ca")}\"")
        // Klient: domyślnie trzymamy certyfikat i klucz w assets (bezpieczeństwo klucza prywatnego)
        buildConfigField("String", "MQTT_CLIENT_CERT_PATH", "\"${localProperties.getProperty("mqttClientCertPath", "assets/client.crt")}\"")
        buildConfigField("String", "MQTT_CLIENT_KEY_PATH", "\"${localProperties.getProperty("mqttClientKeyPath", "assets/client.key")}\"")
        // DODANE: patientId z local.properties
        buildConfigField("String", "PATIENT_ID", "\"${localProperties.getProperty("patientId", "")}\"")
    }

    buildTypes {
        release {
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {

    implementation(libs.play.services.wearable)
    // lifecycle process dla ProcessLifecycleOwner
    implementation("androidx.lifecycle:lifecycle-process:2.6.1")
    // lifecycle runtime ktx dla lifecycleScope
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.tiles)
    implementation(libs.tiles.material)
    implementation(libs.tiles.tooling.preview)
    implementation(libs.horologist.compose.tools)
    implementation(libs.horologist.tiles)
    implementation(libs.gson)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.work.runtime.ktx)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.tiles.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.hivemq:hivemq-mqtt-client:1.3.9")
    // Netty handler required for building Netty SslContext used by HiveMQ client
    implementation("io.netty:netty-handler:4.1.99.Final")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation ("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // implementation("com.samsung.android.sdk.health:health-data:1.6.0")
    // implementation("com.samsung.android.sdk:health:6.0.0")
    // implementation("com.samsung.android.health:health-tracker:1.1.0")
    // Te zależności są niepotrzebne i powodują błąd budowania, bo nie są dostępne w publicznych repozytoriach.
}
