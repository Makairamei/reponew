package com.sad25kag.animesail

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * MixDrop family — AnimeSail mirrors (m1xdrop / mixdrop.*).
 * No hard playability probe (probe Range often 403 while player GET works).
 * Always attach Referer/Origin/UA for Exo (avoids HTTP 2004).
 */
open class MixDropBase : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.ag"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String = "$mainUrl/e/$id"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""/(?:e|f)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        val embedUrl = if (id != null) "$mainUrl/e/$id" else url.replace("/f/", "/e/")

        val response = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }.getOrNull() ?: return

        val scriptChunks = linkedSetOf<String>()
        scriptChunks.add(response.text)
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isBlank()) return@forEach
            if (data.contains("MDCore", true) ||
                data.contains("wurl", true) ||
                data.contains("furl", true) ||
                data.contains("eval(function(p,a,c,k,e,d)")
            ) {
                scriptChunks.add(data)
            }
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(data) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { scriptChunks.add(it) }
            }
        }
        runCatching { getAndUnpack(response.text) }.getOrNull()?.let { scriptChunks.add(it) }

        val streamRegexes = listOf(
            Regex("""(?:MDCore\.)?wurl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:MDCore\.)?furl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](//[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        fun normalizeCandidate(raw: String): String? {
            val normalized = raw
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .trim()
            val absolute = when {
                normalized.startsWith("http://", true) || normalized.startsWith("https://", true) -> normalized
                normalized.startsWith("//") -> "https:$normalized"
                else -> return null
            }
            return absolute.takeIf {
                it.contains(".mp4", true) || it.contains(".m3u8", true)
            }
        }

        val candidates = linkedSetOf<String>()
        scriptChunks.forEach { blob ->
            streamRegexes.forEach { regex ->
                regex.findAll(blob).forEach { m ->
                    m.groupValues.getOrNull(1)?.let(::normalizeCandidate)?.let(candidates::add)
                }
            }
        }
        val streamUrl = candidates.firstOrNull() ?: return
        val type = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = type,
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class MixDropBz : MixDropBase() {
    override var mainUrl = "https://m1xdrop.bz"
}

class MixDropAg : MixDropBase() {
    override var mainUrl = "https://mixdrop.ag"
}

class MixDropTo : MixDropBase() {
    override var mainUrl = "https://mixdrop.to"
}

class MixDropClub : MixDropBase() {
    override var mainUrl = "https://mixdrop.club"
}

class MixDropCom : MixDropBase() {
    override var mainUrl = "https://mixdrop.com"
}

/** Mp4Upload — embed player.src unpack + headers */
open class Mp4UploadFix : ExtractorApi() {
    override var name = "Mp4Upload"
    override var mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    private val idMatch = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val srcRegex = Regex("""player\.src\(\s*\{[\w\W]*?src:\s*["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val srcRegex2 = Regex("""player\.src\(\s*["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val srcRegex3 = Regex("""src:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)

    override fun getExtractorUrl(id: String): String = "$mainUrl/embed-$id.html"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = idMatch.find(url)?.groupValues?.getOrNull(1)
        val realUrl = id?.let { "$mainUrl/embed-$it.html" } ?: url
        val watchReferer = "$mainUrl/"

        val response = runCatching {
            app.get(
                realUrl,
                referer = referer ?: watchReferer,
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }.getOrNull() ?: return

        val scriptBlobs = linkedSetOf<String>()
        scriptBlobs.add(response.text)
        runCatching { getAndUnpack(response.text) }.getOrNull()?.let { scriptBlobs.add(it) }
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isNotBlank()) {
                scriptBlobs.add(data)
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    runCatching { getAndUnpack(data) }.getOrNull()?.let { scriptBlobs.add(it) }
                }
            }
        }

        val streamUrl = scriptBlobs.firstNotNullOfOrNull { blob ->
            srcRegex.find(blob)?.groupValues?.getOrNull(1)?.trim()
                ?: srcRegex2.find(blob)?.groupValues?.getOrNull(1)?.trim()
                ?: srcRegex3.find(blob)?.groupValues?.getOrNull(1)?.trim()
        }?.takeIf { it.isNotBlank() } ?: return

        val quality = scriptBlobs.firstNotNullOfOrNull { blob ->
            Regex("""height\s*[:=]\s*["']?(\d{3,4})""", RegexOption.IGNORE_CASE)
                .find(blob)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: Qualities.Unknown.value

        val fixedStream = when {
            streamUrl.startsWith("http", true) -> streamUrl
            streamUrl.startsWith("//") -> "https:$streamUrl"
            else -> return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = fixedStream,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = watchReferer
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class Mp4UploadOrg : Mp4UploadFix() {
    override var mainUrl = "https://mp4upload.org"
}

/** Pixeldrain direct API file */
class Pixeldrain : ExtractorApi() {
    override var name = "Pixel"
    override var mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""/(?:u|api/file)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/').takeIf { it.length in 4..40 }
            ?: return

        val direct = "https://pixeldrain.com/api/file/$id"
        val page = "https://pixeldrain.com/u/$id"
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = direct,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = page
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to page,
                    "Accept" to "*/*",
                )
            }
        )
    }
}

/** Krakenfiles — GET only */
class Krakenfiles : ExtractorApi() {
    override var name = "Kraken"
    override var mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val page = runCatching {
            app.get(
                url,
                referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }.getOrNull() ?: return

        val body = page.text
        val candidates = linkedSetOf<String>()
        page.document.select("source[src], video[src], [data-src-url]").forEach { el ->
            listOf("src", "data-src-url").forEach { a ->
                el.attr(a).takeIf { it.startsWith("http") }?.let { candidates.add(it) }
            }
        }
        Regex("""https?://[^"'\\\s<>]+\.(?:mp4|m3u8)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { candidates.add(it.value.replace("\\/", "/")) }

        candidates.forEach { stream ->
            val type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(name, name, stream, type) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mainUrl,
                        "Accept" to "*/*",
                    )
                }
            )
        }
    }
}

/**
 * Doodstream family — AnimeSail labels this **"Dodo"**.
 * Site: aghanim.xyz/tools/redirect/?id=XXX → dood-like /e/ or /v/.
 */
open class DoodStreamSail : ExtractorApi() {
    override var name = "Dodo"
    override var mainUrl = "https://dood.watch"
    override val requiresReferer = true

    private val passMd5 = Regex("""(/pass_md5/[^"'\\\s]+)""")
    private val tokenRe = Regex("""[?&]token=([A-Za-z0-9]+)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: return

        val hosts = linkedSetOf(
            mainUrl.trimEnd('/'),
            "https://dood.watch",
            "https://dood.ws",
            "https://dood.li",
            "https://dood.so",
            "https://dood.to",
            "https://dood.la",
            "https://dood.pm",
            "https://dood.wf",
            "https://dood.yt",
            "https://dood.re",
            "https://d000d.com",
            "https://ds2play.com",
            "https://doply.net",
            "https://vide0.net",
            "https://myvidplay.com",
            "https://rasa-cintaku-semakin-berantai.xyz",
        )

        for (host in hosts) {
            val embed = "$host/e/$id"
            val page = runCatching {
                app.get(
                    embed,
                    referer = referer ?: "$host/",
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "$host/")),
                ).text
            }.getOrNull() ?: continue

            val md5Path = passMd5.find(page)?.groupValues?.getOrNull(1) ?: continue
            val md5Url = if (md5Path.startsWith("http")) md5Path else "$host$md5Path"
            val base = runCatching {
                app.get(
                    md5Url,
                    referer = embed,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embed),
                ).text.trim()
            }.getOrNull() ?: continue

            if (!base.startsWith("http")) continue

            val token = tokenRe.find(page)?.groupValues?.getOrNull(1).orEmpty()
            val stream = when {
                token.isNotBlank() && !base.contains("token=") ->
                    "$base${if (base.contains("?")) "&" else "?"}token=$token&expiry=${System.currentTimeMillis()}"
                else -> base
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embed,
                        "Accept" to "*/*",
                    )
                }
            )
            return
        }
    }
}

class DoodWatch : DoodStreamSail() {
    override var mainUrl = "https://dood.watch"
}

class DoodWs : DoodStreamSail() {
    override var mainUrl = "https://dood.ws"
}

class DoodLi : DoodStreamSail() {
    override var mainUrl = "https://dood.li"
}

class DoodRasa : DoodStreamSail() {
    override var mainUrl = "https://rasa-cintaku-semakin-berantai.xyz"
}
