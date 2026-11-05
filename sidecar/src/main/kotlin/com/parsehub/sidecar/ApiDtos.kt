package com.parsehub.sidecar

import com.parsehub.core.ParseResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ParseRequest(val text: String)
@Serializable data class TaskRequest(
    val resultId: String,
    val pick: Set<Int> = emptySet(),
    val imagesOn: Boolean = true,
    val auxOn: List<Boolean> = emptyList(),
    val creator: String = "",
)
@Serializable data class TaskState(
    val id: String,
    val status: String = "running",
    val name: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val doneJobs: Int = 0,
    val totalJobs: Int = 0,
    val error: String = "",
    val files: List<String> = emptyList(),
)

fun ParseResult.toDto(): JsonObject = buildJsonObject {
    put("platform", platform.key); put("id", id); put("title", title)
    put("author", author); put("authorId", authorId); put("date", date)
    put("durSec", durSec); put("statLine", statLine); put("width", width); put("height", height)
    put("cover", cover?.let(::JsonPrimitive) ?: JsonNull); put("referer", referer)
    putJsonArray("tiers") { tiers.forEach { t -> add(buildJsonObject {
        put("idx", t.idx); put("gear", t.gear); put("w", t.w); put("h", t.h)
        put("bps", t.bps); put("size", t.size); put("h265", t.h265); put("fps", t.fps)
        put("fpsEst", t.fpsEst); put("isOrigin", t.isOrigin)
        put("urls", JsonArray(t.urls.map(::JsonPrimitive)))
    }) } }
    putJsonArray("images") { images.forEach { im -> add(buildJsonObject {
        put("index", im.index); put("url", im.url); put("ext", im.ext)
        put("live", im.live?.let(::JsonPrimitive) ?: JsonNull); put("w", im.w); put("h", im.h)
    }) } }
    putJsonArray("aux") { aux.forEach { a -> add(buildJsonObject {
        put("kind", a.kind); put("label", a.label); put("spec", a.spec); put("ext", a.ext)
        put("urls", JsonArray(a.urls.map(::JsonPrimitive)))
    }) } }
}
