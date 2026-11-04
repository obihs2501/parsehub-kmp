plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "io.github.obihs2501.parsehub.shared"
        compileSdk { version = release(37) }
        minSdk = 29
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            api(libs.okio)
            implementation(libs.jetbrains.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}
