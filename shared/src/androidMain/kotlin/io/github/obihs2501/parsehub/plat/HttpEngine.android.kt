package io.github.obihs2501.parsehub.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun newHttpClient(followRedirects: Boolean): HttpClient =
    HttpClient(OkHttp) {
        this.followRedirects = followRedirects
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 90_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
