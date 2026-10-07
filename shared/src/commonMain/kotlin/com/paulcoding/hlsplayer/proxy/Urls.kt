package com.paulcoding.hlsplayer.proxy

/**
 * Relative references resolved as RFC 3986 §5.2 says, so a playlist's URIs
 * land exactly where a browser would send them. Only http(s) bases are used.
 */
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

private data class Parts(val scheme: String, val authority: String, val path: String, val query: String?)

private fun split(url: String): Parts? {
    val scheme = SCHEME.find(url)?.value?.dropLast(1)?.lowercase() ?: return null
    val rest = url.substring(scheme.length + 1).substringBefore('#')
    if (!rest.startsWith("//")) return null
    val afterSlashes = rest.substring(2)
    val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) afterSlashes.length else it }
    val authority = afterSlashes.substring(0, authorityEnd)
    val tail = afterSlashes.substring(authorityEnd)
    val path = tail.substringBefore('?')
    val query = if ('?' in tail) tail.substringAfter('?') else null
    return Parts(scheme, authority, path, query)
}

private fun join(parts: Parts) = buildString {
    append(parts.scheme).append("://").append(parts.authority).append(parts.path.ifEmpty { "/" })
    parts.query?.let { append('?').append(it) }
}

/** RFC 3986 §5.2.4. */
internal fun removeDotSegments(path: String): String {
    val output = mutableListOf<String>()
    val segments = path.split('/')
    segments.forEachIndexed { index, segment ->
        when (segment) {
            "." -> if (index == segments.lastIndex) output += ""
            ".." -> {
                if (output.size > 1) output.removeAt(output.lastIndex)
                if (index == segments.lastIndex) output += ""
            }
            else -> output += segment
        }
    }
    return output.joinToString("/").let { if (path.startsWith("/") && !it.startsWith("/")) "/$it" else it }
}

/** [reference] resolved against [base]; null when the result is not an http(s) URL. */
fun resolveUrl(base: String, reference: String): String? {
    val ref = reference.trim().substringBefore('#')
    val baseParts = split(base) ?: return null
    val resolved = when {
        SCHEME.containsMatchIn(ref) -> split(ref)?.let { it.copy(path = removeDotSegments(it.path)) } ?: return null
        ref.startsWith("//") -> split("${baseParts.scheme}:$ref")?.let { it.copy(path = removeDotSegments(it.path)) } ?: return null
        ref.startsWith("/") -> baseParts.copy(path = removeDotSegments(ref.substringBefore('?')), query = queryOf(ref))
        ref.startsWith("?") -> baseParts.copy(query = ref.substring(1))
        ref.isEmpty() -> baseParts
        else -> {
            val directory = baseParts.path.substringBeforeLast('/', "") + "/"
            baseParts.copy(path = removeDotSegments(directory + ref.substringBefore('?')), query = queryOf(ref))
        }
    }
    if (resolved.scheme != "http" && resolved.scheme != "https") return null
    if (resolved.authority.isEmpty()) return null
    return join(resolved)
}

private fun queryOf(ref: String) = if ('?' in ref) ref.substringAfter('?') else null

/**
 * Upstream URLs are mirrored into the proxy's path —
 * `https://cdn.test/show/a.m3u8` becomes `<base>/s/<token>/https/cdn.test/show/a.m3u8` —
 * so a relative URI the player resolves itself still lands on the right
 * upstream file. That is what lets a DASH manifest pass through unrewritten.
 */
fun proxyUrl(base: String, token: String, upstream: String): String? {
    val parts = split(upstream) ?: return null
    if (parts.scheme != "http" && parts.scheme != "https") return null
    // User info would end up in the path, readable by anything on the device.
    if (parts.authority.isEmpty() || '@' in parts.authority) return null
    return buildString {
        append(base.trimEnd('/')).append("/s/").append(token).append('/').append(parts.scheme)
            .append('/').append(parts.authority).append(parts.path.ifEmpty { "/" })
        parts.query?.let { append('?').append(it) }
    }
}

data class ProxyTarget(val token: String, val upstream: String)

/** The upstream URL a proxy request names, from its path and query; null when it names none. */
fun upstreamOf(path: String, query: String?): ProxyTarget? {
    val segments = path.removePrefix("/").split('/')
    if (segments.size < 4 || segments[0] != "s") return null
    val token = segments[1]
    val scheme = segments[2].lowercase()
    val authority = segments[3]
    if (token.isEmpty() || (scheme != "http" && scheme != "https") || authority.isEmpty() || '@' in authority) return null
    val rest = segments.drop(4).joinToString("/")
    val upstream = buildString {
        append(scheme).append("://").append(authority).append('/').append(rest)
        if (!query.isNullOrEmpty()) append('?').append(query)
    }
    return ProxyTarget(token, upstream)
}
