package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

internal const val ANICHIN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Map raw HLS heights to CloudStream quality buckets.
 * StreamRuby often ships 1920x818 for the top rung — treat >=800 as 1080 so it ranks above 720.
 */
internal fun normalizePlayQuality(raw: Int): Int {
    if (raw <= 0) return Qualities.Unknown.value
    // Already a standard bucket
    if (raw == Qualities.P2160.value || raw == Qualities.P1440.value ||
        raw == Qualities.P1080.value || raw == Qualities.P720.value ||
        raw == Qualities.P480.value || raw == Qualities.P360.value ||
        raw == Qualities.P240.value || raw == Qualities.P144.value
    ) return raw

    return when {
        raw >= 1400 -> Qualities.P1440.value
        raw >= 800 -> Qualities.P1080.value   // 818, 900, 1080, 1920x818 …
        raw >= 700 -> Qualities.P720.value
        raw >= 500 -> Qualities.P480.value
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

/**
 * Expand master playlist, remap odd heights (818→1080), emit highest quality first.
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
) {
    val links = runCatching {
        M3u8Helper.generateM3u8(
            source = source,
            streamUrl = streamUrl,
            referer = referer,
            headers = headers,
        )
    }.getOrElse { emptyList() }

    if (links.isEmpty()) {
        // Still expose the master so player can try native HLS
        callback(
            newExtractorLink(source, "$source Auto", streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return
    }

    links
        .map { link ->
            val q = normalizePlayQuality(link.quality)
            Triple(q, link, qualityLabel(q))
        }
        .sortedByDescending { it.first }
        .forEach { (q, link, label) ->
            callback(
                newExtractorLink(
                    source = source,
                    name = "$source $label",
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

internal fun cleanServerLabel(raw: String): String {
    return raw
        .replace(Regex("""\[.*?\]"""), "")
        .replace(Regex("""\(.*?\)"""), "")
        .trim()
        .ifBlank { "Anichin" }
}
