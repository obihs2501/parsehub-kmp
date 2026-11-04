package com.parsehub.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 全模块共用的宽松 JSON 实例 */
val J = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    allowSpecialFloatingPointValues = true
}

fun parseJson(s: String): JsonElement = J.parseToJsonElement(s)

fun JsonObject.str(k: String, def: String = ""): String =
    (this[k] as? JsonPrimitive)?.contentOrNull ?: def

fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

fun JsonObject.lng(k: String, def: Long = 0L): Long =
    (this[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: def

fun JsonObject.int(k: String, def: Int = 0): Int =
    (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: def

fun JsonObject.dbl(k: String, def: Double = 0.0): Double =
    (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: def

fun JsonObject.bool(k: String, def: Boolean = false): Boolean =
    when ((this[k] as? JsonPrimitive)?.contentOrNull) {
        "true", "1" -> true
        "false", "0" -> false
        else -> def
    }

fun JsonObject.any(k: String): JsonElement? = this[k]

fun JsonArray.strs(): List<String> =
    this.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }

fun JsonArray.objs(): List<JsonObject> = this.mapNotNull { it as? JsonObject }

fun JsonObject.toJsonString(): String = this.toString()
