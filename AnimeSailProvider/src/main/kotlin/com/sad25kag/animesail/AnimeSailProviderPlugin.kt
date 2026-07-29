package com.sad25kag.animesail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSailProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // Main site (Cloudflare Turnstile + mirrors)
        registerMainAPI(AnimeSailProvider())

        // Host extractors used by AnimeSail mirrors
        registerExtractorAPI(MixDropBz())
        registerExtractorAPI(MixDropCom())
        registerExtractorAPI(MixDropTo())
        registerExtractorAPI(MixDropClub())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(Mp4UploadFix())
        registerExtractorAPI(Mp4UploadOrg())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(KrakenfilesTo())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(DoodstreamAlias())
        registerExtractorAPI(DoodstreamWs())
    }
}
