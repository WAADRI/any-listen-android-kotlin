package dev.waadri.anylisten

import android.util.Log

/**
 * The one place this app writes to logcat.
 *
 * ## Why this exists
 *
 * Two shipped defects were invisible for the same reason: a wrong lyric tag key rendered a blank
 * pane, and a resume that ran before the socket started did nothing at all. Both compiled, both
 * passed every unit test, and **neither produced a single line of output**. Debugging them from a
 * phone meant reading the screen and guessing, because logcat carried nothing but EGL and IME
 * noise from the platform.
 *
 * So the rule is: anything that can fail *quietly* logs here.
 *
 * ## Redaction
 *
 * A logcat buffer is readable by anyone with adb access, and this app holds a server password and
 * a bearer token. [secret] is the only sanctioned way to print either, and the JWT is identified
 * by length alone — enough to tell "we have one" from "we have none", which is what actually
 * matters when a handshake fails.
 *
 * ## Cost in release builds
 *
 * Every entry point returns immediately unless [BuildConfig.DEBUG], so the release APK carries no
 * logging behaviour. Values passed as arguments are still evaluated (the argument is built before
 * the call), so expensive or secret-bearing expressions belong *inside* a lambda or behind [hide].
 */
object Diag {

    /** Filter with: `adb logcat -s AnyListen:D` */
    const val TAG = "AnyListen"

    /** The key used for every field whose value must never be readable in a log. */
    private const val REDACTED = "<hidden>"

    fun d(field: String, value: Any?) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "$field=${render(value)}")
    }

    /** A whole event on one line, as `key=value key=value`. */
    fun event(field: String, vararg pairs: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val body = pairs.joinToString(" ") { (key, value) -> "$key=${render(value)}" }
        Log.d(TAG, if (body.isEmpty()) field else "$field $body")
    }

    /** A failure worth seeing even when it is handled. Never includes a stack trace. */
    fun problem(field: String, detail: Any?) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "$field=${render(detail)}")
    }

    /**
     * Marks a value as secret.
     *
     * Replaces the value entirely with its length class rather than a prefix: a truncated token is
     * still a credential fragment, and the only question worth answering here is whether one exists.
     */
    fun secret(value: String?): String = when {
        value == null -> "absent"
        value.isBlank() -> "blank"
        else -> "$REDACTED(${value.length})"
    }

    /**
     * Builds the value for a log line only in debug builds.
     *
     * Use this when computing the value is itself the expensive or sensitive part.
     */
    inline fun hide(build: () -> String?): String? = if (BuildConfig.DEBUG) build() else null

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> if (value.isEmpty()) "\"\"" else value
        else -> value.toString()
    }

    /**
     * A URL with any userinfo stripped, so a `http://user:pass@host` form cannot leak through.
     * Query strings are kept: the socket URL carries its token there, which is exactly why
     * [buildSocketUrl][dev.waadri.anylisten.data.remote.buildSocketUrl] is logged as a shape and
     * not passed through here.
     */
    fun url(raw: String?): String? {
        if (raw == null) return null
        return raw.replace(Regex("//[^/@]*@"), "//")
    }
}
