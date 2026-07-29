package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Rumble extractor for Anichin "Rumble [Setting DNS]" mirrors.
 *
 * Site often geo/DNS blocks — try several embedJS + HTML endpoints.
 * Emits highest working progressive/HLS first; name stays "Rumble".
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
        val embedId = Regex("""/(?:embed|v)/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]v=([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return

        val headers = mapOf(
            "User-Agent" to ANICHIN_UA,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
            "Referer" to url,
            "Origin" to mainUrl,
        )

        val fetchUrls = listOf(
            // Official embedJS (preferred)
            "$mainUrl/embedJS/u3/?request=video&v=$embedId",
            "$mainUrl/embedJS/u3/?request=video&ver=2&v=$embedId",
            "$mainUrl/embedJS/?request=video&v=$embedId",
            // www variant
            "https://www.rumble.com/embedJS/u3/?request=video&v=$embedId",
            // embed page HTML
            "$mainUrl/embed/$embedId/",
            "$mainUrl/embed/$embedId/?pub=2li51c",
            url,
        )

        val bodies = mutableListOf<String>()
        for (endpoint in fetchUrls) {
            val text = runCatching {
                app.get(
                    endpoint,
                    referer = referer ?: url,
                    headers = headers,
                ).text
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                bodies.add(text)
                // stop early if JSON-looking with media keys
                if (text.contains("\"ua\"") || text.contains("\"mp4\"") || text.contains(".m3u8")) break
            }
        }
        if (bodies.isEmpty()) return

        val candidates = linkedMapOf<Int, String>() // quality -> url

        fun absorb(text: String) {
            // JSON blob
            runCatching {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start < 0 || end <= start) return@runCatching
                val json = JSONObject(text.substring(start, end + 1))
                listOf("ua", "mp4").forEach { key ->
                    val obj = json.optJSONObject(key) ?: return@forEach
                    obj.keys().forEach { qKey ->
                        val entry = obj.opt(qKey)
                        val stream = when (entry) {
                            is JSONObject -> entry.optString("url").ifBlank {
                                entry.optString("file")
                            }
                            is String -> entry
                            else -> ""
                        }.replace("\\/", "/")
                        if (stream.startsWith("http") && !isJunkStreamUrl(stream)) {
                            val q = qKey.filter { it.isDigit() }.toIntOrNull()
                                ?: Regex("""(\d{3,4})""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: 0
                            candidates[normalizePlayQuality(q)] = stream
                        }
                    }
                }
                listOf("hls_url", "hls", "live_hls_url").forEach { k ->
                    json.optString(k).replace("\\/", "/")
                        .takeIf { it.startsWith("http") }
                        ?.let { candidates.putIfAbsent(Qualities.Unknown.value, it) }
                }
            }

            // HTML / packed strings
            Regex(""""url"\s*:\s*"(https?://[^"]+)"""")
                .findAll(text)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter {
                    it.contains("rumble", true) ||
                        it.contains(".m3u8", true) ||
                        it.contains(".mp4", true)
                }
                .filterNot { isJunkStreamUrl(it) }
                .forEach { stream ->
                    val q = Regex("""(\d{3,4})""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    candidates.putIfAbsent(normalizePlayQuality(q), stream)
                }

            Regex("""https?://[^\s"'\\<>]+rumble[^\s"'\\<>]+\.(?:m3u8|mp4)[^\s"'\\<>]*""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.value.replace("\\/", "/") }
                .filterNot { isJunkStreamUrl(it) }
                .forEach { stream ->
                    val q = Regex("""(\d{3,4})""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    candidates.putIfAbsent(normalizePlayQuality(q), stream)
                }
        }

        bodies.forEach { absorb(it) }
        if (candidates.isEmpty()) return

        // Prefer progressive MP4 with known quality (more reliable than HLS on blocked DNS)
        // then HLS masters
        val ordered = candidates.entries.sortedWith(
            compareByDescending<Map.Entry<Int, String>> { !it.value.contains(".m3u8", true) }
                .thenByDescending { it.key }
        )

        var emitted = 0
        for ((q, stream) in ordered) {
            if (emitted >= 4) break // enough ladders, avoid spam
            when {
                stream.contains(".m3u8", true) -> {
                    // Single master (or expand if not audio-separated)
                    emitHlsVariants(
                        source = name,
                        streamUrl = stream,
                        referer = mainUrl,
                        callback = callback,
                        headers = mapOf(
                            "User-Agent" to ANICHIN_UA,
                            "Referer" to mainUrl,
                            "Origin" to mainUrl,
                            "Accept" to "*/*",
                        ),
                    )
                    emitted++
                }
                else -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = stream,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = if (q > 0) q else Qualities.Unknown.value
                            this.referer = mainUrl
                            this.headers = mapOf(
                                "User-Agent" to ANICHIN_UA,
                                "Referer" to mainUrl,
                                "Origin" to mainUrl,
                            )
                        }
                    )
                    emitted++
                }
            }
        }
    }
}
