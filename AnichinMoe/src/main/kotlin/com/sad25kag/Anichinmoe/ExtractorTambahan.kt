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
import com.lagradost.cloudstream3.utils.newExtractorLink

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// --- Helper unpacker ---
private suspend fun unpackAndEmitM3u8(
    sourceName: String,
    url: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).text
        val unpacked = getAndUnpack(html)
        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
        val matches = m3u8Regex.findAll(unpacked).map { it.value }.toList()
        
        if (matches.isNotEmpty()) {
            matches.forEach { streamUrl ->
                generateM3u8(sourceName, streamUrl, url).forEach(callback)
            }
        } else {
            // Direct regex check on html if unpack fails
            val directMatches = m3u8Regex.findAll(html).map { it.value }.toList()
            directMatches.forEach { streamUrl ->
                generateM3u8(sourceName, streamUrl, url).forEach(callback)
            }
        }
    } catch (e: Exception) {
        // Ignored
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

// --- 2. VidHide / StreamHide Family ---
class Morencius : ExtractorApi() {
    override val name = "Earnvids / Vidhide"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
    }
}

class VidHidePro1 : VidHidePro() {
    override var mainUrl = "https://filelions.live"
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

// --- 3. AbyssPlayer / NewPlayer Family ---
class Abyssplayer : StreamWishExtractor() {
    override var name = "New Player"
    override var mainUrl = "https://abyssplayer.com"
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
        callback: (ExtractorLink) -> Unit
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
        callback: (ExtractorLink) -> Unit
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

// --- 5. StreamRuby Extractor ---
class Rubyvidhub : ExtractorApi() {
    override val name = "Streamruby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        unpackAndEmitM3u8(name, url, referer ?: "https://anichin.moe/", callback)
    }
}

// --- 6. TurboVIP / VidHide HLS Family ---
class Turbovidhls : VidHidePro() {
    override var name = "TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
}

// --- 7. D-Tube Extractor ---
class Dtube : ExtractorApi() {
    override val name = "D-Tube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfter("?v=").substringBefore("&")
        if (videoId.isBlank()) return
        val ipfsUrl = "https://video.dtube.top/ipfs/$videoId"
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = ipfsUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P1080.value
            }
        )
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

// --- 9. Anichin Proxy Player (Anichin Player Embed Proxy) ---
class AnichinPlayerProxy : ExtractorApi() {
    override val name = "Anichin Player"
    override val mainUrl = "https://anichin-player.web.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headersMap = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://anichin.moe/"
        )
        val response = runCatching { app.get(url, headers = headersMap, referer = "https://anichin.moe/").text }.getOrNull() ?: return
        
        // 1. Dailymotion Parsing
        val dmMatch = Regex("""video=([A-Za-z0-9]+)""").find(response) 
            ?: Regex("""src=["'](https?://[^"']*dailymotion\.com/[^"']+)["']""").find(response)
        if (dmMatch != null) {
            val videoId = dmMatch.groupValues[1]
            val dmUrl = if (videoId.startsWith("http")) videoId else "https://www.dailymotion.com/embed/video/$videoId"
            com.lagradost.cloudstream3.utils.loadExtractor(dmUrl, "https://anichin.moe/", subtitleCallback, callback)
        }

        // 2. OK.ru Parsing
        val okMatch = Regex("""src=["'](https?://ok\.ru/videoembed/[0-9]+)["']""").find(response)
            ?: Regex("""ok=([0-9]+)""").find(url)
        if (okMatch != null) {
            val okId = okMatch.groupValues[1]
            val okUrl = if (okId.startsWith("http")) okId else "https://ok.ru/videoembed/$okId"
            com.lagradost.cloudstream3.utils.loadExtractor(okUrl, "https://anichin.moe/", subtitleCallback, callback)
        }

        // 3. Inner Iframe (Streamruby, Vidhide, Earnvids, Abyss, dll)
        Regex("""src=["'](https?://[^"']+)["']""").findAll(response).forEach { match ->
            val innerUrl = match.groupValues[1]
            if (!innerUrl.contains("cloudflare") && !innerUrl.contains("anichin-player")) {
                com.lagradost.cloudstream3.utils.loadExtractor(innerUrl, "https://anichin.moe/", subtitleCallback, callback)
            }
        }

        // 4. Direct m3u8 Stream
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(response).forEach { match ->
            generateM3u8(name, match.groupValues[1], "https://anichin.moe/").forEach(callback)
        }
    }
}
