package com.sad25kag.animesail

import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private val mapper: ObjectMapper by lazy {
            ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private val turnstileInterceptor = TurnstileInterceptor("_as_turnstile")

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            ),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Anime Terbaru",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Donghua Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val rawHref = fixUrlNull(this.selectFirst("a")?.attr("href")).toString()
        val href = getProperAnimeLink(rawHref)

        val rawTitle = this.selectFirst(".tt > h2")?.text() ?: ""

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))

        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val typeText = this.selectFirst(".tt > span")?.text() ?: ""
        val type = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val link = if (page <= 1) {
            "$mainUrl/?s=$encodedQuery"
        } else {
            "$mainUrl/page/$page/?s=$encodedQuery"
        }

        val document = request(link).document

        val results = document.select("div.listupd article").map {
            it.toSearchResult()
        }

        val hasNext = document.selectFirst(
            "a.next, a.next.page-numbers, .pagination .next"
        ) != null

        return results.toNewSearchResponseList(
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().toString()
            .replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()
        val statusText = document.select("tbody th:contains(Status)").next().text().trim()
        val plotText = document.selectFirst("div.entry-content > p")?.text()
        val tagsList = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
        val durationText = document.select("tbody th:contains(Durasi)").next().text().trim()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {
            }
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("ul.daftar > li").amap {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()

            var episodeNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                "Synopsis not yet available."
            }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }

                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.reversed()

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview

        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            plotText
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year = year
            this.duration = getDurationFromString(durationText)
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = getStatus(statusText)
            this.plot = finalPlot
            this.tags = tagsList
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document
        val playerPath = "$mainUrl/utils/player/"

        document.select(".mobius > .mirror > option, .mirror option, option[data-em]").amap { element ->
            safeApiCall {
                val encodedData = element.attr("data-em").ifBlank { element.attr("value") }
                if (encodedData.isBlank() || encodedData.equals("0") || encodedData.length < 8) return@safeApiCall

                val decodedHtml = runCatching { base64Decode(encodedData) }.getOrNull() ?: return@safeApiCall
                val iframe = fixUrl(
                    Jsoup.parse(decodedHtml)
                        .select("iframe[src], embed[src], source[src], video[src]")
                        .firstOrNull()
                        ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                        .orEmpty()
                )
                if (iframe.contains("statistic") || iframe.isBlank()) return@safeApiCall

                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)
                val serverName = cleanMirrorName(rawText)

                when {
                    iframe.endsWith(".mp4", true) || iframe.endsWith(".m3u8", true) ||
                        iframe.contains(".mp4?", true) || iframe.contains(".m3u8?", true) -> {
                        emitDirectMedia(serverName, iframe, quality, mainUrl, callback)
                    }

                    iframe.contains("${playerPath}popup") || iframe.contains("popup.php") ||
                        iframe.contains("popup?", true) -> {
                        val encodedUrl = iframe.substringAfter("url=").substringBefore("&")
                        if (encodedUrl.isNotBlank()) {
                            val realUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                            loadFixedExtractor(realUrl, serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    iframe.contains("player-kodir") || iframe.contains("${playerPath}kodir") ||
                        iframe.contains("kodir2") -> {
                        resolveSitePlayer(iframe, data, serverName, quality, callback)
                    }

                    iframe.contains("${playerPath}framezilla") || iframe.contains("uservideo.xyz") ||
                        iframe.contains("framezilla") -> {
                        val res = request(iframe, ref = data)
                        if (isTurnstileGate(res.text)) {
                            // Still gated — request() should have solved; retry once
                            val retry = request(iframe, ref = data)
                            parseInnerPlayer(retry, iframe, serverName, quality, mainUrl, subtitleCallback, callback)
                        } else {
                            parseInnerPlayer(res, iframe, serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    // Dodo path: tools/redirect → dood-like hosts
                    iframe.contains("/tools/redirect/") || iframe.contains("aghanim.xyz/tools/redirect/") -> {
                        val id = iframe.substringAfter("id=").substringBefore("&").trim()
                        if (id.isNotBlank()) {
                            resolveDodoRedirect(id, serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    // Lokal / Buzi / any utils/player/* page (often CF-gated HTML5 source)
                    iframe.contains(playerPath) || iframe.contains("/utils/player") ||
                        (iframe.contains(mainUrl) && (iframe.contains("player") || iframe.contains("embed"))) -> {
                        resolveSitePlayer(iframe, data, serverName, quality, callback)
                    }

                    // Label hints for Dodo even if URL shape odd
                    serverName.contains("dodo", true) || iframe.contains("dood", true) ||
                        iframe.contains("doply", true) || iframe.contains("vide0", true) ||
                        iframe.contains("rasa-cintaku", true) -> {
                        loadFixedExtractor(iframe, "Dodo", quality, mainUrl, subtitleCallback, callback)
                        // also try id hop
                        val id = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(iframe)?.groupValues?.getOrNull(1)
                        if (id != null) resolveDodoRedirect(id, "Dodo", quality, mainUrl, subtitleCallback, callback)
                    }

                    else -> {
                        loadFixedExtractor(iframe, serverName, quality, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private fun cleanMirrorName(raw: String): String {
        val t = raw.trim()
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""\(.*?\)"""), "")
            .trim()
        val first = t.split(Regex("""\s+""")).firstOrNull().orEmpty()
        return first.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { name }
    }

    private fun isTurnstileGate(html: String): Boolean {
        val h = html.lowercase()
        return h.contains("turnstile") || h.contains("challenges.cloudflare") ||
            (h.contains("loading..") && h.contains("cf-turnstile")) ||
            h.contains("_as_turnstile") && h.length < 20000 && !h.contains("data-em")
    }

    private fun mediaHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "*/*",
    )

    private suspend fun emitDirectMedia(
        serverName: String,
        stream: String,
        quality: Int,
        ref: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val absolute = when {
            stream.startsWith("//") -> "https:$stream"
            stream.startsWith("http") -> stream
            else -> fixUrl(stream)
        }
        if (absolute.isBlank()) return
        callback(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = absolute,
                type = if (absolute.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = ref
                this.quality = quality
                this.headers = mediaHeaders(ref)
            }
        )
    }

    /** Lokal / site HTML5 player — always go through Turnstile interceptor via request() */
    private suspend fun resolveSitePlayer(
        iframe: String,
        episodeUrl: String,
        serverName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val res = request(iframe, ref = episodeUrl)
        var html = res.text
        if (isTurnstileGate(html)) {
            // Force re-solve: interceptor clears cookie on 403; second hit after wait
            html = request(iframe, ref = episodeUrl).text
        }
        if (isTurnstileGate(html)) return // still blocked — nothing to emit

        val link = extractPlayerSource(html, iframe)
        if (!link.isNullOrBlank()) {
            emitDirectMedia(serverName, link, quality, iframe, callback)
            return
        }
        // Nested iframe inside player page
        val nested = Jsoup.parse(html).select("iframe[src]").attr("src")
        if (nested.isNotBlank()) {
            val abs = fixUrl(nested)
            if (abs.contains(".mp4", true) || abs.contains(".m3u8", true)) {
                emitDirectMedia(serverName, abs, quality, iframe, callback)
            } else if (abs.contains("/tools/redirect/") || abs.contains("dood", true)) {
                val id = abs.substringAfter("id=").substringBefore("&").ifBlank {
                    Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(abs)?.groupValues?.getOrNull(1).orEmpty()
                }
                if (id.isNotBlank()) {
                    resolveDodoRedirect(id, serverName, quality, mainUrl, { }, callback)
                } else {
                    loadFixedExtractor(abs, serverName, quality, mainUrl, { }, callback)
                }
            } else {
                // recurse one level with CF
                val inner = request(abs, ref = iframe)
                val innerLink = extractPlayerSource(inner.text, abs)
                if (!innerLink.isNullOrBlank()) {
                    emitDirectMedia(serverName, innerLink, quality, abs, callback)
                }
            }
        }
    }

    private fun extractPlayerSource(html: String, base: String): String? {
        val doc = Jsoup.parse(html)
        doc.select("source[src], video[src], video source").forEach { el ->
            val s = el.attr("src").ifBlank { el.attr("data-src") }
            if (s.isNotBlank()) return s
        }
        // JS template: = `... <source src="...">`
        val fromTpl = Jsoup.parse(
            html.substringAfter("= `", "").substringBefore("`;", "")
        ).select("source").lastOrNull()?.attr("src")
        if (!fromTpl.isNullOrBlank()) return fromTpl

        Regex(
            """["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """src\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """file\s*[:=]\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }

        return null
    }

    private suspend fun parseInnerPlayer(
        res: com.lagradost.nicehttp.NiceResponse,
        iframe: String,
        serverName: String,
        quality: Int,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val inner = res.document.select("iframe[src], source[src], video[src]")
            .firstOrNull()
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            .orEmpty()
        if (inner.isBlank()) {
            extractPlayerSource(res.text, iframe)?.let {
                emitDirectMedia(serverName, it, quality, iframe, callback)
            }
            return
        }
        val abs = fixUrl(inner)
        if (abs.contains(".mp4", true) || abs.contains(".m3u8", true)) {
            emitDirectMedia(serverName, abs, quality, iframe, callback)
        } else {
            loadFixedExtractor(abs, serverName, quality, referer, subtitleCallback, callback)
        }
    }

    /** Dodo (Dood) multi-host after AnimeSail redirect id= */
    private suspend fun resolveDodoRedirect(
        id: String,
        serverName: String,
        quality: Int?,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val hosts = listOf(
            "https://rasa-cintaku-semakin-berantai.xyz/e/$id",
            "https://rasa-cintaku-semakin-berantai.xyz/v/$id",
            "https://dood.watch/e/$id",
            "https://dood.ws/e/$id",
            "https://dood.li/e/$id",
            "https://dood.so/e/$id",
            "https://dood.to/e/$id",
            "https://dood.la/e/$id",
            "https://d000d.com/e/$id",
            "https://ds2play.com/e/$id",
            "https://doply.net/e/$id",
            "https://vide0.net/e/$id",
            "https://myvidplay.com/e/$id",
        )
        val label = if (serverName.contains("dodo", true) || serverName.contains("dood", true)) {
            "Dodo"
        } else {
            serverName
        }
        hosts.forEach { link ->
            loadFixedExtractor(link, label, quality, referer, subtitleCallback, callback)
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val ref = referer ?: mainUrl
        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to ref,
            "Origin" to mainUrl,
            "Accept" to "*/*",
        )

        // Direct file links need headers (Exo 2004 without Referer)
        if (fixed.contains(".mp4", true) || fixed.contains(".m3u8", true)) {
            callback.invoke(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = fixed,
                    type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = ref
                    this.quality = quality ?: Qualities.Unknown.value
                    this.headers = baseHeaders
                }
            )
            return
        }

        loadExtractor(fixed, ref, subtitleCallback) { link ->
            runBlocking {
                val merged = LinkedHashMap<String, String>()
                merged.putAll(baseHeaders)
                merged.putAll(link.headers)
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.ifBlank { ref }
                        this.quality = quality?.takeIf { it > 0 } ?: link.quality
                        this.headers = merged
                        this.extractorData = link.extractorData
                    }
                )
            }
        }

        // MixDrop host hop for alternate domains
        if (fixed.contains("mixdrop", true) || fixed.contains("m1xdrop", true)) {
            val id = Regex("""/(?:e|f)/([A-Za-z0-9]+)""").find(fixed)?.groupValues?.getOrNull(1)
            if (id != null) {
                listOf(
                    "https://m1xdrop.bz/e/$id",
                    "https://mixdrop.ag/e/$id",
                    "https://mixdrop.to/e/$id",
                ).forEach { alt ->
                    if (!alt.equals(fixed, true)) {
                        loadExtractor(alt, ref, subtitleCallback) { link ->
                            runBlocking {
                                val merged = LinkedHashMap<String, String>()
                                merged.putAll(baseHeaders)
                                merged.putAll(link.headers)
                                callback.invoke(
                                    newExtractorLink(
                                        source = serverName,
                                        name = serverName,
                                        url = link.url,
                                        type = link.type
                                    ) {
                                        this.referer = link.referer.ifBlank { ref }
                                        this.quality = quality?.takeIf { it > 0 } ?: link.quality
                                        this.headers = merged
                                        this.extractorData = link.extractorData
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dodo / Dood host hop
        if (fixed.contains("dood", true) || fixed.contains("doply", true) ||
            fixed.contains("vide0", true) || fixed.contains("ds2play", true) ||
            fixed.contains("d000d", true) || fixed.contains("rasa-cintaku", true) ||
            fixed.contains("myvidplay", true) || serverName.contains("dodo", true)
        ) {
            val id = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(fixed)?.groupValues?.getOrNull(1)
            if (id != null) {
                listOf(
                    "https://dood.watch/e/$id",
                    "https://dood.ws/e/$id",
                    "https://dood.li/e/$id",
                    "https://rasa-cintaku-semakin-berantai.xyz/e/$id",
                    "https://myvidplay.com/e/$id",
                ).forEach { alt ->
                    if (!alt.equals(fixed, true)) {
                        loadExtractor(alt, ref, subtitleCallback) { link ->
                            runBlocking {
                                val merged = LinkedHashMap<String, String>()
                                merged.putAll(baseHeaders)
                                merged.putAll(link.headers)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Dodo",
                                        name = "Dodo",
                                        url = link.url,
                                        type = link.type
                                    ) {
                                        this.referer = link.referer.ifBlank { ref }
                                        this.quality = quality?.takeIf { it > 0 } ?: link.quality
                                        this.headers = merged
                                        this.extractorData = link.extractorData
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @param:JsonProperty("coverType") val coverType: String?,
        @param:JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @param:JsonProperty("episode") val episode: String?,
        @param:JsonProperty("airDateUtc") val airDateUtc: String?,
        @param:JsonProperty("runtime") val runtime: Int?,
        @param:JsonProperty("image") val image: String?,
        @param:JsonProperty("title") val title: Map<String, String>?,
        @param:JsonProperty("overview") val overview: String?,
        @param:JsonProperty("rating") val rating: String?,
        @param:JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @param:JsonProperty("titles") val titles: Map<String, String>?,
        @param:JsonProperty("description") val description: String?,
        @param:JsonProperty("images") val images: List<MetaImage>?,
        @param:JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @param:JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @param:JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @param:JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    private fun parseAnimeData(jsonString: String): MetaAnimeData? {
        return try {
            mapper.readValue(jsonString, MetaAnimeData::class.java)
        } catch (_: Exception) {
            null
        }
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
