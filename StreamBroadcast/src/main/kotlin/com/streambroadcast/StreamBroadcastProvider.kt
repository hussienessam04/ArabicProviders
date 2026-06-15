package com.streambroadcast

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class StreamBroadcastProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://streambroadcast.net"
    override var name = "StreamBroadcast"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "Origin" to "https://streambroadcast.net",
        "Referer" to "https://streambroadcast.net/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeLists = mutableListOf<HomePageList>()

        // 1. Fetch Soccer Matches
        val soccerMatches = fetchSoccerMatches()
        if (soccerMatches.isNotEmpty()) {
            val soccerResponses = soccerMatches.mapNotNull { match ->
                val homeName = match.home_en ?: match.home ?: "Home Team"
                val awayName = match.away_en ?: match.away ?: "Away Team"
                val titleText = "$homeName vs $awayName"
                val posterUrl = match.home_logo?.let { "https://ws.kora-api.space/uploads/logos/$it" } ?: "https://raw.githubusercontent.com/hussienessam04/ArabicProviders/main/Streamed/icon.png"

                val payload = LoadData(
                    title = titleText,
                    posterUrl = posterUrl,
                    soccerMatchId = match.id ?: return@mapNotNull null,
                    isSoccer = true
                )

                newLiveSearchResponse(
                    name = titleText,
                    url = payload.toJson()
                ) {
                    this.type = TvType.Live
                    this.posterUrl = payload.posterUrl
                }
            }
            if (soccerResponses.isNotEmpty()) {
                homeLists.add(HomePageList("Live & Upcoming Soccer", soccerResponses))
            }
        }

        // 2. Fetch All Sports Matches
        val allSports = try {
            val res = app.get("https://ws.kora-api.space/api/matches?per_page=100", headers = headers).text
            parseJson<MatchResponse>(res).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Categorize All Sports Matches
        val categories = listOf(
            CategoryFilter("NFL Streams", listOf("nfl", "american-football")),
            CategoryFilter("NBA Streams", listOf("nba", "basketball")),
            CategoryFilter("NHL Streams", listOf("nhl", "hockey")),
            CategoryFilter("MLB Streams", listOf("mlb", "baseball")),
            CategoryFilter("MMA / UFC / Boxing", listOf("ufc", "mma", "boxing", "combat", "fight", "wrestling")),
            CategoryFilter("Other Sports", emptyList()) // Fallback for uncategorized
        )

        val matchedIds = mutableSetOf<Int>()

        categories.forEach { filter ->
            val filteredList = if (filter.keywords.isEmpty()) {
                allSports.filter { m -> m.id != null && !matchedIds.contains(m.id) }
            } else {
                allSports.filter { m ->
                    val catName = m.category?.name?.lowercase().orEmpty()
                    val sportCode = m.category?.sport_code?.lowercase().orEmpty()
                    val name = m.name?.lowercase().orEmpty()
                    filter.keywords.any { k -> catName.contains(k) || sportCode.contains(k) || name.contains(k) }
                }
            }

            val responses = filteredList.mapNotNull { m ->
                m.id?.let { matchedIds.add(it) }
                val titleText = m.name ?: m.game_name ?: "Match"
                val parsedStreams = parseStreams(m.streams)
                val posterUrl = m.logo_team1 ?: m.category?.image ?: "https://raw.githubusercontent.com/hussienessam04/ArabicProviders/main/Streamed/icon.png"

                val payload = LoadData(
                    title = titleText,
                    posterUrl = posterUrl,
                    streams = parsedStreams,
                    isSoccer = false
                )

                newLiveSearchResponse(
                    name = titleText,
                    url = payload.toJson()
                ) {
                    this.type = TvType.Live
                    this.posterUrl = payload.posterUrl
                }
            }

            if (responses.isNotEmpty()) {
                homeLists.add(HomePageList(filter.name, responses))
            }
        }

        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResponse>()

        // 1. Fetch and search soccer matches
        val soccerMatches = fetchSoccerMatches()
        soccerMatches.forEach { match ->
            val homeName = match.home_en ?: match.home ?: "Home Team"
            val awayName = match.away_en ?: match.away ?: "Away Team"
            val titleText = "$homeName vs $awayName"
            val leagueName = match.league_en ?: match.league.orEmpty()

            if (titleText.lowercase().contains(normalizedQuery) || leagueName.lowercase().contains(normalizedQuery)) {
                val posterUrl = match.home_logo?.let { "https://ws.kora-api.space/uploads/logos/$it" } ?: "https://raw.githubusercontent.com/hussienessam04/ArabicProviders/main/Streamed/icon.png"
                val payload = LoadData(
                    title = titleText,
                    posterUrl = posterUrl,
                    soccerMatchId = match.id ?: return@forEach,
                    isSoccer = true
                )
                results.add(
                    newLiveSearchResponse(
                        name = titleText,
                        url = payload.toJson()
                    ) {
                        this.type = TvType.Live
                        this.posterUrl = payload.posterUrl
                    }
                )
            }
        }

        // 2. Fetch and search all sports matches
        val allSports = try {
            val res = app.get("https://ws.kora-api.space/api/matches?per_page=100", headers = headers).text
            parseJson<MatchResponse>(res).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        allSports.forEach { m ->
            val titleText = m.name ?: m.game_name ?: "Match"
            val catName = m.category?.name.orEmpty()

            if (titleText.lowercase().contains(normalizedQuery) || catName.lowercase().contains(normalizedQuery)) {
                val parsedStreams = parseStreams(m.streams)
                val posterUrl = m.logo_team1 ?: m.category?.image ?: "https://raw.githubusercontent.com/hussienessam04/ArabicProviders/main/Streamed/icon.png"
                val payload = LoadData(
                    title = titleText,
                    posterUrl = posterUrl,
                    streams = parsedStreams,
                    isSoccer = false
                )
                results.add(
                    newLiveSearchResponse(
                        name = titleText,
                        url = payload.toJson()
                    ) {
                        this.type = TvType.Live
                        this.posterUrl = payload.posterUrl
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(
            name = payload.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = payload.posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseJson<LoadData>(data)
        val sources = mutableListOf<StreamSource>()

        if (payload.isSoccer) {
            val soccerMatchId = payload.soccerMatchId ?: return false
            val detailUrl = "https://ws.kora-api.top/api/matche/$soccerMatchId/en?t=${System.currentTimeMillis()}"
            try {
                val res = app.get(detailUrl, headers = mapOf("Referer" to "https://strm01.app/"), timeout = 8000L).text
                val details = parseJson<SoccerMatchDetail>(res)
                details.channels?.forEachIndexed { index, channel ->
                    val name = channel.server_name ?: "Server ${index + 1}"
                    
                    // Try mobile_link first
                    var playerUrl = channel.mobile_link?.takeIf { it.isNotBlank() }
                    
                    // If no mobile_link, check link for token
                    if (playerUrl == null) {
                        val link = channel.link
                        if (link != null) {
                            if (link.contains("token=")) {
                                val token = link.substringAfter("token=").substringBefore("&")
                                val decoded = decodeToken(token)
                                if (decoded != null) {
                                    playerUrl = decoded.replace("https://href.li/?", "")
                                }
                            } else {
                                playerUrl = link
                            }
                        }
                    }
                    
                    if (playerUrl != null && playerUrl.isNotBlank()) {
                        sources.add(StreamSource(playerUrl, name))
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        } else {
            payload.streams?.let { sources.addAll(it) }
        }

        var linksFoundCount = 0
        var webViewCallsCount = 0
        val maxWebViewCalls = 3

        for (src in sources.take(6)) {
            val embedUrl = src.url
            if (embedUrl.contains(".m3u8")) {
                val refererUrl = try { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" } } catch (e: Exception) { "" }
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = src.name,
                        url = embedUrl
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                linksFoundCount++
            } else {
                val resolved = resolveDirectStream(embedUrl)
                if (resolved != null) {
                    val refererUrl = try { java.net.URI(resolved).let { "${it.scheme}://${it.host}/" } } catch (e: Exception) { "" }
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = src.name,
                            url = resolved
                        ) {
                            this.referer = refererUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksFoundCount++
                } else if (embedUrl.contains("streams.center/embed")) {
                    val m3u8Url = resolveStreamCenterLink(embedUrl)
                    if (m3u8Url != null && m3u8Url.contains(".m3u8")) {
                        val refererUrl = try { java.net.URI(m3u8Url).let { "${it.scheme}://${it.host}/" } } catch (e: Exception) { "" }
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = src.name,
                                url = m3u8Url
                            ) {
                                this.referer = refererUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        linksFoundCount++
                    } else {
                        if (linksFoundCount == 0 && webViewCallsCount < maxWebViewCalls) {
                            webViewCallsCount++
                            val resolvedWv = resolveWithWebView(embedUrl, "https://newsport-tv.com/")
                            if (resolvedWv != null && resolvedWv.contains(".m3u8")) {
                                callback(
                                    newExtractorLink(
                                        source = this.name,
                                        name = src.name,
                                        url = resolvedWv
                                    ) {
                                        this.referer = "https://streams.center/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                linksFoundCount++
                            }
                        }
                    }
                } else {
                    if (linksFoundCount < 2 && webViewCallsCount < maxWebViewCalls) {
                        webViewCallsCount++
                        val resolvedWv = resolveWithWebView(embedUrl, "https://newsport-tv.com/")
                        if (resolvedWv != null && resolvedWv.contains(".m3u8")) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = src.name,
                                    url = resolvedWv
                                ) {
                                    this.referer = ""
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            linksFoundCount++
                        } else {
                            loadExtractor(
                                embedUrl,
                                "https://newsport-tv.com/",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
        }

        return true
    }

    private fun decodeToken(token: String): String? {
        return try {
            val output = StringBuilder()
            var i = 0
            while (i < token.length) {
                val str = token.substring(i, i + 2)
                output.append(str.toInt(16).toChar())
                i += 2
            }
            output.toString()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveDirectStream(url: String): String? {
        try {
            val headersMap = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            val responseText = app.get(url, headers = headersMap, timeout = 6000L).text

            // 1. Check for AlbaPlayerControl base64 pattern
            val albaRegex = """AlbaPlayerControl\(\s*['"]([^'"]+)['"]""".toRegex()
            val albaMatch = albaRegex.find(responseText)
            if (albaMatch != null) {
                val base64Str = albaMatch.groupValues[1]
                val decodedBytes = java.util.Base64.getDecoder().decode(base64Str)
                return String(decodedBytes, Charsets.UTF_8).trim()
            }

            // 2. Check for Clappr/source pattern
            val sourceRegex = """source\s*[:=]\s*["']([^"']+)["']""".toRegex()
            val sourceMatch = sourceRegex.find(responseText)
            if (sourceMatch != null) {
                return sourceMatch.groupValues[1].trim()
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private suspend fun resolveStreamCenterLink(embedUrl: String): String? {
        val embedHtml = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "https://newsport-tv.com/"
                )
            ).text
        } catch (e: Exception) {
            return null
        }

        val iframeRegex = """//([^"/]+)/embed/hls2\.php\?stream=[a-zA-Z0-9]+""".toRegex()
        val matchResult = iframeRegex.find(embedHtml) ?: return null
        val hlsPath = matchResult.value
        val hlsUrl = if (hlsPath.startsWith("//")) "https:$hlsPath" else hlsPath

        val hlsHtml = try {
            app.get(
                hlsUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to embedUrl
                )
            ).text
        } catch (e: Exception) {
            return null
        }

        val inputRegex = """input\s*:\s*"([^"]+)"""".toRegex()
        val inputMatch = inputRegex.find(hlsHtml) ?: return null
        val inputVal = inputMatch.groupValues[1]

        val decryptUrl = hlsUrl.substringBefore("hls2.php") + "decrypt.php"
        return try {
            val decryptedUrl = app.post(
                decryptUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to hlsUrl,
                    "Origin" to "https://" + java.net.URI(hlsUrl).host,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("input" to inputVal)
            ).text
            decryptedUrl.trim()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSoccerMatches(): List<SoccerMatchItem> {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val date = format.format(java.util.Date())
        val tFormat = java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val t = tFormat.format(java.util.Date())

        val url = "https://ws.kora-api.space/api/matches/$date/1?t=$t"
        return try {
            val res = app.get(url, headers = headers).text
            val response = parseJson<SoccerResponse>(res)
            response.matches ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseStreams(streams: List<StreamItem>?): List<StreamSource> {
        val result = mutableListOf<StreamSource>()
        streams?.forEach { item ->
            val mainUrl = item.url ?: return@forEach
            val langField = item.lang.orEmpty()
            val firstLang = langField.substringBefore(";").ifBlank { "English" }
            result.add(StreamSource(mainUrl, firstLang))

            if (langField.contains(";")) {
                val rest = langField.substringAfter(";")
                if (rest.isNotBlank()) {
                    rest.split(";").forEach { part ->
                        if (part.contains("<")) {
                            val subUrl = part.substringBefore("<")
                            val subLang = part.substringAfter("<")
                            if (subUrl.isNotBlank()) {
                                result.add(StreamSource(subUrl, subLang))
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        referer: String
    ): String? = suspendCancellableCoroutine { cont ->
        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                attributes = attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
            }

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }

            try {
                dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                dialog.show()
            } catch (e: Exception) {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP))
                } catch (_: Exception) {}
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                blockNetworkImage = true
            }

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null

            fun cleanup() {
                activity.runOnUiThread {
                    try { timeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                    try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                    try { webView.stopLoading() } catch (_: Exception) {}
                    try { webView.destroy() } catch (_: Exception) {}
                    try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                }
            }

            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try { if (cont.isActive) cont.resume(result) } catch (_: Exception) {}
                cleanup()
            }

            timeoutRunnable = Runnable { safeFinish(null) }
            handler.postDelayed(timeoutRunnable!!, 12000)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val js = """
                        (function() {
                            Object.defineProperty(navigator, 'userActivation', { get: () => ({ hasBeenActive: true, isActive: true }) });
                            setInterval(function() {
                                try {
                                    document.querySelectorAll('video').forEach(function(v) {
                                        v.muted = true;
                                        if (v.paused) v.play();
                                    });
                                    if (typeof Clappr !== 'undefined' && window.player) {
                                        window.player.mute();
                                        window.player.play();
                                    }
                                } catch(e) {}
                            }, 1000);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    if (reqUrl.contains(".m3u8")) {
                        val cleanUrl = reqUrl.substringBefore("?")
                        if (cleanUrl.endsWith(".m3u8")) {
                            safeFinish(reqUrl)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            try {
                if (referer.isNotBlank()) {
                    webView.loadUrl(iframeUrl, mapOf("Referer" to referer))
                } else {
                    webView.loadUrl(iframeUrl)
                }
            } catch (e: Exception) {
                safeFinish(null)
            }
        }
    }

    data class LoadData(
        val title: String,
        val posterUrl: String? = null,
        val streams: List<StreamSource>? = null,
        val soccerMatchId: String? = null,
        val isSoccer: Boolean = false
    )

    data class StreamSource(
        val url: String,
        val name: String
    )

    data class MatchResponse(
        val data: List<MatchItem>? = null
    )

    data class MatchItem(
        val id: Int? = null,
        val name: String? = null,
        val description: String? = null,
        val game_name: String? = null,
        val category: Category? = null,
        val logo_team1: String? = null,
        val logo_team2: String? = null,
        val begin_at: String? = null,
        val end_at: String? = null,
        val is_live: Boolean? = null,
        val popular: Boolean? = null,
        val streams: List<StreamItem>? = null
    )

    data class Category(
        val id: Int? = null,
        val name: String? = null,
        val sport_code: String? = null,
        val image: String? = null
    )

    data class StreamItem(
        val url: String? = null,
        val lang: String? = null
    )

    data class SoccerResponse(
        val matches: List<SoccerMatchItem>? = null
    )

    data class SoccerMatchItem(
        val id: String? = null,
        val home_en: String? = null,
        val home: String? = null,
        val away_en: String? = null,
        val away: String? = null,
        val league_en: String? = null,
        val league: String? = null,
        val home_logo: String? = null,
        val away_logo: String? = null,
        val is_live: Boolean? = null,
        val time: String? = null,
        val active: String? = null,
        val has_channels: Any? = null
    )

    data class SoccerMatchDetail(
        val channels: List<SoccerChannel>? = null
    )

    data class SoccerChannel(
        val server_name: String? = null,
        val link: String? = null,
        val mobile_link: String? = null,
        val type: String? = null,
        val ch: String? = null,
        val edge: Any? = null
    )

    data class CategoryFilter(
        val name: String,
        val keywords: List<String>
    )
}
