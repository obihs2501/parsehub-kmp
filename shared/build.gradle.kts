plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.parsehub.shared"
        compileSdk { version = release(37) }
        minSdk = 29
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            api(libs.okio)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.animation)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.coil.network.okhttp)
            implementation(libs.ktor.client.okhttp)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(libs.coil.network.okhttp)
            implementation(libs.ktor.client.okhttp)
        }
    }
}
