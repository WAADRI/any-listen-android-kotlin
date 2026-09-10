package dev.waadri.anylisten.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The slice of the server's `AppSetting` this client actually consumes.
 *
 * The full settings object has ~60 keys and is shared across desktop/web; modelling all of it
 * would mean maintaining a mirror of an upstream type that changes independently of us. This
 * reads only what playback needs and leaves everything else alone (`ignoreUnknownKeys`).
 */
data class AppSettings(
    val playMethod: PlayMethod = PlayMethod.LIST_LOOP,
    val playQuality: String? = null,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val playbackRate: Double = 1.0,
) {
    companion object {
        fun fromJson(root: kotlinx.serialization.json.JsonElement?): AppSettings {
            val obj = root as? JsonObject ?: return AppSettings()

            fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
            fun bool(key: String): Boolean? = obj[key]?.let { element ->
                runCatching { element.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
            }
            fun dbl(key: String): Double? = obj[key]?.let { element ->
                runCatching { element.jsonPrimitive.content.toDoubleOrNull() }.getOrNull()
            }

            return AppSettings(
                playMethod = PlayMethod.fromWire(str("player.togglePlayMethod")),
                playQuality = str("player.playQuality"),
                volume = dbl("player.volume")?.coerceIn(0.0, 1.0) ?: 1.0,
                muted = bool("player.isMute") ?: false,
                playbackRate = dbl("player.playbackRate")?.coerceIn(0.25, 4.0) ?: 1.0,
            )
        }

        fun decode(raw: String): AppSettings? = try {
            fromJson(M2cCodec.json.parseToJsonElement(raw))
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * `player.togglePlayMethod` from the server's settings.
 *
 * `NONE` has no dedicated UI in the web client and behaves like "stop at the end of the list",
 * so it is mapped to [LIST] rather than inventing a fifth behaviour.
 */
enum class PlayMethod(val wire: String) {
    LIST_LOOP("listLoop"),
    RANDOM("random"),
    LIST("list"),
    SINGLE_LOOP("singleLoop"),

    /** `'none'` and anything unrecognised. */
    STOP_AT_END("none"),
    ;

    companion object {
        fun fromWire(value: String?): PlayMethod = when (value) {
            "listLoop" -> LIST_LOOP
            "random" -> RANDOM
            "singleLoop" -> SINGLE_LOOP
            "list" -> LIST
            else -> LIST_LOOP
        }
    }
}
