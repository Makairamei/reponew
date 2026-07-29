package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Dailymotion.
 * geo.dailymotion.com often returns 403 from non-browser clients — always fall back to
 * www.dailymotion.com/player/metadata/video/{id} which works with the public access id
 * (k… / x…) used by anichin-player.web.id?url=…
 */
class Geodailymotion : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Reuse robust Dailymotion path (geo JSON is flaky / 403)
        Dailymotion().getUrl(url, referer, subtitleCallback, callback)
    }
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"
    private val geoBaseUrl = "https://geo.dailymotion.com"

    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: url
        val id = getVideoId(embedUrl)
            ?: getGeoAccessId(embedUrl)
            ?: Regex("""(?:video[=/]|url=)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
            ?: return

        // 1) Official player metadata (works for both k… public ids and x… canonical ids)
        if (resolveMetadataVideo(id, embedUrl, referer, subtitleCallback, callback)) return

        // 2) geo JSON (may 403 — best effort)
        if (embedUrl.contains("geo.dailymotion.com", true) || id.startsWith("k")) {
            resolveGeoPlayer(embedUrl, id, referer, subtitleCallback, callback)
        }
    }

    private suspend fun resolveGeoPlayer(
        embedUrl: String,
        accessId: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedder = URLEncoder.encode(referer ?: "https://anichin.moe/", "UTF-8")
        val metadataUrl = "$geoBaseUrl/video/$accessId.json?legacy=true&embedder=$embedder"
        val response = runCatching {
            app.get(
                metadataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: "https://anichin.moe/"),
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        emitSubtitles(json, subtitleCallback)

        val urls = extractQualityUrls(json)
        if (urls.isNotEmpty()) {
            urls.forEach { videoUrl -> getStream(videoUrl, name, embedUrl, callback) }
            return true
        }

        val canonicalId = json.optString("id").trim().takeIf { it.matches(videoIdRegex) } ?: return false
        return resolveMetadataVideo(canonicalId, embedUrl, referer, subtitleCallback, callback)
    }

    private suspend fun resolveMetadataVideo(
        id: String,
        embedUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = runCatching {
            app.get(
                metaDataUrl,
                referer = referer ?: "https://anichin.moe/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: "https://anichin.moe/"),
                    "Accept" to "application/json,text/plain,*/*",
                    "Origin" to "https://www.dailymotion.com",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull()
        val urls = if (json != null) {
            emitSubtitles(json, subtitleCallback)
            // Prefer auto HLS master; generateM3u8 expands qualities
            extractQualityUrls(json)
        } else {
            Regex(""""url"\s*:\s*"([^"]+)"""")
                .findAll(response)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.contains(".m3u8", true) }
                .distinct()
                .toList()
        }

        // Metadata may remap public k-id → canonical x-id; also try that
        val canonical = json?.optString("id")?.trim().orEmpty()
        val finalUrls = urls.ifEmpty {
            if (canonical.isNotBlank() && canonical != id && canonical.matches(videoIdRegex)) {
                return resolveMetadataVideo(canonical, embedUrl, referer, subtitleCallback, callback)
            }
            emptyList()
        }

        finalUrls.forEach { videoUrl ->
            getStream(videoUrl, name, "https://www.dailymotion.com/", callback)
        }
        return finalUrls.isNotEmpty()
    }

    private fun extractQualityUrls(json: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        val qualities = json.optJSONObject("qualities") ?: return emptyList()

        // Prefer "auto" first (full ladder), then discrete keys
        val keys = mutableListOf<String>()
        if (qualities.has("auto")) keys.add("auto")
        qualities.keys().forEach { k -> if (k != "auto") keys.add(k) }

        keys.forEach { quality ->
            val entries = qualities.optJSONArray(quality) ?: return@forEach
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val type = item.optString("type").lowercase()
                val url = item.optString("url").trim().replace("\\/", "/")
                    .takeIf { it.isNotBlank() } ?: continue
                if (type.contains("mpegurl") || type.contains("x-mpegurl") || url.contains(".m3u8", true)) {
                    urls.add(url)
                }
            }
        }
        return urls.toList()
    }

    private fun emitSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = json.optJSONObject("subtitles") ?: return
        subtitles.keys().forEach { lang ->
            val value = subtitles.opt(lang)
            val entries = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("data") ?: JSONArray().put(value)
                else -> JSONArray()
            }
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val label = item.optString("label", lang).ifBlank { lang }
                val urls = item.optJSONArray("urls")
                if (urls != null) {
                    for (urlIndex in 0 until urls.length()) {
                        val subUrl = urls.optString(urlIndex).trim()
                        if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(url = subUrl, lang = label))
                    }
                } else {
                    val subUrl = item.optString("url").trim()
                    if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(url = subUrl, lang = label))
                }
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("geo.dailymotion.com", true)) return url
        if (url.contains("dailymotion.com", true)) return url
        if (url.contains("dai.ly", true)) return url
        // anichin-player?url=kXXXX
        if (url.contains("url=", true) && Regex("""[?&]url=([A-Za-z0-9]+)""").containsMatchIn(url)) return url
        return null
    }

    private fun getGeoAccessId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        return listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)[?&]url=([A-Za-z0-9]+)"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)\.json"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(decoded)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.matches(videoIdRegex) }
    }

    private fun getVideoId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val id = when {
            decoded.contains("dai.ly", true) -> URI(decoded).path.trim('/').substringBefore("/")
            decoded.contains("geo.dailymotion.com", true) -> getGeoAccessId(decoded).orEmpty()
            decoded.contains("dailymotion.com", true) -> URI(decoded).path.substringAfterLast("/")
            else -> getGeoAccessId(decoded).orEmpty()
        }
        return if (id.matches(videoIdRegex)) id else null
    }

    private suspend fun getStream(
        streamLink: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        emitHlsVariants(
            source = name,
            streamUrl = streamLink,
            referer = referer,
            callback = callback,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://www.dailymotion.com/",
                "Origin" to "https://www.dailymotion.com",
                "Accept" to "*/*",
            ),
        )
    }
}
