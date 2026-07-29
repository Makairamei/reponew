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
 * Rumble embed. Uses embedJS API (page scrape often times out / geo-blocked).
 * Qualities come from ua/mp4 maps when present.
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
            ?: return

        val apis = listOf(
            "$mainUrl/embedJS/u3/?request=video&v=$embedId",
            "$mainUrl/embedJS/?request=video&v=$embedId",
        )

        var body: String? = null
        for (api in apis) {
            body = runCatching {
                app.get(
                    api,
                    referer = url,
                    headers = mapOf(
                        "User-Agent" to ANICHIN_UA,
                        "Accept" to "*/*",
                        "Referer" to url,
                    ),
                ).text
            }.getOrNull()
            if (!body.isNullOrBlank() && body!!.contains("{")) break
        }

        if (body.isNullOrBlank()) {
            // Last resort: embed HTML
            body = runCatching {
                app.get(url, referer = referer ?: "$mainUrl/", headers = mapOf("User-Agent" to ANICHIN_UA)).text
            }.getOrNull()
        }
        if (body.isNullOrBlank()) return

        val jsonText = body.let { raw ->
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
        }

        val candidates = linkedMapOf<Int, String>() // quality -> url

        runCatching {
            val json = JSONObject(jsonText)
            // ua: {"360":{"url":"..."}, "720":{"url":"..."}} or similar
            listOf("ua", "mp4").forEach { key ->
                val obj = json.optJSONObject(key) ?: return@forEach
                obj.keys().forEach { qKey ->
                    val entry = obj.opt(qKey)
                    val stream = when (entry) {
                        is JSONObject -> entry.optString("url").ifBlank { entry.optString("file") }
                        is String -> entry
                        else -> ""
                    }.replace("\\/", "/")
                    if (stream.startsWith("http")) {
                        val q = qKey.filter { it.isDigit() }.toIntOrNull()
                            ?: Regex("""(\d{3,4})""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: 0
                        candidates[normalizePlayQuality(q)] = stream
                    }
                }
            }
            json.optString("hls_url").ifBlank { json.optString("hls") }
                .replace("\\/", "/")
                .takeIf { it.startsWith("http") }
                ?.let { candidates[Qualities.Unknown.value] = it }
        }

        // Regex fallback
        Regex(""""(?:url|file|hls_url|hls)"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { stream ->
                val q = Regex("""(\d{3,4})p?""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                candidates.putIfAbsent(normalizePlayQuality(q), stream)
            }

        if (candidates.isEmpty()) return

        candidates.entries
            .sortedByDescending { it.key }
            .forEach { (q, stream) ->
                when {
                    stream.contains(".m3u8", true) -> {
                        emitHlsVariants(name, stream, mainUrl, callback)
                    }
                    else -> {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name ${qualityLabel(q)}",
                                url = stream,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.quality = if (q > 0) q else Qualities.Unknown.value
                                this.referer = mainUrl
                                this.headers = mapOf("User-Agent" to ANICHIN_UA, "Referer" to mainUrl)
                            }
                        )
                    }
                }
            }
    }
}
