plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.parsehub.app"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.parsehub.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 内置 Kotlin，工具链配置照 Miuix 官方约定插件写
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension> {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.miuix.ui.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
