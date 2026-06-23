package com.siiiirtv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class SiiiiirTVProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://www.siiiiir.tv"
    override var name = "Siiiiir TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val browserHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
        "Sec-CH-UA" to "\"Chromium\";v=\"124\", \"Android WebView\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
        "Sec-CH-UA-Mobile" to "?1",
        "Sec-CH-UA-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )
    private val TAG = "SiiiiirTV"

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (!url.startsWith("http")) return "$mainUrl$url"
        return url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/hes-goals/", headers = browserHeaders).document
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
            val doc = app.get("$mainUrl/?s=$query", headers = browserHeaders).document
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
        val doc = app.get(pageUrl, headers = browserHeaders).document
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
        val matchId = data.substringAfterLast("#").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return false

        val apiUrl = "https://ws.kora-api.top/api/matche/$matchId/ar?t=${System.currentTimeMillis()}"
        val apiJson = runCatching { app.get(apiUrl, headers = browserHeaders).text }.getOrNull()
            ?: return false

        val matchData = runCatching { JSONObject(apiJson) }.getOrNull() ?: return false
        val channels = matchData.optJSONArray("channels") ?: return false

        val channelNames = (0 until channels.length()).mapNotNull { i ->
            val ch = channels.optJSONObject(i) ?: return@mapNotNull null
            val name = ch.optString("server_name").ifBlank { ch.optString("server_name_en") }
            if (name.isBlank()) null else name
        }
        if (channelNames.isEmpty()) return false

        val burnerUrl = "https://siiiir.hes-goals.mov/?m=$matchId&p=87350"
        Log.d(TAG, "loadLinks: matchId=$matchId, burnerUrl=$burnerUrl")
        val streamUrl = fetchStreamUrl(burnerUrl)
        if (streamUrl == null) {
            Log.w(TAG, "loadLinks: no stream URL found for matchId=$matchId")
            return false
        }
        Log.d(TAG, "loadLinks: found streamUrl=$streamUrl")

        val streamReferer = runCatching {
            java.net.URI(streamUrl).let { "${it.scheme}://${it.host}/" }
        }.getOrDefault("https://foozlive.co/")

        val linkHeaders = mapOf("User-Agent" to ua, "Referer" to streamReferer)
        val isM3u8 = streamUrl.contains(".m3u8")

        var found = false
        for (serverName in channelNames) {
            val displayName = "$name - $serverName"
            if (isM3u8) {
                M3u8Helper.generateM3u8(
                    source = name, name = displayName, streamUrl = streamUrl,
                    referer = streamReferer, headers = linkHeaders
                ).forEach { link -> callback(link); found = true }
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

    /**
     * Fetches stream URL using WebView by:
     * 1. Loading burner URL (redirects to hard player page)
     * 2. Hard player page loads iframe with actual player
     * 3. Intercepting network requests for .m3u8 stream URLs
     * 4. Returns first captured stream URL or null after 40s timeout
     */
    private suspend fun fetchStreamUrl(burnerUrl: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val activity = context as? android.app.Activity
            
            if (activity == null) {
                android.util.Log.e(TAG, "Context is not an Activity, cannot create WebView")
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            
            android.util.Log.d(TAG, "WebView: creating for URL: $burnerUrl")
            
            // Create WebView
            val webView = WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(1920, 1080)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }
            
            // Add to view hierarchy (required for WebView to work)
            val addedToView = try {
                val decor = activity.window?.decorView as? ViewGroup
                if (decor != null) {
                    decor.addView(webView)
                    android.util.Log.d(TAG, "WebView: added to view hierarchy")
                    true
                } else {
                    android.util.Log.e(TAG, "WebView: could not get decor view")
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "WebView: failed to add to view", e)
                false
            }
            
            if (!addedToView) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            
            // Measure and layout WebView
            try {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, 1920, 1080)
                webView.onResume()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "WebView: measure/layout failed", e)
            }
            
            // Configure WebView settings
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = ua
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                blockNetworkImage = true // Optimization: don't load images
            }
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            var capturedUrl: String? = null
            var finished = false
            val finishLock = Any()
            
            // Cleanup function
            fun cleanup() {
                handler.post {
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                        android.util.Log.d(TAG, "WebView: cleaned up")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "WebView: cleanup error", e)
                    }
                }
            }
            
            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try {
                    if (cont.isActive) cont.resume(result) {}
                } catch (_: Exception) { }
                cleanup()
            }
            
            // Timeout after 40 seconds
            val timeoutRunnable = Runnable {
                android.util.Log.w(TAG, "WebView: TIMEOUT after 40s, captured=${capturedUrl != null}")
                safeFinish(capturedUrl)
            }
            handler.postDelayed(timeoutRunnable, 40000)
            
            // Set WebView client to intercept requests
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    
                    // Look for .m3u8 stream URLs
                    if (url.contains(".m3u8", ignoreCase = true)) {
                        android.util.Log.d(TAG, "WebView: captured m3u8: $url")
                        if (capturedUrl == null) {
                            capturedUrl = url
                            handler.postDelayed({
                                safeFinish(url)
                            }, 1000) // Small delay to capture potential better quality streams
                        }
                    }
                    
                    // Also look for other stream patterns
                    if ((url.contains("/kooora/", ignoreCase = true) || 
                         url.contains("/live/", ignoreCase = true) ||
                         url.contains("/stream/", ignoreCase = true)) && 
                        (url.endsWith(".m3u8") || url.contains(".m3u8?"))) {
                        android.util.Log.d(TAG, "WebView: captured stream: $url")
                        if (capturedUrl == null) {
                            capturedUrl = url
                            handler.postDelayed({
                                safeFinish(url)
                            }, 1000)
                        }
                    }
                    
                    return super.shouldInterceptRequest(view, request)
                }
                
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    android.util.Log.d(TAG, "WebView: page started: $url")
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d(TAG, "WebView: page finished: $url")
                    
                    // Inject JavaScript to search for stream URLs in page content
                    view?.evaluateJavascript("""
                        (function() {
                            try {
                                // Look for m3u8 URLs in script tags
                                var scripts = document.getElementsByTagName('script');
                                for (var i = 0; i < scripts.length; i++) {
                                    var content = scripts[i].textContent || scripts[i].innerText || '';
                                    var m3u8Match = content.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"']*/);
                                    if (m3u8Match) {
                                        console.log('Found m3u8 in script: ' + m3u8Match[0]);
                                        return m3u8Match[0];
                                    }
                                }
                                
                                // Look for video source elements
                                var videos = document.getElementsByTagName('video');
                                for (var i = 0; i < videos.length; i++) {
                                    if (videos[i].src && videos[i].src.includes('.m3u8')) {
                                        console.log('Found m3u8 in video.src: ' + videos[i].src);
                                        return videos[i].src;
                                    }
                                }
                                
                                // Look for iframe src
                                var iframe = document.querySelector('iframe');
                                if (iframe && iframe.src) {
                                    console.log('Found iframe.src: ' + iframe.src);
                                }
                                
                                return null;
                            } catch(e) {
                                console.error('Error searching for m3u8:', e);
                                return null;
                            }
                        })();
                    """.trimIndent()) { result ->
                        if (result != null && result != "null" && result.isNotBlank()) {
                            val cleanUrl = result.trim('"')
                            if (cleanUrl.contains(".m3u8")) {
                                android.util.Log.d(TAG, "WebView: JS found stream: $cleanUrl")
                                if (capturedUrl == null) {
                                    capturedUrl = cleanUrl
                                    handler.postDelayed({
                                        safeFinish(cleanUrl)
                                    }, 500)
                                }
                            }
                        }
                    }
                }
                
                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    android.util.Log.w(TAG, "WebView: error $errorCode at $failingUrl: $description")
                }
            }
            
            // Handle coroutine cancellation
            cont.invokeOnCancellation {
                android.util.Log.d(TAG, "WebView: coroutine cancelled")
                handler.removeCallbacks(timeoutRunnable)
                safeFinish(null)
            }
            
            // Load the burner URL (will redirect to hard player page)
            try {
                android.util.Log.d(TAG, "WebView: loading URL: $burnerUrl")
                webView.loadUrl(burnerUrl, mapOf("Referer" to mainUrl))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "WebView: loadUrl failed", e)
                handler.removeCallbacks(timeoutRunnable)
                safeFinish(null)
            }
        }
    }
}
