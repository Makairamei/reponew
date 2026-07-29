package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

internal const val ANICHIN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Map raw HLS heights to CloudStream quality buckets.
 * StreamRuby top rung is often 1920x818 → treat as 1080 so it ranks above 720.
 */
internal fun normalizePlayQuality(raw: Int): Int {
    if (raw <= 0) return Qualities.Unknown.value
    if (raw == Qualities.P2160.value || raw == Qualities.P1440.value ||
        raw == Qualities.P1080.value || raw == Qualities.P720.value ||
        raw == Qualities.P480.value || raw == Qualities.P360.value ||
        raw == Qualities.P240.value || raw == Qualities.P144.value
    ) return raw

    return when {
        raw >= 1400 -> Qualities.P1440.value
        raw >= 800 -> Qualities.P1080.value
        raw >= 700 -> Qualities.P720.value
        raw >= 400 -> Qualities.P480.value
        raw >= 300 -> Qualities.P360.value
        raw >= 200 -> Qualities.P240.value
        else -> raw
    }
}

internal fun qualityLabel(q: Int): String {
    return when (normalizePlayQuality(q)) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        Qualities.P240.value -> "240p"
        Qualities.P144.value -> "144p"
        else -> if (q > 0) "${q}p" else "Auto"
    }
}

/** I-frame-only / junk playlists that show as i1/i2/i3 and cannot play as normal video */
internal fun isJunkStreamUrl(url: String, nameHint: String = ""): Boolean {
    val u = url.lowercase()
    val n = nameHint.lowercase().trim()
    if (n.matches(Regex("""i\d+"""))) return true
    if (n.matches(Regex("""iframe.*"""))) return true
    return u.contains("iframe") ||
        u.contains("/iframes") ||
        u.contains("iframes-") ||
        u.contains("i-frame") ||
        u.contains("iframe-stream") ||
        Regex("""/(?:i|iframe)s?\d*(?:/|\.m3u8)""").containsMatchIn(u)
}

internal fun cleanServerLabel(raw: String): String {
    return raw
        .replace(Regex("""\[.*?\]"""), "")
        .replace(Regex("""\(.*?\)"""), "")
        .replace(Regex("""(?i)setting\s*dns"""), "")
        .trim()
        .ifBlank { "Anichin" }
}

/**
 * Emit HLS links for CloudStream UI.
 *
 * - name = source only (no "Source 1080p") so CS doesn't show double quality
 * - quality field carries the rung for sorting/display
 * - drops I-frame junk (i1/i2/i3)
 * - optional preferMaster: emit master playlist first (needed for Dailymotion separate AUDIO groups)
 */
internal suspend fun emitHlsVariants(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
    headers: Map<String, String> = mapOf(
        "User-Agent" to ANICHIN_UA,
        "Referer" to referer,
        "Origin" to (Regex("""^(https?://[^/]+)""").find(referer)?.value ?: referer),
        "Accept" to "*/*",
    ),
    preferMaster: Boolean = false,
) {
    val cleanSource = cleanServerLabel(source)

    if (preferMaster) {
        // Master keeps EXT-X-MEDIA AUDIO groups → sound works (Dailymotion)
        callback(
            newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
    }

    val links = runCatching {
        M3u8Helper.generateM3u8(
            source = cleanSource,
            streamUrl = streamUrl,
            referer = referer,
            headers = headers,
        )
    }.getOrElse { emptyList() }
        .filterNot { isJunkStreamUrl(it.url, it.name) }
        .map { link ->
            val q = normalizePlayQuality(link.quality)
            q to link
        }
        .sortedByDescending { it.first }

    if (links.isEmpty()) {
        if (!preferMaster) {
            callback(
                newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
        return
    }

    // If preferMaster already emitted Auto, still list discrete qualities without double-label
    links.forEach { (q, link) ->
        if (isJunkStreamUrl(link.url, link.name)) return@forEach
        callback(
            newExtractorLink(
                source = cleanSource,
                name = cleanSource, // quality only via .quality → UI shows once
                url = link.url,
                type = link.type ?: ExtractorLinkType.M3U8,
            ) {
                this.referer = link.referer.ifBlank { referer }
                this.quality = q
                this.headers = if (link.headers.isNotEmpty()) link.headers else headers
            }
        )
    }
}

/**
 * Pass-through generateM3u8 links (preserves helper metadata) with clean source name + junk filter.
 * Used when we must not break AUDIO group handling beyond master emit.
 */
internal suspend fun emitHlsPassThrough(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
    headers: Map<String, String>,
) {
    val cleanSource = cleanServerLabel(source)
    // Master first for audio
    callback(
        newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = headers
        }
    )
    runCatching {
        M3u8Helper.generateM3u8(
            source = cleanSource,
            streamUrl = streamUrl,
            referer = referer,
            headers = headers,
        )
    }.getOrElse { emptyList() }
        .filterNot { isJunkStreamUrl(it.url, it.name) }
        .forEach { link ->
            val q = normalizePlayQuality(link.quality)
            // Re-emit with clean name only; keep URL from helper
            callback(
                newExtractorLink(cleanSource, cleanSource, link.url, link.type ?: ExtractorLinkType.M3U8) {
                    this.referer = link.referer.ifBlank { referer }
                    this.quality = q
                    this.headers = if (link.headers.isNotEmpty()) link.headers else headers
                }
            )
        }
}
