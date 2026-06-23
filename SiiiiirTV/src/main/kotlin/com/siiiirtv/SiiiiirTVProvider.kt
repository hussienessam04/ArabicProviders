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

        Log.d(TAG, "loadLinks: matchId=$matchId")
        
        // Construct player page URL directly
        val playerUrl = "https://siiiiiiir.tv/hard/2908c7d4425d87351.html?match=$matchId"
        Log.d(TAG, "loadLinks: playerUrl=$playerUrl")
        
        // Fetch player page HTML
        val playerHtml = runCatching { 
            app.get(playerUrl, headers = browserHeaders).text 
        }.getOrNull() ?: return false
        
        // Extract iframe URL
        val iframeUrl = extractIframeUrl(playerHtml)
        if (iframeUrl == null) {
            Log.w(TAG, "loadLinks: no iframe URL found")
            return false
        }
        Log.d(TAG, "loadLinks: iframeUrl=$iframeUrl")
        
        // Fetch stream URLs via WebView
        val streamUrls = fetchStreamUrls(iframeUrl)
        if (streamUrls.isEmpty()) {
            Log.w(TAG, "loadLinks: no stream URLs found")
            return false
        }
        
        Log.d(TAG, "loadLinks: found ${streamUrls.size} stream URLs")
        
        // Return all streams
        streamUrls.forEach { (serverName, url) ->
            val referer = "https://912acsss8af38.liveonlinesports.net/"
            val headers = mapOf("User-Agent" to ua, "Referer" to referer)
            
            M3u8Helper.generateM3u8(
                source = name,
                name = "$name - $serverName",
                streamUrl = url,
                referer = referer,
                headers = headers
            ).forEach { link -> callback(link) }
        }
        
        return true
    }

    // ponytail: extract player URL from JavaScript variable
    private fun extractIframeUrl(html: String): String? {
        // First try: match window.__playerSrc assignment (capture until single quote after key=)
        val playerSrcRegex = """window\.__playerSrc\s*=\s*'([^']+)'""".toRegex()
        playerSrcRegex.find(html)?.groupValues?.get(1)?.let { return it }
        
        // Try double quotes version
        val playerSrcRegex2 = """window\.__playerSrc\s*=\s*"([^"]+)"\s*;""".toRegex()
        playerSrcRegex2.find(html)?.groupValues?.get(1)?.let { return it }
        
        // Try semicolon-terminated single quote
        val playerSrcRegex3 = """window\.__playerSrc\s*=\s*'([^']+)'\s*;""".toRegex()
        playerSrcRegex3.find(html)?.groupValues?.get(1)?.let { return it }
        
        // Fallback: look for player iframe src in script
        val scriptRegex = """['"](https?://[^'"]*playerv\d+\.php[^'"]*)['"]""".toRegex()
        return scriptRegex.find(html)?.groupValues?.get(1)
    }

    private suspend fun fetchStreamUrls(iframeUrl: String): Map<String, String> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val activity = context as? android.app.Activity
            
            if (activity == null) {
                Log.e(TAG, "Context is not an Activity")
                cont.resume(emptyMap())
                return@suspendCancellableCoroutine
            }
            
            Log.d(TAG, "WebView: loading iframe: $iframeUrl")
            
            val webView = WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(1920, 1080)
                visibility = View.INVISIBLE
            }
            
            val addedToView = try {
                (activity.window?.decorView as? ViewGroup)?.addView(webView)
                true
            } catch (e: Exception) {
                Log.e(TAG, "WebView: add failed", e)
                false
            }
            
            if (!addedToView) {
                cont.resume(emptyMap())
                return@suspendCancellableCoroutine
            }
            
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = ua
                cacheMode = WebSettings.LOAD_NO_CACHE
                blockNetworkImage = true
            }
            
            val capturedUrls = mutableMapOf<String, String>()
            var finished = false
            
            fun cleanup() {
                handler.post {
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    } catch (_: Exception) {}
                }
            }
            
            fun safeFinish() {
                if (finished) return
                finished = true
                cont.resume(capturedUrls.toMap())
                cleanup()
            }
            
            // Timeout after 40s
            handler.postDelayed({ safeFinish() }, 40000)
            
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    
                    // Capture foozlive.co m3u8 URLs with quality variants
                    if (url.contains("foozlive.co") && url.contains(".m3u8")) {
                        Log.d(TAG, "WebView: captured m3u8: $url")
                        
                        // Determine server and quality from URL
                        val serverName = when {
                            url.contains("_kc_uhd") -> extractServerName(url, "UHD")
                            url.contains("_kc_hd") -> extractServerName(url, "HD")
                            url.contains("_kc") -> extractServerName(url, "SD")
                            else -> null
                        }
                        
                        if (serverName != null) {
                            capturedUrls[serverName] = url
                            Log.d(TAG, "WebView: captured $serverName -> $url")
                            
                            // Finish once we have enough URLs (at least 4 servers × 3 qualities = 12)
                            if (capturedUrls.size >= 12) {
                                handler.postDelayed({ safeFinish() }, 2000)
                            }
                        }
                    }
                    
                    return super.shouldInterceptRequest(view, request)
                }
            }
            
            cont.invokeOnCancellation { cleanup() }
            
            try {
                webView.loadUrl(iframeUrl, mapOf("Referer" to "https://siiiiiiir.tv/"))
            } catch (e: Exception) {
                Log.e(TAG, "WebView: loadUrl failed", e)
                safeFinish()
            }
        }
    }

    // ponytail: extract server name from path pattern
    private fun extractServerName(url: String, quality: String): String? {
        return when {
            url.contains("/kc/") && url.contains("d0x1") -> "AR 1 $quality"
            url.contains("/kc/") && url.contains("d0x2") -> "AR 2 $quality"
            url.contains("d1xfr") -> "FR $quality"
            url.contains("d1xen") -> "EN $quality"
            else -> null
        }
    }
}
