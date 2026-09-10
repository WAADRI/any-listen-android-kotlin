package dev.waadri.anylisten.data.remote

/**
 * Play order, as understood by any-listen's `player.togglePlayMethod`.
 *
 * This client evaluates play order locally and never writes it to the server, but the wire values
 * are kept identical to the server's so the two never drift if the mode is ever displayed next to
 * a server-driven client.
 *
 * Volume is intentionally NOT modelled here. Output level belongs to the platform's media stream —
 * the hardware volume keys and the system volume panel — so this app has no volume setting to
 * store, sync, or reconcile.
 */
enum class PlayMethod(val wire: String) {
    LIST_LOOP("listLoop"),
    RANDOM("random"),
    LIST("list"),
    SINGLE_LOOP("singleLoop"),

    /**
     * `'none'`: play the list once and stop.
     *
     * Kept as its own value rather than folded into [LIST] so that selecting it and reading it back
     * round-trips exactly. It is reachable from the settings screen but skipped by the player's
     * tap-to-cycle button, which would otherwise make it a permanent extra tap.
     */
    STOP_AT_END("none"),
    ;

    /**
     * The mode a tap on the mode button advances to.
     *
     * Reached from [STOP_AT_END] by returning to list-loop, so the cycle always terminates.
     */
    fun next(): PlayMethod = when (this) {
        LIST_LOOP -> RANDOM
        RANDOM -> SINGLE_LOOP
        SINGLE_LOOP -> LIST
        LIST, STOP_AT_END -> LIST_LOOP
    }

    companion object {
        /**
         * Falls back to [LIST_LOOP] for anything unrecognised, which is also what a fresh install
         * gets and what the web client defaults to.
         */
        fun fromWire(value: String?): PlayMethod = when (value) {
            "listLoop" -> LIST_LOOP
            "random" -> RANDOM
            "singleLoop" -> SINGLE_LOOP
            "list" -> LIST
            "none" -> STOP_AT_END
            else -> LIST_LOOP
        }
    }
}
