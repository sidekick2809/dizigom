package com.filmmakinesi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.Requests.Companion.await
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Cloudflare "managed challenge" çözücü.
 *
 * Neden [com.lagradost.cloudstream3.network.CloudflareKiller] değil?
 *  - `init` bloğu **tüm** çerezleri siler. Aynı uygulamada birden çok sağlayıcı
 *    (bu depoda üç tane) çalıştığı için biri diğerinin `cf_clearance` çerezini
 *    siler; sayfalar boş gelir.
 *  - Çözülen host bir kez kaydedildikten sonra bir daha doğrulanmaz; çerezin
 *    süresi dolsa bile eski çerezle istek atılır.
 *
 * Bu sınıf çerezleri silmez, host başına çözümü önbelleğe alır ve doğrular;
 * doğrulama başarısız olursa WebView ile yeniden çözer.
 *
 * Ek olarak: bazı Cloudflare kurulumları `cf_clearance` çerezini tarayıcının
 * TLS/HTTP2 parmak izine bağlar. Bu durumda OkHttp (Conscrypt) taze çerezle
 * bile 403 alır. O zaman son çare olarak sayfa bir WebView'de açılır ve oluşan
 * HTML doğrudan eklentiye verilir — akış adresleri (CDN) Cloudflare arkasında
 * olmadığı için oynatma etkilenmez.
 */
class CloudflareBypass : Interceptor {

    private data class Solved(val cookie: String, val userAgent: String?)

    private data class CachedHtml(val html: String, val at: Long)

    private val solved = ConcurrentHashMap<String, Solved>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val htmlCache = ConcurrentHashMap<String, CachedHtml>()
    private val okhttpBlockedAt = ConcurrentHashMap<String, Long>()
    private val mapper by lazy { jacksonObjectMapper() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // OkHttp bu host'ta challenge'ı geçemiyorsa doğrudan WebView'e git.
        if (!isOkhttpBlocked(host)) {
            // 1) Daha önce çözülmüş host: önbellekteki çerezle dene.
            solved[host]?.let { saved ->
                val retried = retryWithCookie(request, saved)
                if (retried != null && !isChallenge(retried)) return retried
                retried?.close()
                solved.remove(host)
            }

            // 2) Normal istek.
            val response = chain.proceed(request)
            if (!isChallenge(response)) return response
            response.close()

            // 3) WebView ile çöz ve tazelenmiş çerezle tekrar dene.
            Log.i(TAG, "challenge, WebView ile çözülüyor: ${request.url}")
            solveAndRetry(request)?.let { return it }
        }

        // 4) Son çare: sayfayı WebView'de aç, HTML'i doğrudan kullan.
        val url = request.url.toString()
        cachedHtml(url)?.let { return synthetic(request, it) }

        val html = htmlFor(request)
        if (!html.isNullOrBlank() && !isChallengeHtml(html)) {
            Log.i(TAG, "HTML WebView'den alındı (${html.length} bayt): $url")
            htmlCache[url] = CachedHtml(html, System.currentTimeMillis())
            return synthetic(request, html)
        }

        return chain.proceed(request)
    }

    // ---------------------------------------------------------------- Cloudflare

    /** Cloudflare challenge yanıtı mı? (403/503 + Cloudflare izleri) */
    private fun isChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        val server = response.header("Server").orEmpty()
        val mitigated = response.header("cf-mitigated").orEmpty()
        return server.contains("cloudflare", ignoreCase = true) || mitigated.isNotBlank()
    }

    private fun isChallengeHtml(html: String): Boolean {
        val head = html.take(5000).lowercase()
        return head.contains("<title>just a moment") ||
            head.contains("<title>bir dakika") ||
            head.contains("_cf_chl_opt") ||
            head.contains("id=\"challenge-form\"") ||
            head.contains("attention required! | cloudflare")
    }

    private fun isOkhttpBlocked(host: String): Boolean {
        val at = okhttpBlockedAt[host] ?: return false
        if (System.currentTimeMillis() - at > OKHTTP_BLOCK_MS) {
            okhttpBlockedAt.remove(host)
            return false
        }
        return true
    }

    /**
     * WebView ile challenge'ı çözer, `cf_clearance` çerezini önbelleğe alır ve
     * isteği yeniden dener. Aynı host için eşzamanlı çağrılar kilitlenir.
     */
    private fun solveAndRetry(request: Request): Response? {
        val host = request.url.host
        val url = request.url.toString()

        return withLock(host) {
            // 1) CookieManager'daki mevcut çerezle dene (süresi dolmuş olabilir).
            cookieFor(url)?.takeIf { it.contains("cf_clearance") }?.let { existing ->
                val saved = Solved(existing, WebViewResolver.webViewUserAgent)
                retryWithCookie(request, saved)?.let { retried ->
                    if (!isChallenge(retried)) {
                        solved[host] = saved
                        return@withLock retried
                    }
                    retried.close()
                }
            }

            // 2) WebView ile yeniden çöz.
            blockOn(timeoutSeconds = 90) {
                runCatching {
                    WebViewResolver(
                        // URL'ye göre erken çıkma; her istekte çerez kontrol edilir.
                        interceptUrl = Regex(".^"),
                        additionalUrls = listOf(Regex(".")),
                        // Cloudflare WebView'in kendi user agent'ını ister.
                        userAgent = null,
                        // Captcha/Cloudflare için OkHttp kullanılmamalı.
                        useOkhttp = false,
                    ).resolveUsingWebView(url) {
                        cookieFor(url)?.contains("cf_clearance") == true
                    }
                }
            }

            val fresh = cookieFor(url)?.takeIf { it.contains("cf_clearance") } ?: return@withLock null
            val saved = Solved(fresh, WebViewResolver.webViewUserAgent)
            val retried = retryWithCookie(request, saved) ?: return@withLock null

            if (isChallenge(retried)) {
                retried.close()
                // Taze çerez + WebView UA ile bile challenge: OkHttp parmak izi
                // reddediliyor. Bir süre çerez yolunu denemeyelim.
                Log.w(TAG, "OkHttp challenge'ı geçemedi, WebView HTML yoluna geçildi: $host")
                okhttpBlockedAt[host] = System.currentTimeMillis()
                return@withLock null
            }

            okhttpBlockedAt.remove(host)
            solved[host] = saved
            retried
        }
    }

    private fun cookieFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * İsteği Cloudstream'in interceptor'sız istemcisiyle, WebView çerezi ve
     * user agent'ı ile tekrarlar (aksi halde interceptor kendini tekrar çağırır).
     */
    private fun retryWithCookie(request: Request, saved: Solved): Response? {
        val builder = request.newBuilder().header("Cookie", saved.cookie)
        if (!saved.userAgent.isNullOrBlank()) {
            builder.header("User-Agent", saved.userAgent)
        }
        val built = builder.build()
        return blockOn(timeoutSeconds = 60) {
            runCatching { app.baseClient.newCall(built).await() }.getOrNull()
        }
    }

    private inline fun <T> withLock(host: String, block: () -> T): T {
        val lock = locks.getOrPut(host) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // ------------------------------------------------------------- WebView HTML

    /** HTML'i host başına sırayla (tek WebView) getirir. */
    private fun htmlFor(request: Request): String? = withLock(request.url.host) {
        blockOn(timeoutSeconds = 90) { fetchHtmlViaWebView(request.url.toString()) }
    }

    /**
     * Sayfayı gerçek bir WebView'de açar, challenge çözülene kadar bekler ve
     * oluşan DOM'u HTML olarak döndürür.
     */
    private suspend fun fetchHtmlViaWebView(url: String): String? = suspendCoroutine { cont ->
        val context = getContext() as? Context
        if (context == null) {
            cont.resume(null)
            return@suspendCoroutine
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            var finished = false
            var webView: WebView? = null
            var attempts = 0

            fun finish(html: String?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView?.stopLoading()
                    webView?.destroy()
                }
                webView = null
                cont.resume(html)
            }

            handler.postDelayed({ finish(null) }, WEBVIEW_TIMEOUT_MS)

            try {
                val view = WebView(context)
                webView = view
                @SuppressLint("SetJavaScriptEnabled")
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.blockNetworkImage = true

                lateinit var probe: Runnable
                probe = object : Runnable {
                    override fun run() {
                        if (finished) return
                        val current = webView ?: return
                        attempts += 1
                        current.evaluateJavascript(JS_OUTER_HTML) { raw ->
                            if (finished) return@evaluateJavascript
                            val html = decodeJsString(raw)
                            when {
                                html.isNullOrBlank() -> retryLater()
                                // Challenge hâlâ çözülmedi.
                                isChallengeHtml(html) -> retryLater()
                                // Challenge çözülüp gerçek sayfa gelene kadar bekle.
                                html.length < MIN_HTML_LENGTH -> retryLater()
                                else -> finish(html)
                            }
                        }
                    }

                    private fun retryLater() {
                        if (attempts >= MAX_PROBES) finish(null)
                        else handler.postDelayed(probe, PROBE_INTERVAL_MS)
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        handler.postDelayed(probe, PROBE_INTERVAL_MS)
                    }
                }

                view.loadUrl(url)
            } catch (_: Throwable) {
                finish(null)
            }
        }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { mapper.readValue(raw, String::class.java) }.getOrNull()
    }

    private fun cachedHtml(url: String): String? {
        val cached = htmlCache[url] ?: return null
        if (System.currentTimeMillis() - cached.at > HTML_CACHE_MS) {
            htmlCache.remove(url)
            return null
        }
        return cached.html
    }

    private fun synthetic(request: Request, html: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "text/html; charset=utf-8")
        .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    // ---------------------------------------------------------------- Coroutines

    /**
     * Eklenti modüllerinde `kotlinx-coroutines` bağımlılığı yok; bu yüzden
     * `runBlocking` yerine stdlib'in `startCoroutine` API'si kullanılır.
     * `Interceptor.intercept` askıya alınamadığı için zorunlu.
     */
    private fun <T> blockOn(timeoutSeconds: Long, block: suspend () -> T): T? {
        val latch = CountDownLatch(1)
        var outcome: Result<T>? = null

        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
                latch.countDown()
            }
        })

        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return null
        return outcome?.getOrNull()
    }

    private companion object {
        const val TAG = "CloudflareBypass"

        const val JS_OUTER_HTML =
            "(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()"

        const val WEBVIEW_TIMEOUT_MS = 60_000L
        const val PROBE_INTERVAL_MS = 1_200L
        const val MAX_PROBES = 30
        const val MIN_HTML_LENGTH = 2_000
        const val HTML_CACHE_MS = 3 * 60 * 1000L
        const val OKHTTP_BLOCK_MS = 10 * 60 * 1000L
    }
}
