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
 * Rumble — Anichin labels this "Rumble [Setting DNS]".
 * Prefers embedJS API; falls back to page scrape (TESTINGCF style).
 * Display name kept as "Rumble" (DNS hint stripped by cleanServerLabel upstream).
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

        var body: String? = null
        val apis = listOf(
            "$mainUrl/embedJS/u3/?request=video&v=$embedId",
            "$mainUrl/embedJS/?request=video&v=$embedId",
        )
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
            body = runCatching {
                app.get(
                    url,
                    referer = referer ?: "$mainUrl/",
                    headers = mapOf("User-Agent" to ANICHIN_UA),
                ).text
            }.getOrNull()
        }
        if (body.isNullOrBlank()) return

        val candidates = linkedMapOf<Int, String>()

        // embedJS JSON: ua / mp4 maps
        runCatching {
            val start = body.indexOf('{')
            val end = body.lastIndexOf('}')
            if (start < 0 || end <= start) return@runCatching
            val json = JSONObject(body.substring(start, end + 1))
            listOf("ua", "mp4").forEach { key ->
                val obj = json.optJSONObject(key) ?: return@forEach
                obj.keys().forEach { qKey ->
                    val entry = obj.opt(qKey)
                    val stream = when (entry) {
                        is JSONObject -> entry.optString("url").ifBlank { entry.optString("file") }
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
            json.optString("hls_url").ifBlank { json.optString("hls") }
                .replace("\\/", "/")
                .takeIf { it.startsWith("http") }
                ?.let { candidates[Qualities.Unknown.value] = it }
        }

        // TESTINGCF page scrape: script contains mp4
        val scriptData = runCatching {
            // if body is HTML
            if (body.contains("<script", true)) {
                Regex("""\{"mp4[\s\S]*?"evt":\{""").find(body)?.value
                    ?: body
            } else body
        }.getOrDefault(body)

        Regex(""""url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(scriptData)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.contains("rumble", true) }
            .filterNot { isJunkStreamUrl(it) }
            .forEach { stream ->
                val q = Regex("""(\d{3,4})""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                candidates.putIfAbsent(normalizePlayQuality(q), stream)
            }

        if (candidates.isEmpty()) return

        candidates.entries
            .sortedByDescending { it.key }
            .forEach { (q, stream) ->
                when {
                    stream.contains(".m3u8", true) -> {
                        // Prefer master (may be multi-variant); no i-frame junk
                        emitHlsVariants(name, stream, mainUrl, callback)
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
                                )
                            }
                        )
                    }
                }
            }
    }
}
