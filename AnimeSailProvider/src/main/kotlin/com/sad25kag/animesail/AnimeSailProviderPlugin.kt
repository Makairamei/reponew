package com.sad25kag.animesail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSailProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSailProvider())

        // One extractor per host family — extra dood clones caused 8× "Dodo 1080p"
        registerExtractorAPI(MixDropBz())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(MixDropTo())
        registerExtractorAPI(MixDropClub())
        registerExtractorAPI(MixDropCom())
        registerExtractorAPI(Mp4UploadFix())
        registerExtractorAPI(Mp4UploadOrg())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(DoodStreamSail()) // Dodo — single class only
    }
}
