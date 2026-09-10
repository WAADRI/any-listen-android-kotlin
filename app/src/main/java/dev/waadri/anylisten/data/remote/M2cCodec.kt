package dev.waadri.anylisten.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Wire codec for the `message2call` protocol used by any-listen's web server.
 *
 * Messages are plain JSON arrays (no encryption: the server's `encryptMsg`/`decryptMsg`
 * are pass-through in current any-listen releases):
 *
 * ```
 * [0, "<callId>", ["player","playerAction"], [ <args> ], [ <callback arg indexes> ]]  // REQUEST
 * [1, "<callId>", null, <result> ]                                                   // RESPONSE (ok)
 * [1, "<callId>", { "message": "..." } ]                                             // RESPONSE (error)
 * [2, "<callbackName>", [ <args> ] ]                                                 // CALLBACK_REQUEST
 * [3, "<callbackName>", null, <result> ]                                             // CALLBACK_RESPONSE (ok)
 * [3, "<callbackName>", { "message": "..." } ]                                       // CALLBACK_RESPONSE (error)
 * ```
 *
 * Every method on this object is a pure function: the protocol layer stays testable on a
 * plain JVM without an emulator, which is why it must not touch Android APIs.
 */
object M2cCodec {

    const val TYPE_REQUEST = 0
    const val TYPE_RESPONSE = 1
    const val TYPE_CALLBACK_REQUEST = 2
    const val TYPE_CALLBACK_RESPONSE = 3

    /**
     * Lenient on input: unknown keys are ignored so a newer server adding fields cannot
     * break an older client. `coerceInputValues` additionally turns explicit `null`s for
     * non-nullable fields with defaults into those defaults.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    /** A request the server sent us, expecting a response. */
    data class IncomingRequest(
        val callId: String,
        val path: List<String>,
        val args: List<JsonElementBox>,
        val callbackIndexes: List<Int>,
    ) {
        val methodName: String get() = path.joinToString(".")
    }

    /** A callback the server invoked on the client (fire and forget from our side). */
    data class IncomingCallback(
        val callbackName: String,
        val args: List<JsonElementBox>,
    )

    sealed interface Incoming {
        data class Request(val request: IncomingRequest) : Incoming
        data class Callback(val callback: IncomingCallback) : Incoming
        data class Response(
            val callId: String,
            val errorMessage: String?,
            val result: JsonElementBox?,
        ) : Incoming
        data class CallbackResponse(
            val callbackName: String,
            val errorMessage: String?,
            val result: JsonElementBox?,
        ) : Incoming
        data class Malformed(val reason: String, val raw: String) : Incoming
    }

    /** Thin wrapper so protocol payloads stay as raw JSON for validation at the edges. */
    @JvmInline
    value class JsonElementBox(val value: kotlinx.serialization.json.JsonElement)

    fun encodeRequest(callId: String, path: List<String>, args: List<kotlinx.serialization.json.JsonElement>): String {
        val payload = buildJsonArray {
            add(JsonPrimitive(TYPE_REQUEST))
            add(JsonPrimitive(callId))
            add(buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
            add(buildJsonArray { args.forEach { add(it) } })
            add(buildJsonArray { }) // no proxy callbacks: this client never passes functions
        }
        return payload.toString()
    }

    fun encodeResponse(callId: String, errorMessage: String?, result: kotlinx.serialization.json.JsonElement?): String {
        val payload = buildJsonArray {
            add(JsonPrimitive(TYPE_RESPONSE))
            add(JsonPrimitive(callId))
            if (errorMessage == null) add(kotlinx.serialization.json.JsonNull) else add(JsonPrimitive(errorMessage))
            add(result ?: kotlinx.serialization.json.JsonNull)
        }
        return payload.toString()
    }

    fun encodeCallbackResponse(
        callbackName: String,
        errorMessage: String?,
        result: kotlinx.serialization.json.JsonElement?,
    ): String {
        val payload = buildJsonArray {
            add(JsonPrimitive(TYPE_CALLBACK_RESPONSE))
            add(JsonPrimitive(callbackName))
            if (errorMessage == null) add(kotlinx.serialization.json.JsonNull) else add(JsonPrimitive(errorMessage))
            add(result ?: kotlinx.serialization.json.JsonNull)
        }
        return payload.toString()
    }

    /**
     * Decodes one server frame. Never throws: a malformed frame becomes [Incoming.Malformed]
     * so the socket layer can decide whether to log-and-continue or drop the connection.
     */
    fun decode(raw: String): Incoming {
        val array: JsonArray = try {
            json.parseToJsonElement(raw).jsonArray
        } catch (e: Exception) {
            return Incoming.Malformed("not a JSON array: ${e.message}", raw)
        }
        val type = array.firstOrNull()?.let { element ->
            runCatching { element.jsonPrimitive.int }.getOrNull()
        } ?: return Incoming.Malformed("missing type tag", raw)

        return try {
            when (type) {
                TYPE_REQUEST -> {
                    val callId = array.getOrNull(1)?.jsonPrimitive?.content
                        ?: return Incoming.Malformed("request without callId", raw)
                    val path = array.getOrNull(2)?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: return Incoming.Malformed("request without path", raw)
                    val args = array.getOrNull(3)?.jsonArray?.map(::JsonElementBox).orEmpty()
                    val callbacks = array.getOrNull(4)?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull() }
                        .orEmpty()
                    Incoming.Request(IncomingRequest(callId, path, args, callbacks))
                }

                TYPE_RESPONSE -> {
                    val callId = array.getOrNull(1)?.jsonPrimitive?.content
                        ?: return Incoming.Malformed("response without callId", raw)
                    Incoming.Response(callId, errorOf(array.getOrNull(2)), array.getOrNull(3)?.let(::JsonElementBox))
                }

                TYPE_CALLBACK_REQUEST -> {
                    val name = array.getOrNull(1)?.jsonPrimitive?.content
                        ?: return Incoming.Malformed("callback without name", raw)
                    val args = array.getOrNull(2)?.jsonArray?.map(::JsonElementBox).orEmpty()
                    Incoming.Callback(IncomingCallback(name, args))
                }

                TYPE_CALLBACK_RESPONSE -> {
                    val name = array.getOrNull(1)?.jsonPrimitive?.content
                        ?: return Incoming.Malformed("callback response without name", raw)
                    Incoming.CallbackResponse(name, errorOf(array.getOrNull(2)), array.getOrNull(3)?.let(::JsonElementBox))
                }

                else -> Incoming.Malformed("unknown type tag $type", raw)
            }
        } catch (e: Exception) {
            Incoming.Malformed("failed to decode frame: ${e.message}", raw)
        }
    }

    /** The error slot is `null` on success and an object carrying at least `message` on failure. */
    private fun errorOf(element: kotlinx.serialization.json.JsonElement?): String? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val obj = element as? JsonObject ?: return element.toString()
        return obj["message"]?.jsonPrimitive?.content ?: obj.toString()
    }

    private fun JsonPrimitive.intOrNull(): Int? = runCatching { int }.getOrNull()

    /** Convenience for building a `{ "action": ..., "data": ... }` player action payload. */
    fun actionPayload(action: String, data: kotlinx.serialization.json.JsonElement? = null): JsonObject =
        JsonObject(
            buildMap {
                put("action", JsonPrimitive(action))
                if (data != null) put("data", data)
            },
        )

    /** Reads a boolean that may arrive as a real boolean or as a 0/1 number. */
    fun JsonObject.boolOrNullAt(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: primitive.longOrNull()?.let { it != 0L }
    }

    private fun JsonPrimitive.longOrNull(): Long? = runCatching { long }.getOrNull()

    // ---------------------------------------------------------------- positional payloads

    /**
     * Some server payloads are positional arrays whose element types differ per index — most
     * notably `status: ["playing", true]`. Reading them through these helpers keeps the
     * "numbers may arrive as int or double" problem in one place instead of scattered
     * `asDouble`/`asInt` casts that would throw at runtime.
     */
    fun numericAt(array: JsonArray?, index: Int): Double? {
        val primitive = array?.getOrNull(index) as? JsonPrimitive ?: return null
        return primitive.content.toDoubleOrNull()
    }

    fun stringAt(array: JsonArray?, index: Int): String? {
        val primitive = array?.getOrNull(index) as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    fun booleanAt(array: JsonArray?, index: Int): Boolean? {
        val primitive = array?.getOrNull(index) as? JsonPrimitive ?: return null
        return primitive.content.toBooleanStrictOrNull() ?: primitive.longOrNull()?.let { it != 0L }
    }

    fun asArray(element: kotlinx.serialization.json.JsonElement?): JsonArray? =
        element as? JsonArray
}
