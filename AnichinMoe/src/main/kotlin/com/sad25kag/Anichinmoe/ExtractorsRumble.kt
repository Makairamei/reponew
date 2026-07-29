package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        val response = runCatching {
            app.get(url, referer = referer ?: "$mainUrl/")
        }.getOrNull() ?: return

        val body = response.text
        val candidates = linkedSetOf<String>()

        Regex(""""(?:url|hls|ua)"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""https?://[^\s"'<>]+rumble[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value.replace("\\/", "/") }
            .forEach { candidates.add(it) }

        Regex("""https?://[^\s"'<>]+rumble[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value.replace("\\/", "/") }
            .forEach { candidates.add(it) }

        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()
            ?.substringAfter("{\"mp4")?.substringBefore("\"evt\":{")
        if (!scriptData.isNullOrBlank()) {
            Regex(""""url":"([^"]+)"""")
                .findAll(scriptData)
                .map { it.groupValues[1].replace("\\/", "/") }
                .forEach { candidates.add(it) }
        }

        for (stream in candidates) {
            when {
                stream.contains(".m3u8", true) -> {
                    runCatching {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = stream,
                            referer = mainUrl,
                        ).forEach(callback)
                    }.onFailure {
                        callback.invoke(
                            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8)
                        )
                    }
                }
                stream.contains(".mp4", true) -> {
                    val quality = when {
                        stream.contains("1080") -> Qualities.P1080.value
                        stream.contains("720") -> Qualities.P720.value
                        stream.contains("480") -> Qualities.P480.value
                        stream.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(
                        newExtractorLink(name, name, stream, ExtractorLinkType.VIDEO) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }
    }
}
