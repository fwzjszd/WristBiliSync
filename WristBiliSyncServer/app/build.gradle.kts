plugins {
    id("com.android.application")
}

android {
    // Java 源码包名（工程内部结构，与 applicationId 解耦）
    namespace = "com.wristbili.sync"
    compileSdk = 34

    defaultConfig {
        // 互联硬约束：applicationId 必须与手环 RPK 包名完全一致（否则互联被拒）
        applicationId = "com.example.band.bilibili.lite"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 签名必须与手环 RPK 一致（7640E1AB / CN=Hyperbili）
            storeFile = file("./android.jks")
            storePassword = "123456"
            keyAlias = "hbkey"
            keyPassword = "123456"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // xms-wearable-lib（小米穿戴互联 SDK）+ 本地 jar/aar
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
