package com.yacinetv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

        val detail = runCatching {
            app.get(
                "$detailBase/api/matche/${payload.matchId}/ar?t=${ts()}",
                headers = commonHeaders + ("Referer" to "https://strm01.app/"),
                timeout = 8000L
            ).text.let { parseJson<MatchDetail>(it) }
        }.getOrNull() ?: return false

        val channels = detail.channels ?: return false
        if (channels.isEmpty()) return false

        var linksFound = 0
        var webViewUsed = false

        // Sort: prefer channels whose hosts are known to work (score808, soccerball)
        // over the dead reddit-soccer-streams mirrors.
        val sorted = channels.sortedBy { ch ->
            val host = runCatching { java.net.URI(ch.link ?: ch.mobile_link ?: "").host ?: "" }.getOrDefault("")
            when {
                host.contains("score808", ignoreCase = true) -> 0
                host.contains("soccerball", ignoreCase = true) -> 1
                host.contains("reddit-soccer-streams", ignoreCase = true) -> 9
                else -> 5
            }
        }

        for ((idx, channel) in sorted.take(12).withIndex()) {
            if (linksFound >= 6) break
            val displayName = formatChannelName(channel, idx)

            val candidates = mutableListOf<String>()

            // Detect known-dead hosts so we can skip wasting time on them.
            val linkHost = runCatching { java.net.URI(channel.link ?: "").host ?: "" }.getOrDefault("")
            val linkIsDeadHost = linkHost.contains("reddit-soccer-streams", ignoreCase = true)

            // For dead-host channels, probe known-working AlbaPlayer iframe hosts
            // FIRST (before the dead link), using both the channel.ch field and
            // the ?ch= param extracted from the link URL.
            val chKeys = mutableSetOf<String>()
            channel.ch?.takeIf { it.isNotBlank() }?.let { chKeys.add(it) }
            channel.link?.let { lnk ->
                Regex("""[?&]ch=([^&]+)""").find(lnk)?.groupValues?.get(1)?.let { chKeys.add(it) }
            }
            if (chKeys.isNotEmpty()) {
                for (ck in chKeys) {
                    candidates.add("https://new.poiy.online/albaplayer/$ck/")
                    candidates.add("https://26.streemach.site/albaplayer/$ck/")
                    candidates.add("https://17.livekoora.blog/albaplayer/$ck/")
                }
            }

            // Standard candidates: mobile_link, decoded token, link itself.
            channel.mobile_link?.takeIf { it.isNotBlank() && it != "0" }?.let { candidates.add(it) }
            val decoded = channel.link?.let { decodeTokenFromLink(it) }
            if (decoded != null) candidates.add(decoded)
            if (channel.link != null && !channel.link!!.contains("token=")) candidates.add(channel.link!!)

            for (url in candidates.distinct()) {
                if (linksFound >= 6) break

                if (url.contains(".m3u8", ignoreCase = true)) {
                    pushLink(callback, displayName, url, channel.quality)
                    linksFound++
                    continue
                }

                val direct = resolveDirectStream(url)
                if (direct != null) {
                    pushLink(callback, displayName, direct, channel.quality)
                    linksFound++
                    continue
                }

                var extractorPushed = false
                runCatching {
                    loadExtractor(url, "https://yacinetv.watch/", { }, { link ->
                        extractorPushed = true
                        callback(link)
                    })
                }
                if (extractorPushed) {
                    linksFound++
                    continue
                }

                if (!webViewUsed && !linkIsDeadHost && !isDeadIframeHost(url)) {
                    webViewUsed = true
                    val wv = resolveWithWebView(url)
                    if (wv != null && wv.contains(".m3u8")) {
                        pushLink(callback, displayName, wv, channel.quality)
                        linksFound++
                    }
                }
            }
        }

        return linksFound > 0
    }

    private suspend fun pushLink(
        callback: (ExtractorLink) -> Unit,
        displayName: String,
        url: String,
        qualityLabel: String?
    ) {
        callback(
            newExtractorLink(source = name, name = displayName, url = url) {
                this.referer = refererFor(url)
                this.quality = qualityFrom(qualityLabel)
            }
        )
    }

    private fun isDeadIframeHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        return host.contains("reddit-soccer-streams", ignoreCase = true) ||
               host.contains("kora-plus.app", ignoreCase = true) ||
               host.contains("kora-top.mov", ignoreCase = true)
    }

    private fun formatChannelName(ch: Channel, index: Int): String {
        val base = ch.server_name?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}"
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

    private suspend fun resolveDirectStream(url: String): String? = runCatching {
        val text = app.get(
            url,
            headers = mapOf(
                "User-Agent" to (commonHeaders["User-Agent"] ?: ""),
                "Referer" to "https://yacinetv.watch/"
            ),
            timeout = 6000L
        ).text

        val albaMatch = Regex("""AlbaPlayerControl\(\s*['"]([^'"]+)['"]""").find(text)
        if (albaMatch != null) {
            val decoded = String(Base64.decode(albaMatch.groupValues[1], Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.contains(".m3u8")) return@runCatching decoded
        }

        val sourceMatch = Regex("""source\s*[:=]\s*["']([^"']+)["']""").find(text)
        val rawSource = sourceMatch?.groupValues?.get(1)?.trim()
        if (!rawSource.isNullOrBlank()) {
            if (rawSource.endsWith(".m3u8", ignoreCase = true) || rawSource.endsWith(".mp4", ignoreCase = true)) {
                return@runCatching rawSource
            }
            val resolved = resolvePhpWrapperToM3u8(rawSource, referer = url)
            if (resolved != null) return@runCatching resolved
        }

        val m3u8Match = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(text)
        if (m3u8Match != null) return@runCatching m3u8Match.groupValues[1]

        null
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
    private suspend fun resolveWithWebView(iframeUrl: String): String? = suspendCancellableCoroutine { cont ->
        val activity = getActivity(context)
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        val isTv = runCatching {
            val mode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_TYPE_MASK
            mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        }.getOrDefault(false)

        handler.post {
            val webViewContext = activity ?: context

            val dialog: Dialog? = if (activity != null && !activity.isFinishing && !isTv) {
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
                if (Looper.myLooper() == mainLooper) {
                    runCleanup()
                } else {
                    handler.post(runCleanup)
                }
            }

            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try { if (cont.isActive) cont.resume(result) } catch (_: Exception) { }
                cleanup()
            }

            timeoutRunnable = Runnable { safeFinish(null) }
            handler.postDelayed(timeoutRunnable!!, 15000)

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        safeFinish(null)
                    }
                    super.onReceivedError(view, request, error)
                }

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
                webView.loadUrl(iframeUrl, mapOf("Referer" to "https://yacinetv.watch/"))
            } catch (e: Exception) {
                safeFinish(null)
            }
        }
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
