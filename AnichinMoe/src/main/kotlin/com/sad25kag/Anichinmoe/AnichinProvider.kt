package com.sad25kag.Anichinmoe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinProvider: Plugin() {
    override fun load(context: Context) {
        Anichin.context = context
        registerMainAPI(Anichin())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Rpmshare())
        registerExtractorAPI(RpmshareSub())
        registerExtractorAPI(Rpmplay())
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Peytonepre())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(DoodPlaymogo())
        registerExtractorAPI(DoodMyvidplay())
        registerExtractorAPI(Abyssplayer())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Turbovidhls())
        registerExtractorAPI(Dtube())
        registerExtractorAPI(AnichinPlayerProxy())
        registerExtractorAPI(Rubyvidhub())
        registerExtractorAPI(Newplayr())
        registerExtractorAPI(NewplayrSub())
        registerExtractorAPI(Streamhg())
        registerExtractorAPI(StreamhgSub())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(StreamWishSub())
    }
}
