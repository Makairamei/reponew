package com.sad25kag.animesail

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloudflare Turnstile gate on AnimeSail (aghanim / IP host).
 * Solves challenge via WebView, injects cookies for subsequent OkHttp calls.
 */
class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        // Geo/locale cookies required by site JS before turnstile
        cookieManager.setCookie(domainUrl, "_as_ipin_lc=id-ID; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_tz=Asia/Jakarta; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_ct=ID; path=/; SameSite=Strict")
        cookieManager.flush()

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""
        if (existingCookies.contains(targetCookie)) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .header("User-Agent", originalRequest.header("User-Agent") ?: DEFAULT_UA)
                    .build()
            )
            // Still blocked? re-solve
            if (response.code != 403 && response.code != 503) {
                val peek = runCatching {
                    val body = response.peekBody(2048).string()
                    body.contains("turnstile", true) || body.contains("Just a moment", true) ||
                        body.contains("Loading..", true) && body.contains("challenges.cloudflare", true)
                }.getOrDefault(false)
                if (!peek) return response
            }
            response.close()
            cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0; path=/; Secure")
            cookieManager.flush()
        }

        val context = CloudStreamApp.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        val userAgentRef = AtomicReference(
            originalRequest.header("User-Agent") ?: DEFAULT_UA
        )
        val webViewRef = AtomicReference<WebView?>(null)

        handler.post {
            val wv = WebView(context)
            webViewRef.set(wv)
            cookieManager.setAcceptThirdPartyCookies(wv, true)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                databaseEnabled = true
                userAgentString = userAgentRef.get()
            }
            userAgentRef.set(wv.settings.userAgentString)
            wv.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    sslHandler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    sslHandler?.proceed()
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    cookieManager.flush()
                }
            }
            wv.loadUrl(url)
        }

        // Wait up to ~45s for turnstile cookie
        for (i in 0 until 45) {
            Thread.sleep(1000)
            val cookies = cookieManager.getCookie(domainUrl) ?: ""
            if (cookies.contains(targetCookie) || cookies.contains("cf_clearance")) {
                cookieManager.flush()
                break
            }
        }

        handler.post {
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                destroy()
            }
        }

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        val finalUA = userAgentRef.get().ifBlank { DEFAULT_UA }

        return chain.proceed(
            originalRequest.newBuilder()
                .header("User-Agent", finalUA)
                .header("Cookie", finalCookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
        )
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
