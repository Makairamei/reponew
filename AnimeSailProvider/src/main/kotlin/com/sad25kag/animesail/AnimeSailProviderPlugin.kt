package com.sad25kag.animesail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSailProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSailProvider())

        // Host extractors — without these only built-ins (e.g. some Kraken) show up
        registerExtractorAPI(MixDropBz())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(MixDropTo())
        registerExtractorAPI(MixDropClub())
        registerExtractorAPI(MixDropCom())
        registerExtractorAPI(Mp4UploadFix())
        registerExtractorAPI(Mp4UploadOrg())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(Krakenfiles())

        // Dodo = Doodstream family (AnimeSail redirect host)
        registerExtractorAPI(DoodStreamSail())
        registerExtractorAPI(DoodWatch())
        registerExtractorAPI(DoodWs())
        registerExtractorAPI(DoodLi())
        registerExtractorAPI(DoodRasa())
    }
}
