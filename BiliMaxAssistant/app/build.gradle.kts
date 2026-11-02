import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    // AGP 9.0+ 内置 Kotlin 支持，无需再应用 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名证书不入库：keystore/resona.p12 与密码由本地 keystore.properties
// （或环境变量 RESONA_STORE_PASSWORD / RESONA_KEY_PASSWORD）提供。
// 仓库克隆者没有证书时自动回退默认 debug 签名，不影响日常开发构建。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val resonaKeystore = rootProject.file("keystore/resona.p12")
val hasResonaSigning = resonaKeystore.exists()

// 关于页展示用的构建元数据：构建时间取本机当前时间；构建者优先取 git user.name，
// 回退系统用户名；Git 提交取短 hash。命令失败（非 git 仓库/无 git）统一为“未知”
fun execText(vararg command: String): String =
    try {
        val process = ProcessBuilder(*command).directory(rootDir).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0) output else ""
    } catch (e: Exception) {
        ""
    }

val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.SIMPLIFIED_CHINESE)
    .format(Date())
val builderName = execText("git", "config", "user.name")
    .ifEmpty { System.getenv("USERNAME") ?: System.getProperty("user.name") ?: "未知" }

android {
    namespace = "moe.mcns.BiliMax"
    compileSdk = 37

    defaultConfig {
        // 包名必须与手表端 RPK manifest.json 的 package 完全一致，
        // 且 APK 与 RPK 必须使用同一签名证书，@system.interconnect 才能互通
        applicationId = "moe.mcns.BiliMax"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.2"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "BUILDER_NAME", "\"$builderName\"")
    }

    // 与 RPK（rpk/sign/certificate.pem，CN=ResonaUI）同一证书签名：
    // keystore/resona.p12 由该证书 + 私钥导出，否则互联配套服务指纹校验不通过。
    // 证书文件不入库，仅在本地存在时创建该签名配置
    signingConfigs {
        if (hasResonaSigning) {
            create("resona") {
                storeFile = resonaKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("RESONA_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: "resona"
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("RESONA_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasResonaSigning) {
                signingConfig = signingConfigs.getByName("resona")
            }
        }
        release {
            if (hasResonaSigning) {
                signingConfig = signingConfigs.getByName("resona")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // ffmpeg-kit 的 SmartLinker 从 nativeLibraryDir 以绝对路径加载依赖库，
            // 必须解压原生库（不能以未压缩页映射方式存放），否则转码时 UnsatisfiedLinkError 闪退
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 大卡片背景图标使用 Icons.Rounded.*（与参考项目 Shellplusplus/Android 一致）
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // miuix 组件库（HyperOS 风格，Compose Multiplatform，Android 构件）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")

    // Liquid Glass 背景采样：实时模糊 / 鲜艳度 / 透镜折射（Kyant0/AndroidLiquidGlass）
    implementation("io.github.kyant0:backdrop:2.0.1")
    // Liquid Glass 形状：iOS 风格连续曲率圆角（Kyant0/Shapes），lens 透镜折射依赖其轮廓
    implementation("io.github.kyant0:shapes:1.2.1")

    // FFmpegKit（社区维护 fork，audio 档含 libmp3lame，可输出 mp3；支持 SAF Uri）
    // 8.1.7 使用 NDK r27+ 构建，所有原生库（含 libc++_shared.so）均 16KB(0x4000) 页对齐，
    // 满足 Android 15+ 16KB 内核设备要求（旧 6.0.3 的 libc++_shared.so 仅 4KB 对齐，系统会弹窗警告）
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
    // 该 fork 的 AAR/POM 未声明 ffmpeg-kit 必需的 smart-exception 依赖，
    // 运行时 FFmpegKitConfig 类初始化会 NoClassDefFoundError，必须显式补齐
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // XMS Wearable SDK 净室实现（MIT，OrPudding/XMS_Wearable_SDK_CleanRoom 1.4_oronbox.1）。
    // 公共包名 com.xiaomi.xms.wearable.* 与官方 SDK 一致；AUTO 后端优先连 OronBox
    // 配套服务，回退小米运动健康/小米穿戴。官方未发布到 Maven Central，故 vendor 预构建 AAR。
    // AAR 内 <queries> 声明三个配套服务包名，随 manifest 合并提供 bindService 包可见性。
    implementation(files("libs/xms-wearable-lib-1.4_oronbox.1-release.aar"))
}
