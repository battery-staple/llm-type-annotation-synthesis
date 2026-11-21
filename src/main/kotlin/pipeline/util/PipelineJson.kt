package pipeline.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PipelineJson {
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val compact: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Deterministic JSON rendering: object keys are lexicographically sorted. */
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                JsonPrimitive(k).toString() + ":" + canonical(v)
            }
        is kotlinx.serialization.json.JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") { canonical(it) }
        else -> element.toString()
    }

    fun sortedObject(vararg pairs: Pair<String, JsonElement>): JsonObject =
        buildJsonObject {
            pairs.sortedBy { it.first }.forEach { (k, v) -> put(k, v) }
        }

    fun stringArray(values: Iterable<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
