package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Dailymotion — aligned with TESTINGCF/DailymotionProvider for working audio.
 *
 * Why silent before:
 * DM masters use separate EXT-X-MEDIA AUDIO groups. Expanding to video-only leaves
 * (generateM3u8) drops audio on many CloudStream/Exo builds.
 *
 * Fix: emit the **master** m3u8 only, with geo player embed referer (player x95ee).
 */
class Geodailymotion : Dailymotion() {
    override var name = "Dailymotion"
    override var mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override var mainUrl = "https://www.dailymotion.com"
    override var name = "Dailymotion"
    override val requiresReferer = false

    private val baseUrl = "https://www.dailymotion.com"
    private val geoBaseUrl = "https://geo.dailymotion.com"
    private val defaultPlayerId = "x95ee" // TESTINGCF DailymotionProvider
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = getVideoId(url)
            ?: getGeoAccessId(url)
            ?: Regex("""(?:video[=/]|url=)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
            ?: return

        if (!resolveMetadataVideo(id, referer, subtitleCallback, callback)) {
            resolveGeoPlayer(id, referer, subtitleCallback, callback)
        }
    }

    private fun playerEmbedUrl(videoId: String): String =
        "$geoBaseUrl/player/$defaultPlayerId.html?video=$videoId"

    private suspend fun resolveGeoPlayer(
        accessId: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = playerEmbedUrl(accessId)
        val embedder = URLEncoder.encode(referer ?: "https://anichin.moe/", "UTF-8")
        val metadataUrl = "$geoBaseUrl/video/$accessId.json?legacy=true&embedder=$embedder"
        val response = runCatching {
            app.get(
                metadataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to baseUrl,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        emitSubtitles(json, subtitleCallback)
        val urls = extractQualityUrls(json)
        if (urls.isNotEmpty()) {
            urls.forEach { emitMaster(it, accessId, callback) }
            return true
        }
        val canonicalId = json.optString("id").trim().takeIf { it.matches(videoIdRegex) } ?: return false
        return resolveMetadataVideo(canonicalId, referer, subtitleCallback, callback)
    }

    private suspend fun resolveMetadataVideo(
        id: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = playerEmbedUrl(id)
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = runCatching {
            app.get(
                metaDataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to baseUrl,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull()
        val urls = if (json != null) {
            emitSubtitles(json, subtitleCallback)
            extractQualityUrls(json)
        } else {
            Regex(""""url"\s*:\s*"([^"]+)"""")
                .findAll(response)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.contains(".m3u8", true) }
                .distinct()
                .toList()
        }

        val canonical = json?.optString("id")?.trim().orEmpty()
        if (urls.isEmpty() && canonical.isNotBlank() && canonical != id && canonical.matches(videoIdRegex)) {
            return resolveMetadataVideo(canonical, referer, subtitleCallback, callback)
        }

        val playId = canonical.takeIf { it.matches(videoIdRegex) } ?: id
        // Prefer "auto" master only (first url from extract order)
        urls.firstOrNull()?.let { emitMaster(it, playId, callback) }
        return urls.isNotEmpty()
    }

    private fun extractQualityUrls(json: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        val qualities = json.optJSONObject("qualities") ?: return emptyList()
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

    private suspend fun emitSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = json.optJSONObject("subtitles") ?: return
        val langs = subtitles.keys().asSequence().toList()
        for (lang in langs) {
            val value = subtitles.opt(lang)
            val entries = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("data") ?: JSONArray().put(value)
                else -> JSONArray()
            }
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val label = item.optString("label", lang).ifBlank { lang }
                val urlsArr = item.optJSONArray("urls")
                if (urlsArr != null) {
                    for (urlIndex in 0 until urlsArr.length()) {
                        val subUrl = urlsArr.optString(urlIndex).trim()
                        if (subUrl.isNotBlank()) {
                            subtitleCallback(newSubtitleFile(label, subUrl))
                        }
                    }
                } else {
                    val subUrl = item.optString("url").trim()
                    if (subUrl.isNotBlank()) {
                        subtitleCallback(newSubtitleFile(label, subUrl))
                    }
                }
            }
        }
    }

    private fun getGeoAccessId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        return listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)[?&]url=([A-Za-z0-9]+)"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)\.json"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { it.find(decoded)?.groupValues?.getOrNull(1) }
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

    /** Single master playlist — keeps AUDIO groups (sound). */
    private suspend fun emitMaster(streamLink: String, videoId: String, callback: (ExtractorLink) -> Unit) {
        val embedUrl = playerEmbedUrl(videoId)
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamLink,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = embedUrl
                // Unknown → player adaptive; avoids fake multi-quality silent leaves
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to baseUrl,
                    "Accept" to "*/*",
                )
            }
        )
    }
}
