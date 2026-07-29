package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

internal const val ANICHIN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

/** I-frame-only / junk playlists (i1/i2/i3) */
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

/**
 * Dailymotion (and similar) masters use separate EXT-X-MEDIA AUDIO groups.
 * Expanding with generateM3u8 yields video-only leaves → silent "Dailymotion 1080p".
 */
internal fun isAudioSeparateMasterHost(url: String): Boolean {
    val u = url.lowercase()
    return u.contains("dailymotion.com") ||
        u.contains("dmcdn.net") ||
        u.contains("cdndirector.dailymotion") ||
        u.contains("dai.ly") ||
        (u.contains("geo.dailymotion") && u.contains(".m3u8"))
}

internal fun cleanServerLabel(raw: String): String {
    return raw
        .replace(Regex("""\[.*?\]"""), "")
        .replace(Regex("""\(.*?\)"""), "")
        // Keep "Rumble" readable; DNS is a site hint not part of player name
        .replace(Regex("""(?i)\s*setting\s*dns\s*"""), "")
        .trim()
        .ifBlank { "Anichin" }
}

/**
 * Emit HLS for CloudStream.
 *
 * For Dailymotion-class masters: **master only** (sound). Never emit quality leaves.
 * For others: expand multi-quality, drop i-frame junk, name = source only.
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
    masterOnly: Boolean = false,
) {
    val cleanSource = cleanServerLabel(source)
    val forceMasterOnly = masterOnly || isAudioSeparateMasterHost(streamUrl) || preferMaster && isAudioSeparateMasterHost(streamUrl)

    if (forceMasterOnly || preferMaster || isAudioSeparateMasterHost(streamUrl)) {
        // ONE entry — player adaptive, keeps AUDIO. No "Source 1080p" silent clones.
        callback(
            newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return
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
        .map { link -> normalizePlayQuality(link.quality) to link }
        .sortedByDescending { it.first }

    if (links.isEmpty()) {
        callback(
            newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return
    }

    links.forEach { (q, link) ->
        if (isJunkStreamUrl(link.url, link.name)) return@forEach
        // Skip if leaf looks like audio-less fmp4 video track only from DM (belt+suspenders)
        if (isAudioSeparateMasterHost(link.url)) return@forEach
        callback(
            newExtractorLink(
                source = cleanSource,
                name = cleanSource,
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
