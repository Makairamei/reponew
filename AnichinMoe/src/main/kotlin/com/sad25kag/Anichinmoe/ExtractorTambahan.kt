package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodStreamExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// --- 1. DoodStream / Doods Family ---
class DoodPlaymogo : DoodStreamExtractor() {
    override var name = "Doods"
    override var mainUrl = "https://playmogo.com"
}

class DoodMyvidplay : DoodStreamExtractor() {
    override var name = "Doods"
    override var mainUrl = "https://myvidplay.com"
}

// --- 2. VidHide / StreamHide Family ---
class Morencius : VidHidePro() {
    override var name = "Vidhide / Earnvids"
    override var mainUrl = "https://morencius.com"
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

// --- 4. StreamHG Family ---
class Hgcloud : StreamWishExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
}

class Streamhg : StreamWishExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.com"
}

class StreamhgSub : StreamWishExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.net"
}

// --- 5. TurboVIP / VidHide HLS Family ---
class Turbovidhls : VidHidePro() {
    override var name = "TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
}

// --- 6. D-Tube Extractor ---
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

// --- 7. StreamWish Family ---
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

// --- 8. Anichin Proxy Player (Anichin Player Embed Proxy) ---
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
        val response = app.get(url, referer = "https://anichin.moe/").text
        // Check for OK.ru embed inside proxy
        val okMatch = Regex("""src=["'](https?://ok\.ru/embed/[^"']+)["']""").find(response)
        if (okMatch != null) {
            val okUrl = okMatch.groupValues[1]
            com.lagradost.cloudstream3.utils.loadExtractor(okUrl, url, subtitleCallback, callback)
            return
        }
        // Check for Dailymotion embed inside proxy
        val dmMatch = Regex("""src=["'](https?://[^"']*dailymotion\.com/[^"']+)["']""").find(response)
        if (dmMatch != null) {
            val dmUrl = dmMatch.groupValues[1]
            com.lagradost.cloudstream3.utils.loadExtractor(dmUrl, url, subtitleCallback, callback)
            return
        }
        // Check for m3u8 or mp4
        val m3u8Match = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(response)
        if (m3u8Match != null) {
            generateM3u8(name, m3u8Match.groupValues[1], url).forEach(callback)
        }
    }
}
