package com.streamed

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

class StreamedProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val sportPriority = listOf(
        "live",
        "football",
        "basketball",
        "american-football",
        "baseball",
        "hockey",
        "fighting",
        "motor-sports",
        "tennis"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeLists = mutableListOf<HomePageList>()

        sportPriority.forEach { sport ->
            val matches = runCatching {
                val res = app.get("$mainUrl/api/matches/$sport").text
                parseJson<Array<MatchItem>>(res).toList()
            }.getOrDefault(emptyList())

            val responses = matches.mapNotNull { match ->
                match.toSearchResponse()
            }

            if (responses.isNotEmpty()) {
                homeLists.add(HomePageList(sport.toDisplayName(), responses))
            }
        }

        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        return sportPriority.flatMap { sport ->
            runCatching {
                val res = app.get("$mainUrl/api/matches/$sport").text
                parseJson<Array<MatchItem>>(res).toList()
            }.getOrDefault(emptyList())
        }.filter { match ->
            buildString {
                append(match.title.orEmpty())
                append(' ')
                append(match.category.orEmpty())
                append(' ')
                append(match.teams?.home?.name.orEmpty())
                append(' ')
                append(match.teams?.away?.name.orEmpty())
            }.contains(normalizedQuery, ignoreCase = true)
        }.mapNotNull { match ->
            match.toSearchResponse()
        }.distinctBy { it.url }
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
        
        val sourcesToTry = payload.allSources ?: listOf(MatchSource(payload.source, payload.matchId))
        
        val streams = sourcesToTry.flatMap { src ->
            val srcId = src.id ?: return@flatMap emptyList()
            val srcName = src.source ?: return@flatMap emptyList()
            val streamsRes = app.get("$mainUrl/api/stream/$srcName/$srcId").text
            runCatching { parseJson<Array<StreamItem>>(streamsRes).toList() }.getOrDefault(emptyList())
        }

        streams.forEachIndexed { index, stream ->
            val embedUrl = stream.embedUrl ?: return@forEachIndexed

            if (embedUrl.contains(".m3u8")) {
                val refererUrl = try { 
                    java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" } 
                } catch (e: Exception) { 
                    "https://embedsports.top/" 
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = buildString {
                            append(name)
                            append(" - ")
                            append(stream.language ?: "Stream ${index + 1}")
                            if (stream.hd == true) append(" HD")
                        },
                        url = embedUrl
                    ) {
                        this.referer = refererUrl
                        this.quality = if (stream.hd == true) Qualities.P1080.value else Qualities.Unknown.value
                    }
                )
            } else {
                val m3u8Url = resolveWithWebView(embedUrl, "$mainUrl/") ?: ""
                
                if (m3u8Url.contains(".m3u8")) {
                    val refererUrl = try { 
                        java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" } 
                    } catch (e: Exception) { 
                        "https://embedsports.top/" 
                    }
                    callback(
                        newExtractorLink(
                            source = name,
                            name = buildString {
                                append(name)
                                append(" - ")
                                append(stream.language ?: "Stream ${index + 1}")
                                if (stream.hd == true) append(" HD")
                            },
                            url = m3u8Url
                        ) {
                            this.referer = refererUrl
                            this.quality = if (stream.hd == true) Qualities.P1080.value else Qualities.Unknown.value
                        }
                    )
                } else {
                    loadExtractor(
                        embedUrl,
                        "$mainUrl/",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        return true
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
            handler.postDelayed(timeoutRunnable!!, 30000)

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
                webView.loadUrl(iframeUrl, mapOf("Referer" to referer))
            } catch (e: Exception) {
                safeFinish(null)
            }
        }
    }

    private fun MatchItem.toSearchResponse(): SearchResponse? {
        val sourceInfo = sources?.firstOrNull() ?: return null
        val titleText = title ?: listOfNotNull(teams?.home?.name, teams?.away?.name).joinToString(" vs ")
            .ifBlank { return null }

        val homeBadge = teams?.home?.badge
        val awayBadge = teams?.away?.badge
        val posterUrl = if (poster != null) {
            val suffix = if (poster.endsWith(".webp")) "" else ".webp"
            "$mainUrl$poster$suffix"
        } else if (homeBadge != null && awayBadge != null) {
            "$mainUrl/api/images/poster/$homeBadge/$awayBadge.webp"
        } else if (homeBadge != null) {
            "$mainUrl/api/images/badge/$homeBadge.webp"
        } else if (awayBadge != null) {
            "$mainUrl/api/images/badge/$awayBadge.webp"
        } else {
            "https://raw.githubusercontent.com/hussienessam04/ArabicProviders/main/Streamed/icon.png"
        }

        val payload = LoadData(
            matchId = sourceInfo.id ?: return null,
            source = sourceInfo.source ?: return null,
            title = titleText,
            posterUrl = posterUrl,
            allSources = sources
        )

        return newLiveSearchResponse(
            name = titleText,
            url = payload.toJson()
        ) {
            this.type = TvType.Live
            this.posterUrl = payload.posterUrl
        }
    }

    private fun String.toDisplayName(): String {
        return split("-", "_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
    }

    data class MatchItem(
        val id: String? = null,
        val title: String? = null,
        val category: String? = null,
        val teams: Teams? = null,
        val sources: List<MatchSource>? = null,
        val poster: String? = null
    )

    data class Teams(
        val home: Team? = null,
        val away: Team? = null
    )

    data class Team(
        val name: String? = null,
        val badge: String? = null
    )

    data class MatchSource(
        val source: String? = null,
        val id: String? = null
    )

    data class StreamItem(
        val id: String? = null,
        val streamNo: Int? = null,
        val language: String? = null,
        val hd: Boolean? = null,
        val embedUrl: String? = null,
        val source: String? = null,
        val viewers: Int? = null
    )

    data class LoadData(
        val matchId: String,
        val source: String,
        val title: String,
        val posterUrl: String? = null,
        val allSources: List<MatchSource>? = null
    )
}