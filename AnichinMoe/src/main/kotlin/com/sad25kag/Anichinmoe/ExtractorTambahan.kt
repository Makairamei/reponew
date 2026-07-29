package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Unpack packed JS / raw HTML and emit every m3u8 variant via generateM3u8
 * so CloudStream lists 360/480/720/1080 when the master playlist has them.
 */
private suspend fun unpackAndEmitM3u8(
    sourceName: String,
    url: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
) {
    try {
        val html = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
            referer = referer,
        ).text

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else html
        }.getOrDefault(html)

        val candidates = linkedSetOf<String>()

        // links={"hls2":"https://...m3u8","hls4":"..."}  (VidHide / Earnvids family)
        Regex("""["']hls\d*["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""https?://[^\"'\s\\<>]+\.m3u8[^\"'\s\\<>]*""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.value.replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { candidates.add(it) }

        // Relative hls paths → absolute against page host
        candidates.mapNotNull { raw ->
            when {
                raw.startsWith("http", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> {
                    val origin = Regex("""^(https?://[^/]+)""").find(url)?.value ?: return@mapNotNull null
                    origin + raw
                }
                else -> null
            }
        }.distinct().forEach { streamUrl ->
            generateM3u8(sourceName, streamUrl, url).forEach(callback)
        }
    } catch (_: Exception) {
        // ignore per-server failures
    }
}

// --- 1. DoodStream / Doods Family ---
class DoodPlaymogo : DoodLaExtractor() {
    override var name = "Doods"
    override var mainUrl = "https://playmogo.com"
}

class DoodMyvidplay : DoodLaExtractor() {
    override var name = "Doods"
    override var mainUrl = "https://myvidplay.com"
}

// --- 2. VidHide / StreamHide / Earnvids Family ---
class Morencius : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
    }
}

class Rpmshare : VidHidePro() {
    override var name = "RpmShare"
    override var mainUrl = "https://rpmshare.com"
}

class RpmshareSub : VidHidePro() {
    override var name = "RpmShare"
    override var mainUrl = "https://rpmshare.net"
}

class Rpmplay : VidHidePro() {
    override var name = "RpmShare"
    override var mainUrl = "https://endstar.rpmplay.me"
}

/** anichin.rpmvid.com SPA player — try video API + common embed mirrors */
class Rpmvid : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://anichin.rpmvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = url.substringAfter("#").substringBefore("&").substringBefore("?").trim()
            .ifBlank {
                Regex("""/(?:embed|e|v|t)/([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            }
            .orEmpty()
        if (id.isBlank()) return

        // Try classic VidHide-style embeds on sibling hosts (no ads wall when direct)
        val mirrors = listOf(
            "https://rpmshare.com/embed/$id",
            "https://rpmshare.com/v/$id",
            "https://endstar.rpmplay.me/embed/$id",
            "https://morencius.com/embed/$id",
        )
        for (mirror in mirrors) {
            unpackAndEmitM3u8(name, mirror, referer ?: mainUrl, callback)
            runCatching { loadExtractor(mirror, referer ?: mainUrl, subtitleCallback, callback) }
        }

        // Best-effort JSON API (may be encrypted hex — skip if not plain m3u8/json)
        val apiUrls = listOf(
            "$mainUrl/api/v1/video?id=$id",
            "$mainUrl/api/v1/info?id=$id",
        )
        for (api in apiUrls) {
            val body = runCatching {
                app.get(
                    api,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json,text/plain,*/*",
                        "Origin" to mainUrl,
                    ),
                ).text
            }.getOrNull() ?: continue

            if (body.contains(".m3u8", true)) {
                Regex("""https?://[^\"'\s\\<>]+\.m3u8[^\"'\s\\<>]*""")
                    .findAll(body)
                    .forEach { generateM3u8(name, it.value, mainUrl).forEach(callback) }
            }
            runCatching {
                val json = JSONObject(body)
                listOf("file", "url", "src", "hls", "video", "source")
                    .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
                    .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) }
                    .forEach { stream ->
                        if (stream.contains(".m3u8", true)) {
                            generateM3u8(name, stream, mainUrl).forEach(callback)
                        } else {
                            callback(
                                newExtractorLink(name, name, stream, ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
            }
        }
    }
}

class Earnvids : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://earnvids.com"
}

class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Dhtpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://dhtpre.com"
}

class Peytonepre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://peytonepre.com"
}

// --- 3. Abyss / New Player (host is play.abyssplayer.com) ---
open class Abyssplayer : ExtractorApi() {
    override val name = "New Player"
    override val mainUrl = "https://play.abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Prefer direct m3u8/mp4 if page exposes them; encrypted "datas" blob is ads-gated.
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)

        // Fallback: try StreamWish-style sources endpoint used by some abyss mirrors
        val id = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (id.isNotBlank()) {
            val endpoints = listOf(
                "$mainUrl/ajax/embed-1/getSources?id=$id",
                "https://abyssplayer.com/ajax/embed-1/getSources?id=$id",
            )
            for (ep in endpoints) {
                val body = runCatching {
                    app.get(ep, referer = url, headers = mapOf("User-Agent" to USER_AGENT, "X-Requested-With" to "XMLHttpRequest")).text
                }.getOrNull() ?: continue
                Regex("""https?://[^\"'\s\\<>]+\.m3u8[^\"'\s\\<>]*""")
                    .findAll(body)
                    .forEach { generateM3u8(name, it.value, url).forEach(callback) }
                Regex("""https?://[^\"'\s\\<>]+\.mp4[^\"'\s\\<>]*""")
                    .findAll(body)
                    .forEach { mp4 ->
                        callback(
                            newExtractorLink(name, name, mp4.value, ExtractorLinkType.VIDEO) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
            }
        }
    }
}

class AbyssplayerRoot : Abyssplayer() {
    override val mainUrl = "https://abyssplayer.com"
}

// --- 4. StreamHG / Hanerix Family ---
class Hgcloud : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val targetUrl = url.replace("hgcloud.to", "hanerix.com")
        unpackAndEmitM3u8(name, targetUrl, referer ?: "https://anichin.moe/", callback)
    }
}

class Hanerix : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://hanerix.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
    }
}

class Streamhg : StreamWishExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.com"
}

class StreamhgSub : StreamWishExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.net"
}

// --- 5. StreamRuby alias host ---
class Rubyvidhub : ExtractorApi() {
    override val name = "Streamruby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Delegate to robust StreamRuby implementation via shared unpack
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
        // Also try POST /dl path used by StreamRuby
        val id = Regex("""embed-([a-zA-Z0-9]+)\.html""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1) ?: return
        val response = runCatching {
            app.post(
                "$mainUrl/dl",
                data = mapOf("op" to "embed", "file_code" to id, "auto" to "1", "referer" to ""),
                referer = referer ?: "https://anichin.moe/",
            ).text
        }.getOrNull() ?: return
        val unpacked = runCatching {
            if (!getPacked(response).isNullOrEmpty()) getAndUnpack(response) else response
        }.getOrDefault(response)
        Regex("""https?://[^\"'\s\\<>]+\.m3u8[^\"'\s\\<>]*""")
            .findAll(unpacked)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .forEach { generateM3u8(name, it, mainUrl).forEach(callback) }
    }
}

// --- 6. TurboVIP direct HLS (path /t/ID — not VidHide embed) ---
class Turbovidhls : ExtractorApi() {
    override val name = "TurboVIP"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
    }
}

// --- 7. D-Tube ---
class Dtube : ExtractorApi() {
    override val name = "D-Tube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = url.substringAfter("?v=").substringBefore("&").trim()
        if (videoId.isBlank()) return

        // Try several known IPFS/gateway layouts
        val candidates = listOf(
            "https://video.dtube.top/ipfs/$videoId",
            "https://player.d.tube/ipfs/$videoId",
            "https://ipfs.io/ipfs/$videoId",
        )
        for (ipfsUrl in candidates) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = ipfsUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://d.tube/"
                }
            )
        }
    }
}

// --- 8. StreamWish Family ---
class Newplayr : StreamWishExtractor() {
    override var name = "NewPlayr"
    override var mainUrl = "https://newplayr.com"
}

class NewplayrSub : StreamWishExtractor() {
    override var name = "NewPlayr"
    override var mainUrl = "https://newplayr.org"
}

class StreamWish : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class StreamWishSub : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.com"
}

// --- 9. Anichin Proxy Player ---
class AnichinPlayerProxy : ExtractorApi() {
    override val name = "Anichin Player"
    override val mainUrl = "https://anichin-player.web.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headersMap = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://anichin.moe/",
        )
        val response = runCatching {
            app.get(url, headers = headersMap, referer = "https://anichin.moe/").text
        }.getOrNull() ?: return

        // OK.ru from query or iframe
        Regex("""[?&]ok=([0-9]+)""").find(url)?.groupValues?.getOrNull(1)?.let { okId ->
            loadExtractor("https://ok.ru/videoembed/$okId", "https://anichin.moe/", subtitleCallback, callback)
        }
        Regex("""src=["'](https?://ok\.ru/videoembed/[0-9]+)["']""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .forEach { loadExtractor(it.groupValues[1], "https://anichin.moe/", subtitleCallback, callback) }

        // Dailymotion from query url= or iframe
        Regex("""[?&]url=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)?.let { dmId ->
            loadExtractor(
                "https://geo.dailymotion.com/player.html?video=$dmId",
                "https://anichin.moe/",
                subtitleCallback,
                callback,
            )
            loadExtractor(
                "https://www.dailymotion.com/embed/video/$dmId",
                "https://anichin.moe/",
                subtitleCallback,
                callback,
            )
        }
        Regex("""video=([A-Za-z0-9]+)""").findAll(response).forEach { m ->
            val videoId = m.groupValues[1]
            loadExtractor(
                "https://geo.dailymotion.com/player.html?video=$videoId",
                "https://anichin.moe/",
                subtitleCallback,
                callback,
            )
        }
        Regex("""src=["'](https?://[^"']*dailymotion\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .forEach { loadExtractor(it.groupValues[1].replace("&amp;", "&"), "https://anichin.moe/", subtitleCallback, callback) }

        // Nested iframes → other extractors
        Regex("""src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach { match ->
            val innerUrl = match.groupValues[1].replace("&amp;", "&")
            if (!innerUrl.contains("cloudflare", true) &&
                !innerUrl.contains("anichin-player", true) &&
                !innerUrl.contains("googletagmanager", true)
            ) {
                loadExtractor(innerUrl, "https://anichin.moe/", subtitleCallback, callback)
            }
        }

        // Direct m3u8
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            .findAll(response)
            .forEach { generateM3u8(name, it.groupValues[1], "https://anichin.moe/").forEach(callback) }
    }
}
