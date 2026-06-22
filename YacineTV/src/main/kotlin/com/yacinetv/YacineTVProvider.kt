package com.yacinetv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.suspendCancellableCoroutine

class YacineTVProvider(private val context: Context) : MainAPI() {

    override var mainUrl = "https://yacinetv.watch"
    override var name = "YacineTV"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiBase = "https://ws.kora-api.space"
    private val detailBase = "https://ws.kora-api.top"
    private val cdnBase = "https://cdn.kora-api.space"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json,text/plain,*/*"
    )

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private fun ts(): String =
        java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private suspend fun fetchMatches(): List<MatchItem> {
        val url = "$apiBase/api/matches/${today()}/1?t=${ts()}"
        return runCatching {
            val res = app.get(url, headers = commonHeaders).text
            parseJson<MatchesResponse>(res).matches ?: emptyList()
        }.getOrDefault(emptyList())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = fetchMatches()
        val homeLists = mutableListOf<HomePageList>()

        val live = matches.filter { it.status == 1 }.mapNotNull { it.toSearchResponse() }
        if (live.isNotEmpty()) {
            homeLists.add(HomePageList("Live Now — مباشر", live, isHorizontalImages = true))
        }

        val upcoming = matches.filter { it.status == 0 }.mapNotNull { it.toSearchResponse() }
        if (upcoming.isNotEmpty()) {
            homeLists.add(HomePageList("Today — اليوم", upcoming, isHorizontalImages = true))
        }

        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchMatches()
            .filter { m ->
                listOfNotNull(m.home_en, m.away_en, m.league_en, m.home, m.away, m.league)
                    .any { it.contains(q, ignoreCase = true) }
            }
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = parseJson<LoadData>(url)

        val detail = runCatching {
            app.get(
                "$detailBase/api/matche/${payload.matchId}/ar?t=${ts()}",
                headers = commonHeaders + ("Referer" to "https://strm01.app/")
            ).text.let { parseJson<MatchDetail>(it) }
        }.getOrNull()

        val league = detail?.league_en.orEmpty()
        val date = detail?.date.orEmpty()
        val time = detail?.time.orEmpty()
        val score = detail?.score?.takeIf { it.isNotBlank() && it != "-" }
        val statusText = when (detail?.status) {
            1 -> "LIVE — مباشر"
            0 -> "UPCOMING — قريباً"
            2 -> "FINISHED — انتهت"
            else -> ""
        }

        val plot = buildString {
            if (league.isNotBlank()) append(league)
            if (date.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(date)
            }
            if (time.isNotBlank()) append("  $time GMT")
            if (score != null) {
                if (isNotEmpty()) append('\n')
                append("النتيجة / Score: ").append(score)
            }
            if (statusText.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("الحالة / Status: ").append(statusText)
            }
            detail?.desc?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.ifBlank { null }

        return newLiveStreamLoadResponse(
            name = payload.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = payload.posterUrl
            this.backgroundPosterUrl = payload.posterUrl
            this.plot = plot
            this.tags = if (league.isNotBlank()) listOf(league) else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false

        Log.d("YacineTV", "loadLinks: matchId=${payload.matchId} title=${payload.title}")

        val detail = runCatching {
            app.get(
                "$detailBase/api/matche/${payload.matchId}/ar?t=${ts()}",
                headers = commonHeaders + ("Referer" to "https://strm01.app/"),
                timeout = 8000L
            ).text.let { parseJson<MatchDetail>(it) }
        }.getOrNull() ?: return false.also { Log.d("YacineTV", "loadLinks: detail fetch failed") }

        val channels = detail.channels ?: return false.also { Log.d("YacineTV", "loadLinks: no channels") }
        if (channels.isEmpty()) return false.also { Log.d("YacineTV", "loadLinks: channels empty") }
        Log.d("YacineTV", "loadLinks: ${channels.size} channels, edges=${detail.edges}, edge_domain=${detail.edge_domain}")

        val chByKey: Map<String, Channel> = channels.mapNotNull { ch -> ch.ch?.lowercase()?.let { it to ch } }.toMap()
        val pushedM3u8s = mutableSetOf<String>()
        val arabicChHints = setOf("max1", "max11", "alwan1")

        // Sort: Arabic-language channels first, then push dead reddit-soccer-streams
        // mirrors last. Mirrors the order the website (strm01.app) shows servers.
        val sorted = channels.sortedWith(
            compareBy<Channel> {
                val isArabic = it.language.equals("Ar", ignoreCase = true) ||
                    it.server_name?.contains(" AR", ignoreCase = true) == true ||
                    (it.ch != null && arabicChHints.any { h -> it.ch!!.contains(h, ignoreCase = true) })
                if (isArabic) 0 else 1
            }.thenBy {
                if (it.link?.contains("reddit-soccer-streams", ignoreCase = true) == true) 1 else 0
            }
        )

        // ----- Pass 1: Direct HTTP for score808/soccerball/poiy/livekoora -----
        // These channels have mobile_link (AlbaPlayer host) or decodable hex token.
        // They're not bot-blocked like kora-plus.app — just need the right regex.
        for ((idx, channel) in sorted.withIndex()) {
            if (channel.ch?.isNotBlank() == true) continue // edge channels handled via WebView
            val direct = channel.mobile_link?.takeIf { it.isNotBlank() && it != "0" }
                ?: channel.link?.let { decodeTokenFromLink(it) }
            if (direct.isNullOrBlank()) continue

            Log.d("YacineTV", "direct attempt[${idx}]: ${channel.server_name} via $direct")
            val m3u8 = resolveAlbaPlayer(direct, referer = "https://strm01.app/")
            if (m3u8 != null && pushedM3u8s.add(m3u8)) {
                val name = formatChannelName(channel, pushedM3u8s.size - 1)
                Log.d("YacineTV", "direct OK: $name -> $m3u8")
                pushLink(callback, name, m3u8, refererFor(direct), channel.quality)
            } else {
                Log.d("YacineTV", "direct FAILED: ${channel.server_name}")
            }
        }

        // ----- Pass 2: WebView via strm01.app for kora-plus.app edge channels -----
        // kora-plus.app bot-blocks any direct WebView load (60s timeout, returns Google).
        // The only working approach is to load strm01.app itself, which loads each
        // server's iframe INSIDE its own context (where kora-plus.app serves the
        // real stream page, not Google).
        if (pushedM3u8s.size < 10) {
            val strmUrl = "https://strm01.app/?m=${payload.matchId}&lang=ar"
            Log.d("YacineTV", "strm01 webview: $strmUrl")
            val captured = runCatching {
                captureStrm01App(strmUrl, chByKey, maxCaptures = 10 - pushedM3u8s.size)
            }.getOrDefault(emptyList())
            for ((name, m3u8, chKey) in captured) {
                if (pushedM3u8s.add(m3u8)) {
                    Log.d("YacineTV", "strm01 OK: $name (ch=$chKey) -> $m3u8")
                    pushLink(callback, name, m3u8, "https://strm01.app/", chKey, null)
                }
            }
        }

        Log.d("YacineTV", "loadLinks done: ${pushedM3u8s.size} links found")
        return pushedM3u8s.isNotEmpty()
    }

    private suspend fun pushLink(
        callback: (ExtractorLink) -> Unit,
        displayName: String,
        m3u8Url: String,
        referer: String,
        channelKey: String? = null,
        qualityLabel: String? = null
    ) {
        val host = runCatching { java.net.URI(m3u8Url).host ?: "" }.getOrDefault("")
        // For kora-plus.app m3u8s, the server rejects requests with the wrong
        // Referer (it returns an HTML error page instead of the m3u8 playlist).
        // The browser's WebView sent Referer = https://{host}/frame.php?ch={ch}&p=12
        // when loading the m3u8. We need to replicate that exactly.
        val effectiveReferer = if (channelKey != null && host.isNotBlank()) {
            "https://$host/frame.php?ch=$channelKey&p=12"
        } else {
            referer
        }
        val headers = mapOf(
            "User-Agent" to (commonHeaders["User-Agent"] ?: ""),
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to if (host.isNotBlank()) "https://$host" else referer,
            "Referer" to effectiveReferer
        )
        M3u8Helper.generateM3u8(
            source = name,
            name = displayName,
            streamUrl = m3u8Url,
            referer = effectiveReferer,
            headers = headers
        ).forEach { link ->
            callback(link)
        }
    }

    private fun formatChannelName(ch: Channel, index: Int): String {
        val base = when {
            ch.server_name?.isNotBlank() == true -> ch.server_name!!
            ch.server_name_en?.isNotBlank() == true -> ch.server_name_en!!
            ch.ch?.isNotBlank() == true -> ch.ch!!.uppercase()
            else -> "Server ${index + 1}"
        }
        val q = ch.quality?.takeIf { it.isNotBlank() }
        val l = ch.language?.takeIf { it.isNotBlank() }
        return when {
            q != null && l != null -> "$base ($q • $l)"
            q != null -> "$base ($q)"
            l != null -> "$base ($l)"
            else -> base
        }
    }

    private fun decodeTokenFromLink(link: String): String? {
        if (!link.contains("token=")) return null
        val token = link.substringAfter("token=").substringBefore("&")
        return decodeHexToken(token)?.replace("https://href.li/?", "")
    }

    private fun decodeHexToken(token: String): String? = runCatching {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < token.length) {
            val hex = token.substring(i, i + 2)
            sb.append(hex.toInt(16).toChar())
            i += 2
        }
        sb.toString()
    }.getOrNull()

    private suspend fun resolveAlbaPlayer(url: String, referer: String): String? = runCatching {
        val text = app.get(
            url,
            referer = referer,
            headers = commonHeaders,
            timeout = 8000L
        ).text

        // Pattern 1: CONFIG.token = "urlSafeBase64String" (strm01.app iframe)
        val tokenMatch = Regex("""token\s*[:=]\s*"([A-Za-z0-9_-]+)"""").find(text)
        if (tokenMatch != null) {
            val decoded = urlSafeBase64Decode(tokenMatch.groupValues[1])
            if (!decoded.isNullOrBlank() && decoded.contains(".m3u8")) return@runCatching decoded
        }

        // Pattern 2: AlbaPlayerControl('base64String') (SyriaLive pattern)
        val albaMatch = Regex("""AlbaPlayerControl\(\s*['"]([^'"]+)['"]""").find(text)
        if (albaMatch != null) {
            val decoded = runCatching {
                String(Base64.decode(albaMatch.groupValues[1], Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.contains(".m3u8")) return@runCatching decoded
        }

        // Pattern 3: source = "url" (direct m3u8 or PHP wrapper)
        val sourceMatch = Regex("""source\s*[:=]\s*["']([^"']+)["']""").find(text)
        val rawSource = sourceMatch?.groupValues?.get(1)?.trim()
        if (!rawSource.isNullOrBlank()) {
            if (rawSource.endsWith(".m3u8", ignoreCase = true)) return@runCatching rawSource
            val resolved = resolvePhpWrapperToM3u8(rawSource, referer = url)
            if (resolved != null) return@runCatching resolved
        }

        // Pattern 4: raw .m3u8 URL anywhere in the text
        val rawM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(text)
        if (rawM3u8 != null) return@runCatching rawM3u8.groupValues[1]

        null
    }.getOrNull()

    private fun urlSafeBase64Decode(str: String): String? = runCatching {
        var base64 = str.replace("-", "+").replace("_", "/")
        val padding = base64.length % 4
        if (padding != 0) base64 += "=".repeat(4 - padding)
        String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private suspend fun resolvePhpWrapperToM3u8(url: String, referer: String): String? = runCatching {
        val body = app.get(
            url,
            headers = mapOf(
                "User-Agent" to (commonHeaders["User-Agent"] ?: ""),
                "Referer" to referer
            ),
            timeout = 6000L
        ).text

        if (!body.trimStart().startsWith("#EXTM3U")) return@runCatching null

        // Master playlist: parse variants and pick the highest bandwidth.
        var bestUrl: String? = null
        var bestBw = -1L
        body.lineSequence().forEachIndexed { idx, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                // Next non-empty, non-comment line is the variant URL.
                for (i in (idx + 1) until body.lineSequence().count()) {
                    val candidate = body.lineSequence().toList().getOrNull(i)?.trim().orEmpty()
                    if (candidate.isBlank() || candidate.startsWith("#")) continue
                    if (candidate.endsWith(".m3u8", ignoreCase = true)) {
                        if (bw >= bestBw) { bestBw = bw; bestUrl = candidate }
                    }
                    break
                }
            }
        }
        bestUrl
    }.getOrNull()

    private fun refererFor(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}/"
    }.getOrDefault("")

    private fun qualityFrom(q: String?): Int = when (q?.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FHD", "1080P", "1080" -> Qualities.P1080.value
        "HD", "720P", "720" -> Qualities.P720.value
        "SD", "480P", "480" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun MatchItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val homeName = home_en ?: home ?: "Home"
        val awayName = away_en ?: away ?: "Away"
        val titleText = "$homeName vs $awayName"

        val posterUrl = home_logo?.let { "$cdnBase/uploads/team/$it" }
            ?: league_logo?.let { "$cdnBase/uploads/league/$it" }

        val payload = LoadData(
            matchId = id,
            title = titleText,
            posterUrl = posterUrl
        )

        return newLiveSearchResponse(
            name = titleText,
            url = payload.toJson()
        ) {
            this.type = TvType.Live
            this.posterUrl = posterUrl
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureStrm01App(
        strmUrl: String,
        chByKey: Map<String, Channel>,
        maxCaptures: Int
    ): List<Triple<String, String, String?>> = suspendCancellableCoroutine { cont ->
        val activity = getActivity(context)
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        handler.post {
            val webViewContext = activity ?: context
            val dialog: Dialog? = if (activity != null && !activity.isFinishing) {
                Dialog(activity).apply {
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    window?.apply {
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
                }
            } else null

            val webView = WebView(webViewContext).apply {
                layoutParams = ViewGroup.LayoutParams(1920, 1080)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }

            if (dialog != null) {
                try {
                    dialog.setContentView(webView, ViewGroup.LayoutParams(1920, 1080))
                    dialog.show()
                } catch (e: Exception) {
                    try {
                        val decor = activity?.window?.decorView as? ViewGroup
                        decor?.addView(webView, FrameLayout.LayoutParams(1920, 1080, Gravity.START or Gravity.TOP))
                    } catch (_: Exception) { }
                }
            } else if (activity != null) {
                try {
                    val decor = activity.window.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1920, 1080, Gravity.START or Gravity.TOP))
                } catch (_: Exception) { }
            }

            try {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, 1920, 1080)
                webView.onResume()
                webView.resumeTimers()
            } catch (_: Exception) { }

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
                userAgentString = commonHeaders["User-Agent"] ?: ""
                blockNetworkImage = true
            }
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            val captured = mutableListOf<Triple<String, String, String?>>()
            val capturedM3u8s = mutableSetOf<String>()
            var finished = false
            val finishLock = Any()
            var timeoutRunnable: Runnable? = null

            fun cleanup() {
                val runCleanup = {
                    try { timeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) { }
                    try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) { }
                    try { webView.stopLoading() } catch (_: Exception) { }
                    try { webView.destroy() } catch (_: Exception) { }
                    try { if (dialog?.isShowing == true) dialog.dismiss() } catch (_: Exception) { }
                }
                if (Looper.myLooper() == mainLooper) runCleanup() else handler.post(runCleanup)
            }

            fun safeFinish(result: List<Triple<String, String, String?>>) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try { if (cont.isActive) cont.resume(result) {} } catch (_: Exception) { }
                cleanup()
            }

            timeoutRunnable = Runnable {
                Log.d("YacineTV", "strm01 webview timeout, captured ${captured.size}")
                safeFinish(captured.toList())
            }
            handler.postDelayed(timeoutRunnable!!, 50000)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("YacineTV", "strm01 page finished, injecting click JS")
                    val js = """
                        (function() {
                            setTimeout(function() {
                                try {
                                    var btns = document.querySelectorAll('.btn-server');
                                    console.log('Found ' + btns.length + ' server buttons');
                                    btns.forEach(function(btn, i) {
                                        setTimeout(function() { try { btn.click(); } catch(e) {} }, i * 3500);
                                    });
                                } catch(e) {}
                            }, 2000);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    val cleanUrl = reqUrl.substringBefore("?")
                    if (cleanUrl.endsWith(".m3u8", ignoreCase = true)) {
                        synchronized(captured) {
                            if (capturedM3u8s.add(reqUrl)) {
                                val chKey = extractChKeyFromM3u8(reqUrl)
                                val name = extractChannelNameFromM3u8(reqUrl, chByKey, captured.size)
                                captured.add(Triple(name, reqUrl, chKey))
                                Log.d("YacineTV", "strm01 captured: $name (ch=$chKey) -> $reqUrl")
                                if (captured.size >= maxCaptures) {
                                    handler.post { safeFinish(captured.toList()) }
                                }
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            try {
                webView.loadUrl(strmUrl, mapOf("Referer" to "https://yacinetv.watch/"))
            } catch (e: Exception) {
                safeFinish(emptyList())
            }
        }
    }

    private fun extractChannelNameFromM3u8(
        url: String,
        chByKey: Map<String, Channel>,
        index: Int
    ): String {
        // kora-plus.app m3u8 URL: https://aN.kora-plus.app/live/{chKey}.m3u8?...
        val chKey = extractChKeyFromM3u8(url)
        if (chKey != null) {
            val channel = chByKey[chKey]
            if (channel != null) return formatChannelName(channel, index)
        }
        return "Live ${index + 1}"
    }

    private fun extractChKeyFromM3u8(url: String): String? {
        val match = Regex("""/live/([^./?]+)\.m3u8""").find(url)
        return match?.groupValues?.get(1)?.lowercase()
    }

    private fun getActivity(context: Context): Activity? {
        var currentContext = context
        while (currentContext is android.content.ContextWrapper) {
            if (currentContext is Activity) return currentContext
            currentContext = currentContext.baseContext
        }
        return null
    }

    data class LoadData(
        val matchId: String,
        val title: String,
        val posterUrl: String? = null
    )

    data class MatchesResponse(
        val matches: List<MatchItem>? = null
    )

    data class MatchItem(
        val id: String? = null,
        val status: Int? = null,
        val category: String? = null,
        val home: String? = null,
        val away: String? = null,
        val home_en: String? = null,
        val away_en: String? = null,
        val league: String? = null,
        val league_en: String? = null,
        val home_logo: String? = null,
        val away_logo: String? = null,
        val league_logo: String? = null,
        val time: String? = null,
        val date: String? = null,
        val score: String? = null
    )

    data class MatchDetail(
        val id: String? = null,
        val status: Int? = null,
        val home_en: String? = null,
        val away_en: String? = null,
        val league_en: String? = null,
        val league: String? = null,
        val date: String? = null,
        val time: String? = null,
        val score: String? = null,
        val desc: String? = null,
        val active: String? = null,
        val has_channels: Int? = null,
        val edges: List<String>? = null,
        val edge_domain: String? = null,
        val channels: List<Channel>? = null
    )

    data class Channel(
        val id: String? = null,
        val server_name: String? = null,
        val server_name_en: String? = null,
        val link: String? = null,
        val mobile_link: String? = null,
        val type: String? = null,
        val ch: String? = null,
        val key: String? = null,
        val edge: String? = null,
        val quality: String? = null,
        val language: String? = null
    )
}
