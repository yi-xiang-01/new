// 這是 build.gradle.kts (Module: :app) 的完整內容

// 1. plugins 區塊必須在最頂部
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // 如果您有 google-services.json 檔案，請務必保留這一行
    id("com.google.gms.google-services")
}

// 2. import 語句要放在 plugins 下面
import java.util.Properties
        import java.io.FileInputStream

        android {
            namespace = "com.example.mapcollection"
            compileSdk = 35

            defaultConfig {
                applicationId = "com.example.mapcollection"
                minSdk = 24
                targetSdk = 35
                versionCode = 1
                versionName = "1.0"

                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

                // 從 local.properties 讀取 API 金鑰
                val localProperties = Properties()
                val localPropertiesFile = project.rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    localProperties.load(FileInputStream(localPropertiesFile))
                }
                // 將 API 金鑰設為建置設定欄位
                buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY")}\"")
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
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
            kotlinOptions {
                jvmTarget = "1.8"
            }
            buildFeatures {
                viewBinding = true
                buildConfig = true
            }
        }

dependencies {
    // Firebase BoM（版本照你現在的 33.1.2 也 OK）
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))

    // ✅ 加上 Auth KTX，並把 Firestore/Storage 換成 KTX 版本
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.libraries.places:places:3.5.0")

    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.google.maps)
    implementation("com.google.code.gson:gson:2.10.1")

    // flask
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    implementation("com.airbnb.android:lottie:6.4.0")



    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
