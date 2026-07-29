package com.sad25kag.animesail

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

private val SAIL_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** Shared MixDrop family (m1xdrop / mixdrop mirrors used by AnimeSail) */
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
        val embedUrl = url
            .replace("/f/", "/e/")
            .replace("/embed-", "/e/")
            .let { u ->
                // normalize host to this extractor mainUrl when same path
                val id = Regex("""/(?:e|f)/([A-Za-z0-9]+)""").find(u)?.groupValues?.getOrNull(1)
                if (id != null) "$mainUrl/e/$id" else u
            }

        val response = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to SAIL_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
                ),
            )
        }.getOrNull() ?: return

        val scriptChunks = linkedSetOf<String>()
        scriptChunks.add(response.text)
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isBlank()) return@forEach
            scriptChunks.add(data)
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(data) }.getOrNull()?.let { scriptChunks.add(it) }
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
        if (candidates.isEmpty()) return

        // Prefer first candidate that looks like CDN media; don't hard-fail on HEAD probe
        // (probe Range can 403 while player GET works — caused "only kraken works")
        val streamUrl = candidates.firstOrNull { it.contains(".mp4", true) || it.contains(".m3u8", true) }
            ?: return

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
                // Critical headers for Exo — missing Referer/Origin → HTTP 2004 / remote errors
                this.headers = mapOf(
                    "User-Agent" to SAIL_UA,
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

class MixDropCom : MixDropBase() {
    override var mainUrl = "https://mixdrop.com"
}

class MixDropTo : MixDropBase() {
    override var mainUrl = "https://mixdrop.to"
}

class MixDropClub : MixDropBase() {
    override var mainUrl = "https://mixdrop.club"
}

class MixDropAg : MixDropBase() {
    override var mainUrl = "https://mixdrop.ag"
}

/** Mp4Upload family */
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
                headers = mapOf("User-Agent" to SAIL_UA, "Referer" to (referer ?: watchReferer)),
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
                    "User-Agent" to SAIL_UA,
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

/** Krakenfiles */
open class Krakenfiles : ExtractorApi() {
    override var name = "Kraken"
    override var mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""/(?:view|embed)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/')

        val pageUrl = when {
            url.contains("/embed/", true) -> url
            id.isNotBlank() -> "$mainUrl/embed-video/$id"
            else -> url
        }

        val doc = runCatching {
            app.get(
                pageUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to SAIL_UA),
            )
        }.getOrNull() ?: return

        val body = doc.text
        val candidates = linkedSetOf<String>()

        // HTML5 source / data-src-url
        doc.document.select("source[src], video[src], [data-src-url], [data-file]").forEach { el ->
            listOf("src", "data-src-url", "data-file").forEach { attr ->
                el.attr(attr).takeIf { it.startsWith("http") }?.let { candidates.add(it) }
            }
        }

        Regex("""https?://[^"'\\s<>]+\.(?:mp4|m3u8)[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { candidates.add(it.value.replace("\\/", "/")) }

        Regex(""""(?:url|file|src|downloadUrl)"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { candidates.add(it.groupValues[1].replace("\\/", "/")) }

        // POST get-url pattern used by some kraken embeds
        if (candidates.isEmpty() && id.isNotBlank()) {
            val post = runCatching {
                app.post(
                    "$mainUrl/download/$id",
                    data = mapOf("download" to "yes"),
                    referer = pageUrl,
                    headers = mapOf("User-Agent" to SAIL_UA, "X-Requested-With" to "XMLHttpRequest"),
                ).text
            }.getOrNull().orEmpty()
            Regex("""https?://[^"'\\s<>]+\.mp4[^"'\\s<>]*""").findAll(post)
                .forEach { candidates.add(it.value) }
        }

        candidates
            .filter { it.contains(".mp4", true) || it.contains(".m3u8", true) }
            .distinct()
            .forEach { stream ->
                val type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val q = getQualityFromName(stream).takeIf { it > 0 } ?: Qualities.Unknown.value
                callback(
                    newExtractorLink(name, name, stream, type) {
                        this.referer = mainUrl
                        this.quality = q
                        this.headers = mapOf(
                            "User-Agent" to SAIL_UA,
                            "Referer" to mainUrl,
                            "Accept" to "*/*",
                        )
                    }
                )
            }
    }
}

class KrakenfilesTo : Krakenfiles() {
    override var mainUrl = "https://krakenfiles.to"
}

/** Pixeldrain / "Pixel" mirrors */
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
        val id = Regex("""/(?:u|api/file)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/')

        if (id.isBlank()) return

        // Direct download endpoint — works with proper UA
        val direct = "https://pixeldrain.com/api/file/$id"
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = direct,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = "https://pixeldrain.com/u/$id"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to SAIL_UA,
                    "Referer" to "https://pixeldrain.com/u/$id",
                    "Accept" to "*/*",
                )
            }
        )
    }
}

/** Doodstream aliases sometimes labeled Dodo on AnimeSail */
open class DoodstreamAlias : ExtractorApi() {
    override var name = "Dodo"
    override var mainUrl = "https://dood.watch"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Reuse CloudStream built-in dood path via loadExtractor if possible — else minimal pass
        val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1) ?: return

        val embed = "$mainUrl/e/$id"
        val page = runCatching {
            app.get(embed, referer = referer ?: mainUrl, headers = mapOf("User-Agent" to SAIL_UA)).text
        }.getOrNull() ?: return

        // pass_md5 pattern
        val pass = Regex("""/pass_md5/([^"'\\s]+)""").find(page)?.groupValues?.getOrNull(1) ?: return
        val tokenUrl = "$mainUrl/pass_md5/$pass"
        val token = runCatching {
            app.get(
                tokenUrl,
                referer = embed,
                headers = mapOf("User-Agent" to SAIL_UA, "Referer" to embed),
            ).text.trim()
        }.getOrNull() ?: return

        if (!token.startsWith("http")) return
        // dood appends random + expiry
        val final = token + "z" + (System.currentTimeMillis() / 1000 + 3600)
        // Actually standard: token already full url base; append ?token= from page
        val t = Regex("""token=([A-Za-z0-9]+)""").find(page)?.groupValues?.getOrNull(1)
        val stream = if (t != null && !token.contains("?")) "$token?token=$t&expiry=${System.currentTimeMillis()}"
        else token

        callback(
            newExtractorLink(name, name, stream, ExtractorLinkType.VIDEO) {
                this.referer = embed
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to SAIL_UA,
                    "Referer" to embed,
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class DoodstreamWs : DoodstreamAlias() {
    override var mainUrl = "https://dood.ws"
}
