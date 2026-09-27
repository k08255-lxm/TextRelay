import java.util.Properties

// 签名凭据不从源码读取：优先项目根目录 keystore.properties（不入库），其次环境变量
// TEXTRELAY_STORE_PASSWORD / TEXTRELAY_KEY_PASSWORD。两者都没有时跳过自定义签名。
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}
val signingStorePassword: String? =
    keystoreProps.getProperty("storePassword") ?: System.getenv("TEXTRELAY_STORE_PASSWORD")
val signingKeyPassword: String? =
    keystoreProps.getProperty("keyPassword") ?: System.getenv("TEXTRELAY_KEY_PASSWORD")
val hasFixedSigning = signingStorePassword != null && signingKeyPassword != null
if (!hasFixedSigning) {
    println("警告: 未找到 keystore.properties 或签名环境变量（TEXTRELAY_STORE_PASSWORD / TEXTRELAY_KEY_PASSWORD），固定签名未启用")
}

// versionCode 自动取 git 提交数（随提交递增，无需手动维护）；无 git 环境回退为 1
val autoVersionCode: Int = run {
    try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir)
            .start()
        val n = proc.inputStream.reader().readText().trim().toIntOrNull()
        runCatching { proc.destroy() }
        n ?: 1
    } catch (e: Exception) {
        logger.warn("无法读取 git 提交数，versionCode 回退为 1：{}", e.message)
        1
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.textrelay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.textrelay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = "1.0.4"
    }

    signingConfigs {
        create("main") {
            storeFile = file("textrelay.keystore")
            keyAlias = "textrelay"
            if (hasFixedSigning) {
                storePassword = signingStorePassword
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        // debug 与 release 共用同一把固定签名：升级可直接覆盖安装，无需卸载重装。
        // 若 keystore.properties / 环境变量缺失，debug 回退默认签名、release 输出未签名包。
        debug {
            if (hasFixedSigning) signingConfig = signingConfigs.getByName("main")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasFixedSigning) signingConfig = signingConfigs.getByName("main")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
