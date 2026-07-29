package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Unpack packed JS / raw HTML and emit HLS variants (highest quality first, 818→1080).
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
            headers = mapOf("User-Agent" to ANICHIN_UA, "Referer" to referer),
            referer = referer,
        ).text

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else html
        }.getOrDefault(html)

        val candidates = linkedSetOf<String>()

        // Prefer absolute hls2 over relative hls4 when both exist (VidHide / Earnvids)
        Regex(""""hls2"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""["']hls\d*["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.value.replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { candidates.add(it) }

        val absolute = candidates.mapNotNull { raw ->
            when {
                raw.startsWith("http", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> {
                    val origin = Regex("""^(https?://[^/]+)""").find(url)?.value ?: return@mapNotNull null
                    origin + raw
                }
                else -> null
            }
        }.distinct()

        // Prefer CDN absolute masters first
        val ordered = absolute.sortedBy { if (it.contains("acek-cdn", true) || it.contains("http", true) && !it.contains(url.substringAfter("://").substringBefore("/")) ) 0 else 1 }

        ordered.forEach { streamUrl ->
            val origin = Regex("""^(https?://[^/]+)""").find(url)?.value ?: referer
            emitHlsVariants(
                source = sourceName,
                streamUrl = streamUrl,
                referer = url,
                callback = callback,
                headers = mapOf(
                    "User-Agent" to ANICHIN_UA,
                    "Referer" to url,
                    "Origin" to origin,
                    "Accept" to "*/*",
                ),
            )
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
    override val name = "Vidhide"
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

/** anichin.rpmvid.com SPA — encrypted API; try sibling embeds only (no fake links) */
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
            }.orEmpty()
        if (id.isBlank()) return

        val mirrors = listOf(
            "https://rpmshare.com/embed/$id",
            "https://rpmshare.com/v/$id",
            "https://endstar.rpmplay.me/embed/$id",
        )
        for (mirror in mirrors) {
            unpackAndEmitM3u8(name, mirror, referer ?: mainUrl, callback)
            runCatching { loadExtractor(mirror, referer ?: mainUrl, subtitleCallback, callback) }
        }

        // Plain m3u8 only if API returns readable JSON (encrypted hex is ignored)
        val apiUrls = listOf("$mainUrl/api/v1/video?id=$id", "$mainUrl/api/v1/info?id=$id")
        for (api in apiUrls) {
            val body = runCatching {
                app.get(
                    api,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to ANICHIN_UA,
                        "Accept" to "application/json,text/plain,*/*",
                        "Origin" to mainUrl,
                    ),
                ).text
            }.getOrNull() ?: continue

            if (!body.contains(".m3u8", true) && !body.trimStart().startsWith("{")) continue

            Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
                .findAll(body)
                .forEach { emitHlsVariants(name, it.value, mainUrl, callback) }

            runCatching {
                val json = JSONObject(body)
                listOf("file", "url", "src", "hls", "video", "source")
                    .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
                    .forEach { stream ->
                        if (stream.contains(".m3u8", true)) {
                            emitHlsVariants(name, stream, mainUrl, callback)
                        } else if (stream.startsWith("http")) {
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

// --- 3. Abyss / New Player ---
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
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)

        val id = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (id.isBlank()) return
        val endpoints = listOf(
            "$mainUrl/ajax/embed-1/getSources?id=$id",
            "https://abyssplayer.com/ajax/embed-1/getSources?id=$id",
        )
        for (ep in endpoints) {
            val body = runCatching {
                app.get(
                    ep,
                    referer = url,
                    headers = mapOf(
                        "User-Agent" to ANICHIN_UA,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text
            }.getOrNull() ?: continue
            Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
                .findAll(body)
                .forEach { emitHlsVariants(name, it.value, url, callback) }
            Regex("""https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*""")
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

class AbyssplayerRoot : Abyssplayer() {
    override val mainUrl = "https://abyssplayer.com"
}

// --- 4. StreamHG / Hanerix ---
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
        unpackAndEmitM3u8(name, url.replace("hgcloud.to", "hanerix.com"), referer ?: "https://anichin.moe/", callback)
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

// --- 5. StreamRuby alias ---
class Rubyvidhub : StreamRuby() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubyvidhub.com"
}

// --- 6. TurboVIP direct HLS ---
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
        val page = runCatching {
            app.get(
                url,
                referer = referer ?: "https://anichin.moe/",
                headers = mapOf("User-Agent" to ANICHIN_UA),
            ).text
        }.getOrNull() ?: return

        val m3u8s = linkedSetOf<String>()
        Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""", RegexOption.IGNORE_CASE)
            .findAll(page)
            .map { it.value.replace("\\/", "/") }
            .forEach { m3u8s.add(it) }

        if (m3u8s.isEmpty()) {
            unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
            return
        }

        val headers = mapOf(
            "User-Agent" to ANICHIN_UA,
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "Accept" to "*/*",
        )

        // Expand each master; Turbo soft-cap ≤720 (1080 remote often fails)
        m3u8s.forEach { stream ->
            emitHlsVariants(
                source = name,
                streamUrl = stream,
                referer = mainUrl,
                callback = callback,
                headers = headers,
                maxQuality = Qualities.P720.value,
            )
        }
    }
}

// --- 7. D-Tube: dead on modern anichin (UUID not IPFS CIDs) — do NOT emit fake unplayable links ---
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

        // Only emit if a gateway actually returns media (HEAD/GET small check via GET)
        val gateways = listOf(
            "https://media.d.tube/ipfs/$videoId",
            "https://video.dtube.top/ipfs/$videoId",
            "https://ipfs.io/ipfs/$videoId",
        )
        for (gw in gateways) {
            val ok = runCatching {
                val resp = app.get(
                    gw,
                    referer = "https://d.tube/",
                    headers = mapOf("User-Agent" to ANICHIN_UA, "Range" to "bytes=0-64"),
                )
                val ct = resp.headers["Content-Type"].orEmpty().lowercase()
                val body = resp.text
                ct.startsWith("video/") || ct.contains("octet-stream") ||
                    body.startsWith("\u0000") || body.contains("ftyp") ||
                    (resp.code in 200..299 && !body.trimStart().startsWith("<!") && body.length > 100)
            }.getOrDefault(false)
            if (ok) {
                callback(
                    newExtractorLink(name, name, gw, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://d.tube/"
                        this.headers = mapOf("User-Agent" to ANICHIN_UA, "Referer" to "https://d.tube/")
                    }
                )
                return
            }
        }
        // Dead source — emit nothing (prevents "D-Tube x3" unplayable junk)
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

// --- 9. Anichin Proxy Player (OK.ru / Dailymotion wrapper) ---
// Name is neutral; final links come from OK.ru / Dailymotion extractors.
class AnichinPlayerProxy : ExtractorApi() {
    override val name = "AnichinProxy"
    override val mainUrl = "https://anichin-player.web.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headersMap = mapOf(
            "User-Agent" to ANICHIN_UA,
            "Referer" to "https://anichin.moe/",
        )
        val response = runCatching {
            app.get(url, headers = headersMap, referer = "https://anichin.moe/").text
        }.getOrNull().orEmpty()

        // Prefer query params first (avoid double work)
        Regex("""[?&]ok=([0-9]+)""").find(url)?.groupValues?.getOrNull(1)?.let { okId ->
            loadExtractor("https://ok.ru/videoembed/$okId", "https://anichin.moe/", subtitleCallback, callback)
            return
        }

        Regex("""[?&]url=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)?.let { dmId ->
            // Direct metadata path via Dailymotion extractor (not geo-only)
            loadExtractor(
                "https://www.dailymotion.com/embed/video/$dmId",
                "https://anichin.moe/",
                subtitleCallback,
                callback,
            )
            return
        }

        Regex("""src=["'](https?://ok\.ru/videoembed/[0-9]+)["']""", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.getOrNull(1)?.let {
                loadExtractor(it, "https://anichin.moe/", subtitleCallback, callback)
                return
            }

        Regex("""video=([A-Za-z0-9]+)""").find(response)?.groupValues?.getOrNull(1)?.let { videoId ->
            loadExtractor(
                "https://www.dailymotion.com/embed/video/$videoId",
                "https://anichin.moe/",
                subtitleCallback,
                callback,
            )
            return
        }

        Regex("""src=["'](https?://[^"']*dailymotion\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.getOrNull(1)?.let {
                loadExtractor(it.replace("&amp;", "&"), "https://anichin.moe/", subtitleCallback, callback)
                return
            }

        // Other nested players
        Regex("""src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach { match ->
            val innerUrl = match.groupValues[1].replace("&amp;", "&")
            if (!innerUrl.contains("cloudflare", true) &&
                !innerUrl.contains("anichin-player", true) &&
                !innerUrl.contains("googletagmanager", true) &&
                !innerUrl.contains("imasdk", true)
            ) {
                loadExtractor(innerUrl, "https://anichin.moe/", subtitleCallback, callback)
            }
        }
    }
}
