plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.jmread"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jmread"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        // 使用 Android 默认 debug keystore 签名 release
        // 与已安装 App 同签名，保证应用内更新可覆盖安装
        // 注意：默认 debug keystore 的别名/密码为公开默认值，非保密信息
        // keystore 路径不入库：默认取当前用户目录下的 Android 公共 debug keystore
        // （由 Android SDK 自动生成，别名/密码均为公开默认值，非保密信息），
        // 需要自定义签名时用环境变量覆盖：
        //   JM_KEYSTORE / JM_KS_PW / JM_KEY_ALIAS / JM_KEY_PW
        create("release") {
            storeFile = file(
                System.getenv("JM_KEYSTORE")
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            )
            storePassword = System.getenv("JM_KS_PW") ?: "android"
            keyAlias = System.getenv("JM_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("JM_KEY_PW") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        checkReleaseBuilds = false
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // BouncyCastle 的 JAR 含多版本(Multi-Release)资源，Android 不需要，排除避免打包冲突
    packaging {
        resources {
            excludes += "/META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    // 自定义 TLS：BouncyCastle 纯 Java 栈，绕过 Cloudflare 对 BoringSSL 的指纹拦截
    implementation(libs.bcprov)
    implementation(libs.bctls)
    implementation(libs.bcutil)
    debugImplementation(libs.androidx.ui.tooling)
    // 单测：DTO fixture 回归 + 纯逻辑（scramble/分页/域名池）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
