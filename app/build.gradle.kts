import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mtts.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mtts.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // ★ 打包优化：只打 arm64-v8a（设备为 arm64），显著减小 APK 体积
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 正式签名：凭据从 keystore.properties（本地，不入 git）读取；
    // CI 通过环境变量覆盖（GitHub Secrets）：MTTS_STORE_FILE/MTTS_STORE_PASSWORD/MTTS_KEY_ALIAS/MTTS_KEY_PASSWORD
    val keystoreProps = Properties().apply {
        val f = file("../keystore.properties")
        if (f.exists()) load(f.inputStream())
    }
    fun p(key: String, env: String, def: String) =
        System.getenv(env) ?: keystoreProps.getProperty(key) ?: def

    signingConfigs {
        create("release") {
            storeFile = file(p("storeFile", "MTTS_STORE_FILE", "../keystore/zuimeng.jks"))
            storePassword = p("storePassword", "MTTS_STORE_PASSWORD", "")
            keyAlias = p("keyAlias", "MTTS_KEY_ALIAS", "zuimeng")
            keyPassword = p("keyPassword", "MTTS_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    }

    packaging {
        jniLibs {
            // 搜狗离线 SDK 需要从磁盘读取真实 .so 文件路径（frontModel/service_url），
            // 必须让 native 库解压到 nativeLibraryDir，而非经 APK 压缩（ZIP）直接加载。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // ★ 本地 libs 目录下的 jar（jieba-analysis 中文分词，说话人识别用）
    implementation(fileTree("libs") { include("*.jar") })

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Network (httpx 对应)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // YAML 解析（语音包 config.yaml / engines.yaml，MultiTTS SnakeYAML !!.xxx 标签格式）
    implementation("org.yaml:snakeyaml:2.2")

    // 微软嵌入式语音 SDK —— 提供完整 Java API + core.so + java.bindings.so
    // 嵌入式离线合成扩展库（embedded.tts/onnxruntime 等）官方 AAR 不含，由内置 jniLibs 补齐
    implementation("com.microsoft.cognitiveservices.speech:client-sdk-embedded:1.37.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}