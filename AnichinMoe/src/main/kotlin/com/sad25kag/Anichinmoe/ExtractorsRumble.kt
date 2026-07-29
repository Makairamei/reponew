package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rumble — Betbet scrape path + multi-quality emission.
 *
 * Betbet finds the HLS master via:
 *   script:containsData(mp4) → {"mp4 … "evt":{ → "url":"…m3u8"
 *
 * We keep that discovery, then:
 * - expand master with generateM3u8 → 360/480/720/1080 list items (like Betbet UI)
 * - also emit progressive mp4 rungs from the same JSON blob when present
 *
 * Single page fetch only (no embedJS timeout loops).
 */
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeEmbed(url)
        val response = runCatching {
            app.get(pageUrl, referer = referer ?: "$mainUrl/")
        }.getOrNull() ?: return

        val body = response.text
        val headers = mapOf(
            "User-Agent" to ANICHIN_UA,
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "Accept" to "*/*",
        )

        // --- Betbet script blob ---
        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()
            ?.substringAfter("{\"mp4")
            ?.substringBefore("\"evt\":{")
            .orEmpty()

        val m3u8s = linkedSetOf<String>()
        val mp4s = linkedMapOf<Int, String>() // quality -> url

        fun absorbUrl(raw: String, qualityHint: String? = null) {
            val cleaned = raw.replace("\\/", "/").trim()
            if (!cleaned.startsWith("http")) return
            if (isJunkStreamUrl(cleaned)) return
            if (!cleaned.contains("rumble", true) &&
                !cleaned.contains(".m3u8", true) &&
                !cleaned.contains(".mp4", true)
            ) return

            when {
                cleaned.contains(".m3u8", true) -> m3u8s.add(cleaned)
                cleaned.contains(".mp4", true) -> {
                    val q = qualityHint?.let { getQualityFromName(it) }?.takeIf { it > 0 }
                        ?: Regex("""(\d{3,4})p?""").find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?.let { normalizePlayQuality(it) }
                        ?: Qualities.Unknown.value
                    // keep highest URL per quality bucket
                    mp4s[q] = cleaned
                }
            }
        }

        if (scriptData.isNotBlank()) {
            // Betbet regex
            val regex = """"url":"(.*?)"|h":(.*?)\}""".toRegex()
            for (match in regex.findAll(scriptData)) {
                absorbUrl(match.groupValues[1])
            }
            // quality-keyed maps often look like "1080":{"url":"..."} or "720":{"url":"..."}
            Regex(""""(\d{3,4})"\s*:\s*\{[^}]*?"url"\s*:\s*"(https?://[^"]+)"""")
                .findAll(scriptData)
                .forEach { absorbUrl(it.groupValues[2], it.groupValues[1] + "p") }
        }

        // Full page fallbacks
        Regex(""""(\d{3,4})"\s*:\s*\{[^}]*?"url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorbUrl(it.groupValues[2], it.groupValues[1] + "p") }

        Regex(""""url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorbUrl(it.groupValues[1]) }

        Regex("""https?://[^"'\\s<>]+rumble[^"'\\s<>]+\.(?:m3u8|mp4)[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { absorbUrl(it.value) }

        var emitted = false

        // 1) Progressive MP4 ladder (explicit qualities — matches "banyak kualitas" list)
        mp4s.entries
            .sortedByDescending { it.key }
            .forEach { (q, stream) ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = mainUrl
                        this.quality = if (q > 0) q else Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                emitted = true
            }

        // 2) HLS masters → expand to quality list (360/720/1080…)
        for (master in m3u8s) {
            val links = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = master,
                    referer = mainUrl,
                    headers = headers,
                )
            }.getOrElse { emptyList() }
                .filterNot { isJunkStreamUrl(it.url, it.name) }

            if (links.isNotEmpty()) {
                links
                    .map { normalizePlayQuality(it.quality) to it }
                    .sortedByDescending { it.first }
                    .forEach { (q, link) ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = link.url,
                                type = link.type ?: ExtractorLinkType.M3U8,
                            ) {
                                this.referer = link.referer.ifBlank { mainUrl }
                                this.quality = q
                                this.headers = if (link.headers.isNotEmpty()) link.headers else headers
                            }
                        )
                        emitted = true
                    }
            } else {
                // master as adaptive fallback
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = master,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                emitted = true
            }
        }

        // nothing found → silent return (don't block other hosts)
        if (!emitted) return
    }

    private fun normalizeEmbed(url: String): String {
        if (url.contains("/embed/", true)) return url
        val id = Regex("""/(?:v)/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]v=([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return url
        return "$mainUrl/embed/$id/"
    }
}
