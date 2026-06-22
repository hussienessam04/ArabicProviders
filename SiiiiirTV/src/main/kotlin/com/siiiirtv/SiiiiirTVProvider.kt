package com.siiiirtv

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
    private val playerHost = "https://siiiiiiir.tv"

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
        val matchId = data.substringAfterLast("#").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return false

        val apiUrl = "https://ws.kora-api.top/api/matche/$matchId/ar?t=${System.currentTimeMillis()}"
        val apiJson = runCatching { app.get(apiUrl, headers = headers).text }.getOrNull() ?: return false
        val matchData = runCatching { JSONObject(apiJson) }.getOrNull() ?: return false
        val channels = matchData.optJSONArray("channels") ?: return false

        val channelNames = (0 until channels.length()).mapNotNull { i ->
            val ch = channels.optJSONObject(i) ?: return@mapNotNull null
            val name = ch.optString("server_name").ifBlank { ch.optString("server_name_en") }
            val chId = ch.optString("ch")
            if (name.isBlank()) null else (name to chId)
        }
        if (channelNames.isEmpty()) return false

        val playerPageUrl = "$playerHost/hard/2908c7d4425d87350.html?match=$matchId"

        val resolvedM3u8 = resolveWithWebView(playerPageUrl)
            ?: runCatching {
                app.get(playerPageUrl, headers = headers).text
            }.getOrNull()?.let { html ->
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            }

        if (resolvedM3u8 == null) return false

        val m3u8Referer = runCatching {
            java.net.URI(resolvedM3u8).let { "${it.scheme}://${it.host}/" }
        }.getOrDefault("https://912acsss8af38.liveonlinesports.net/")
        val linkHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to m3u8Referer
        )

        var found = false
        for ((serverName, chId) in channelNames) {
            val displayName = "$name - $serverName"
            M3u8Helper.generateM3u8(
                source = name,
                name = displayName,
                streamUrl = resolvedM3u8,
                referer = m3u8Referer,
                headers = linkHeaders
            ).forEach { link ->
                callback(link)
                found = true
            }
        }
        return found
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        timeoutMs: Long = 25000
    ): String? = suspendCancellableCoroutine { cont ->
        val activity = getActivity(context)
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        handler.post {
            val webViewContext = activity ?: context
            val dialog = if (activity != null && !activity.isFinishing) Dialog(activity) else null

            if (dialog != null) {
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
            }

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
                } catch (_: Exception) {
                    try {
                        val decor = activity?.window?.decorView as? ViewGroup
                        decor?.addView(webView, FrameLayout.LayoutParams(1920, 1080, Gravity.START or Gravity.TOP))
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    val decor = (context as? Activity)?.window?.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1920, 1080, Gravity.START or Gravity.TOP))
                } catch (_: Exception) {}
            }

            try {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, 1920, 1080)
                webView.onResume()
                webView.resumeTimers()
            } catch (_: Exception) {}

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

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            var finished = false
            var timeoutRunnable: Runnable? = null

            fun cleanup() {
                val runCleanup = {
                    try { timeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                    try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                    try { webView.stopLoading() } catch (_: Exception) {}
                    try { webView.destroy() } catch (_: Exception) {}
                    try { if (dialog?.isShowing == true) dialog.dismiss() } catch (_: Exception) {}
                }
                if (Looper.myLooper() == mainLooper) runCleanup() else handler.post(runCleanup)
            }

            fun safeFinish(result: String?) {
                if (finished) return
                finished = true
                try { if (cont.isActive) cont.resume(result, onCancellation = null) } catch (_: Exception) {}
                cleanup()
            }

            timeoutRunnable = Runnable { safeFinish(null) }
            handler.postDelayed(timeoutRunnable!!, timeoutMs)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    if (reqUrl.contains(".m3u8")) {
                        safeFinish(reqUrl)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            try {
                webView.loadUrl(iframeUrl, headers)
            } catch (_: Exception) {
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
