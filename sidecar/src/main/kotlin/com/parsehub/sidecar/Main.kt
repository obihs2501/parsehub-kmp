package com.parsehub.sidecar

import com.parsehub.Hub
import com.parsehub.core.Platform
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.security.MessageDigest

fun main() = runBlocking {
    val token = System.getenv("PARSEHUB_SESSION_TOKEN")?.takeIf { it.length >= 32 }
        ?: error("Missing session token")
    val root = System.getenv("PARSEHUB_DATA_DIR")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".parsehub-studio")
    val store = Store(root)
    val tasks = TaskService(store)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        install(ContentNegotiation) { json(wireJson) }
        intercept(ApplicationCallPipeline.Call) {
            val supplied = call.request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
            if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "Unauthorized") })
                finish()
                return@intercept
            }
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length != null && length > 1_048_576) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                finish()
            }
        }
        routing {
            get("/api/health") { call.respond(buildJsonObject { put("status", "ok"); put("protocol", 1) }) }
            get("/api/platforms") { call.respond(JsonArray(Hub.platforms.map { p -> buildJsonObject { put("key", p.key); put("label", p.label) } })) }
            post("/api/detect") { guarded(call) {
                val req = call.receive<ParseRequest>()
                call.respond(buildJsonObject { put("platform", Platform.detect(req.text)?.key?.let(::JsonPrimitive) ?: JsonNull) })
            } }
            post("/api/parse") { guarded(call) { call.respond(tasks.parse(call.receive<ParseRequest>().text)) } }
            get("/api/settings") { call.respond(store.settings) }
            put("/api/settings") { guarded(call) { call.respond(store.update(call.receive<Settings>())) } }
            get("/api/tasks") { call.respond(tasks.list()) }
            post("/api/tasks") { guarded(call) { call.respond(tasks.start(call.receive<TaskRequest>())) } }
            get("/api/tasks/{id}") { guarded(call) { call.respond(tasks.get(call.parameters["id"].orEmpty())) } }
            post("/api/tasks/{id}/cancel") { guarded(call) { call.respond(tasks.cancel(call.parameters["id"].orEmpty())) } }
            get("/api/history") { call.respond(synchronized(store.history) { store.history.load() }) }
            delete("/api/history") {
                synchronized(store.history) { store.history.clear() }
                call.respond(buildJsonObject { put("ok", true) })
            }
            get("/api/history/export") {
                val csv = synchronized(store.history) { store.history.toCsv(store.history.load()) }
                call.respond(buildJsonObject { put("csv", csv) })
            }
        }
    }.start(wait = false)
    val port = server.engine.resolvedConnectors().first().port
    println(buildJsonObject { put("event", "ready"); put("port", port); put("protocol", 1) })
    System.out.flush()
    try { while (System.`in`.read() != -1) { } }
    finally { tasks.close(); server.stop(500, 3000) }
}

private suspend fun guarded(call: ApplicationCall, action: suspend () -> Unit) {
    try { action() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", e.message?.take(240) ?: "请求失败") }) }
}
