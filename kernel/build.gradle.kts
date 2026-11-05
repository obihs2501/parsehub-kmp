plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.parsehub.kernel"
        compileSdk { version = release(37) }
        minSdk = 29
    }
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.okio)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        val desktopMain by getting
        desktopMain.dependencies { implementation(libs.ktor.client.okhttp) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
