package dev.waadri.anylisten.data.remote

/**
 * Turns the URLs the server hands out into URLs a player can actually fetch.
 *
 * The server has two different ways of saying "this file lives on me", and neither is a plain
 * absolute URL. Getting this wrong produces a 404 that looks like a dead link rather than a bug,
 * so both forms are mirrored here from the authoritative helpers in
 * `packages/shared/common/tools.ts`:
 *
 * ```ts
 * export const VIRTUAL_PROTOCOL = 'al-ps-host:'
 * export const buildVirtualPublicPath = (basePath, name) =>
 *   `${basePath.startsWith('/') ? VIRTUAL_PROTOCOL : ''}${basePath}/${name}`
 * export const buildRealPublicPath = (virtualPath, host) => {
 *   if (!virtualPath.startsWith(VIRTUAL_PROTOCOL)) return virtualPath
 *   return virtualPath.replace(VIRTUAL_PROTOCOL, host)
 * }
 * ```
 *
 * Note `buildRealPublicPath` **replaces** the marker with the host; it does not append. So
 * `al-ps-host:/public/medias/x.mp3` becomes `<origin>/public/medias/x.mp3`, and treating the
 * marker as ordinary path text yields the 404 seen in `url.resolved` before this existed.
 */
object ServerUrl {

    /**
     * Placeholder scheme the server writes in place of its own origin. It is deliberately not
     * `http:`/`https:` so it can never be dialled by accident.
     */
    const val VIRTUAL_PROTOCOL = "al-ps-host:"

    /**
     * Makes [url] fetchable given the configured server [baseUrl].
     *
     * - `https://…`/`http://…` is already absolute and passes through untouched, so a track served
     *   from a CDN or an extension's own host is not rewritten.
     * - [VIRTUAL_PROTOCOL] is replaced by the origin, mirroring `buildRealPublicPath`.
     * - anything else is resolved against the origin. Covers arrive as `./api/p_static/<hash>.jpeg`
     *   — the server initialises its proxy with base `.` and path `/api/p_static`
     *   (`initProxyServer('.', API_PREFIX + PROXY_SERVER_PATH, …)`), so relative to the page it is a
     *   same-origin URL.
     *
     * [baseUrl] may or may not carry a trailing slash; it is trimmed before use.
     */
    fun resolve(url: String?, baseUrl: String): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val origin = baseUrl.trim().trimEnd('/')
        if (raw.startsWith(VIRTUAL_PROTOCOL)) {
            val path = raw.removePrefix(VIRTUAL_PROTOCOL)
            if (origin.isEmpty()) return path
            return "$origin${if (path.startsWith("/")) path else "/$path"}"
        }
        if (origin.isEmpty()) return raw
        // Strip the leading './' that the server uses for "relative to this document".
        val path = raw.removePrefix("./")
        return "$origin${if (path.startsWith("/")) path else "/$path"}"
    }
}
