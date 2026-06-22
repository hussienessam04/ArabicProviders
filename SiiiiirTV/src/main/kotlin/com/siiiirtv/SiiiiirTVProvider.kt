package com.siiiirtv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

class SiiiiirTVProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://www.siiiiir.tv"
    override var name = "Siiiiir TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to ua)
    private val TAG = "SiiiiirTV"

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (!url.startsWith("http")) return "$mainUrl$url"
        return url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/hes-goals/", headers = headers).document
        val homePageList = mutableListOf<HomePageList>()

        val matches = doc.select(".AY_Match").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val rawId = element.id()
            val matchId = rawId.removePrefix("m-")
            if (matchId.isBlank() || matchId.toIntOrNull() == null) return@mapNotNull null

            val team1 = element.selectFirst(".TM1")?.text()?.trim() ?: ""
            val team2 = element.selectFirst(".TM2")?.text()?.trim() ?: ""
            if (team1.isBlank() || team2.isBlank()) return@mapNotNull null

            val score = element.selectFirst(".MT_Score")?.text()?.trim() ?: ""
            val time = element.selectFirst(".MT_Time")?.text()?.trim() ?: ""
            val title = if (score.isNotBlank()) "$team1 $score $team2" else "$team1 vs $team2"

            val poster = element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")

            newLiveSearchResponse(title, "${fixUrl(href)}#$matchId", TvType.Live) {
                this.posterUrl = fixUrl(poster ?: "")
            }
        }

        if (matches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", matches, isHorizontalImages = true))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val doc = app.get("$mainUrl/?s=$query", headers = headers).document
            doc.select(".AY-PItem").mapNotNull { element ->
                val titleEl = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
                newMovieSearchResponse(titleEl.text(), fixUrl(titleEl.attr("href")), TvType.Live) {
                    this.posterUrl = fixUrl(element.selectFirst("img")?.attr("data-src") ?: "")
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.substringBefore("#")
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst(".EntryTitle")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: "مباراة"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img[onerror]")?.attr("src")

        val descBuilder = StringBuilder()
        doc.select(".AY-MatchInfo table tr").forEach { row ->
            val key = row.select("th").text()
            val value = row.select("td").text()
            if (key.isNotBlank() && value.isNotBlank()) {
                descBuilder.append("$key: $value\n")
            }
        }

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = fixUrl(poster ?: "")
            this.plot = descBuilder.toString().ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=$data")
        val matchId = data.substringAfterLast("#").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: run { Log.w(TAG, "loadLinks: no valid matchId in data"); return false }

        Log.d(TAG, "loadLinks: matchId=$matchId")

        val apiUrl = "https://ws.kora-api.top/api/matche/$matchId/ar?t=${System.currentTimeMillis()}"
        val apiJson = runCatching { app.get(apiUrl, headers = headers).text }.getOrNull()
            ?: run { Log.w(TAG, "loadLinks: API request failed"); return false }
        Log.d(TAG, "loadLinks: API json length=${apiJson.length}")

        val matchData = runCatching { JSONObject(apiJson) }.getOrNull()
            ?: run { Log.w(TAG, "loadLinks: API json parse failed"); return false }
        val channels = matchData.optJSONArray("channels")
        if (channels == null) { Log.w(TAG, "loadLinks: no channels array"); return false }

        val channelNames = (0 until channels.length()).mapNotNull { i ->
            val ch = channels.optJSONObject(i) ?: return@mapNotNull null
            val name = ch.optString("server_name").ifBlank { ch.optString("server_name_en") }
            val chId = ch.optString("ch")
            if (name.isBlank()) null else (name to chId)
        }
        Log.d(TAG, "loadLinks: found ${channelNames.size} channels: ${channelNames.map { it.first }}")
        if (channelNames.isEmpty()) return false

        val burnerUrl = "https://siiiir.hes-goals.mov/?m=$matchId&p=87350"
        Log.d(TAG, "loadLinks: burnerUrl=$burnerUrl")

        val streamUrl = resolveWithWebView(burnerUrl)
        if (streamUrl == null) {
            Log.w(TAG, "loadLinks FINAL: no stream found")
            return false
        }
        Log.d(TAG, "loadLinks FINAL: stream=$streamUrl")

        val streamReferer = runCatching {
            java.net.URI(streamUrl).let { "${it.scheme}://${it.host}/" }
        }.getOrDefault("https://1rxolmirvosixpyfy.foozlive.co/")
        val isM3u8 = streamUrl.contains(".m3u8")
        val linkHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to streamReferer
        )

        var found = false
        for ((serverName, _) in channelNames) {
            val displayName = "$name - $serverName"
            if (isM3u8) {
                M3u8Helper.generateM3u8(
                    source = name, name = displayName, streamUrl = streamUrl,
                    referer = streamReferer, headers = linkHeaders
                ).forEach { link ->
                    callback(link)
                    found = true
                }
            } else {
                callback(newExtractorLink(name, displayName, streamUrl) {
                    this.referer = streamReferer
                    this.headers = linkHeaders
                })
                found = true
            }
        }
        return found
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        burnerUrl: String,
        timeoutMs: Long = 50000
    ): String? = suspendCancellableCoroutine { cont ->
        val activity = getActivity(context)
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        handler.post {
            val webViewContext = activity ?: context
            Log.d(TAG, "WebView: creating with context=${webViewContext.javaClass.simpleName} (activity=${activity != null})")

            val webView = WebView(webViewContext).apply {
                layoutParams = ViewGroup.LayoutParams(1920, 1080)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }
            Log.d(TAG, "WebView: instance created")

            // Try to keep the WebView alive even without a parent by calling lifecycle methods
            try {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, 1920, 1080)
                webView.onResume()
                webView.resumeTimers()
                Log.d(TAG, "WebView: measure/layout/onResume called")
            } catch (e: Exception) {
                Log.e(TAG, "WebView: measure/layout failed: ${e.message}")
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = ua
                blockNetworkImage = true
            }
            Log.d(TAG, "WebView: settings applied (JS=on, UA=$ua)")

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            var finished = false

            fun cleanup() {
                val runCleanup = {
                    try { handler.removeCallbacksAndMessages(null) } catch (_: Exception) { }
                    try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) { }
                    try { webView.stopLoading() } catch (_: Exception) { }
                    try { webView.destroy() } catch (_: Exception) { }
                    Log.d(TAG, "WebView: cleaned up")
                    Unit
                }
                if (Looper.myLooper() == mainLooper) runCleanup() else handler.post(runCleanup)
            }

            fun safeFinish(result: String?) {
                if (finished) return
                finished = true
                try { if (cont.isActive) cont.resume(result, onCancellation = null) } catch (_: Exception) { }
                cleanup()
            }

            val timeoutRunnable = Runnable {
                Log.w(TAG, "WebView: TIMEOUT after ${timeoutMs}ms")
                safeFinish(null)
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    val method = request?.method ?: "?"
                    Log.d(TAG, "WebView REQUEST: $method $reqUrl")
                    if (reqUrl.contains(".m3u8") || reqUrl.contains("/kooora/")) {
                        Log.d(TAG, "WebView FOUND STREAM: $reqUrl")
                        safeFinish(reqUrl)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "WebView PAGE STARTED: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView PAGE FINISHED: $url")

                    if (url?.contains("siiiiiiir.tv/hard/") == true) {
                        // Step A: hard player page loaded. Extract the iframe URL.
                        Log.d(TAG, "STEP A: hard player page loaded, extracting playerSrc")
                        view?.evaluateJavascript("window.__playerSrc") { playerSrc ->
                            Log.d(TAG, "STEP A: playerSrc eval = $playerSrc")
                            val cleanSrc = playerSrc?.trim()?.removeSurrounding("\"") ?: ""
                            if (cleanSrc.isNotBlank() && cleanSrc != "null") {
                                Log.d(TAG, "STEP A: loading iframe directly: $cleanSrc")
                                handler.postDelayed({
                                    try {
                                        webView.loadUrl(cleanSrc, mapOf("Referer" to "https://siiiiiiir.tv/hard/2908c7d4425d87350.html"))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "STEP A: loadUrl failed: ${e.message}")
                                    }
                                }, 500)
                            } else {
                                Log.w(TAG, "STEP A: playerSrc is empty/null, cannot proceed")
                            }
                        }
                    } else if (url?.contains("playerv5.php") == true || url?.contains("liveonlinesports.net") == true) {
                        // Step B: iframe page loaded. Search for foozlive.co URL.
                        Log.d(TAG, "STEP B: iframe page loaded, searching for foozlive.co")
                        view?.evaluateJavascript("""
                            (function() {
                                try {
                                    var scripts = document.querySelectorAll('script');
                                    for (var i = 0; i < scripts.length; i++) {
                                        var m = scripts[i].textContent.match(/https?:\/\/[^\s"'<>]+\/kooora\/[^\s"'<>]+/);
                                        if (m) return m[0];
                                    }
                                    return '';
                                } catch (e) { return ''; }
                            })()
                        """.trimIndent()) { value ->
                            Log.d(TAG, "STEP B: iframe JS eval = $value")
                            val cleanUrl = value?.trim()?.removeSurrounding("\"") ?: ""
                            if (cleanUrl.isNotBlank() && cleanUrl != "null") {
                                safeFinish(cleanUrl)
                            } else {
                                Log.w(TAG, "STEP B: no foozlive.co URL found in iframe page")
                            }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView onReceivedError: ${request?.url} code=${error?.errorCode} desc=${error?.description}")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    Log.w(TAG, "WebView onReceivedHttpError: ${request?.url} status=${errorResponse?.statusCode} mime=${errorResponse?.mimeType}")
                }
            }

            try {
                Log.d(TAG, "WebView: loading burner URL: $burnerUrl")
                webView.loadUrl(burnerUrl, mapOf("Referer" to "https://www.siiiiir.tv/"))
            } catch (e: Exception) {
                Log.e(TAG, "WebView: loadUrl threw: ${e.message}")
                safeFinish(null)
            }
        }
    }

    private fun getActivity(context: Context): Activity? {
        var current = context
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
