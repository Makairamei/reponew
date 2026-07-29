package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rumble — matched to BetbetMiro-Extension AnichinMoe.cs3 (dex strings):
 * - script:containsData(mp4)
 * - substringAfter {"mp4  … substringBefore "evt":{
 * - regex "url":"(.*?)"
 * - rumble.com + .m3u8 + multi-variant master only
 *
 * DO NOT multi-fetch embedJS (timeouts → Rumble never shows).
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

        // --- exact Betbet path ---
        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()
            ?.substringAfter("{\"mp4")
            ?.substringBefore("\"evt\":{")

        val processedUrls = mutableSetOf<String>()

        if (!scriptData.isNullOrBlank()) {
            val regex = """"url":"(.*?)"|h":(.*?)\}""".toRegex()
            for (match in regex.findAll(scriptData)) {
                val rawUrl = match.groupValues[1]
                if (rawUrl.isBlank()) continue

                val cleanedUrl = rawUrl.replace("\\/", "/")
                if (!cleanedUrl.contains("rumble.com")) continue
                if (!cleanedUrl.endsWith(".m3u8") && !cleanedUrl.contains(".m3u8")) continue
                if (!processedUrls.add(cleanedUrl)) continue

                val variantCount = runCatching {
                    val m3u8Response = app.get(cleanedUrl, referer = pageUrl)
                    "#EXT-X-STREAM-INF".toRegex().findAll(m3u8Response.text).count()
                }.getOrDefault(0)

                // Betbet: only multi-variant masters
                if (variantCount > 1) {
                    callback.invoke(
                        newExtractorLink(
                            source = this@Rumble.name,
                            name = "Rumble",
                            url = cleanedUrl,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to ANICHIN_UA,
                                "Referer" to mainUrl,
                            )
                        }
                    )
                    return
                }
            }
        }

        // --- HTML body fallback (same single response, no extra host round-trips) ---
        val body = response.text
        // progressive mp4 from same script blob style
        Regex(""""url"\s*:\s*"(https?://[^"]*rumble[^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .forEach { mp4 ->
                val q = Regex("""(\d{3,4})""").find(mp4)?.groupValues?.getOrNull(1)?.toIntOrNull()
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Rumble",
                        url = mp4,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = mainUrl
                        this.quality = normalizePlayQuality(q ?: 0)
                        this.headers = mapOf("User-Agent" to ANICHIN_UA, "Referer" to mainUrl)
                    }
                )
            }

        // any rumble m3u8 still in page
        if (processedUrls.isEmpty()) {
            Regex("""https?://[^"'\\s<>]+rumble[^"'\\s<>]+\.m3u8[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
                .findAll(body)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .firstOrNull()
                ?.let { m3u8 ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Rumble",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to ANICHIN_UA, "Referer" to mainUrl)
                        }
                    )
                }
        }
    }

    private fun normalizeEmbed(url: String): String {
        if (url.contains("/embed/", true)) return url
        val id = Regex("""/(?:v)/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]v=([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return url
        return "$mainUrl/embed/$id/"
    }
}
