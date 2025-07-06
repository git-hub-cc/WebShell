plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "club.ppmc.webshell"
    compileSdk = 34

    defaultConfig {
        applicationId = "club.ppmc.webshell"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 正式发布建议开启 true，并配置好 Proguard 规则
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
    // buildFeatures {
    //     viewBinding = false // 如果不使用 ViewBinding
    // }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0") // 或 1.11.0
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Activity KTX 库，用于 registerForActivityResult
    implementation("androidx.activity:activity-ktx:1.8.2") // 或 1.9.0-alphaXX
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}