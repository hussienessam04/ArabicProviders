package com.footybite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.WebViewResolver

class FootybiteProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://live.footybite.to"
    override var name = "Footybite"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Live Football"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        ).document
        val home = document.select("a[href^=https://footybite.vc/]").mapNotNull {
            val href = it.attr("href")
            val title = it.text()
            if (title.isBlank()) return@mapNotNull null
            
            newLiveSearchResponse(
                name = title,
                url = href
            ) {
                this.type = TvType.Live
            }
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        ).document
        val title = document.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: "Footybite Match"

        val streamTable = document.select("table tr").mapNotNull { row ->
            val columns = row.select("td")
            if (columns.size < 7) return@mapNotNull null
            
            val streamLink = row.selectFirst("input[type=hidden]")?.attr("value") ?: ""
            if (streamLink.isBlank()) return@mapNotNull null

            val language = columns[4].text().trim()
            val quality = columns[3].text().trim()
            val channelName = columns[1].text().trim()
            val isArabic = language.contains("ar", ignoreCase = true) || language.contains("arabic", ignoreCase = true)

            val finalName = if (isArabic) "ARABIC - $channelName ($quality)" else "$language - $channelName ($quality)"

            MatchSource(streamLink, finalName)
        }

        val payload = LoadData(url, streamTable)

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = payload.toJson()
        ) {
            this.type = TvType.Live
        }
    }

    data class MatchSource(
        val url: String,
        val name: String
    )

    data class LoadData(
        val url: String,
        val sources: List<MatchSource>
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        
        loadData.sources.forEach { source ->
            val embedUrl = source.url
            if (embedUrl.contains(".m3u8")) {
                val refererUrl = try { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" } } catch (e: Exception) { "" }
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = source.name,
                        url = embedUrl
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                var m3u8Url = resolveWithWebView(embedUrl, "") ?: ""
                if (!m3u8Url.contains(".m3u8")) {
                    m3u8Url = try {
                        val interceptor = WebViewResolver(Regex(".*\\.m3u8.*"))
                        val res = app.get(embedUrl, interceptor = interceptor)
                        if (res.url.contains(".m3u8")) res.url else ""
                    } catch (e: Exception) {
                        ""
                    }
                }
                
                if (m3u8Url.isNotBlank()) {
                    val refererUrl = try { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" } } catch (e: Exception) { "" }
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = source.name,
                            url = m3u8Url
                        ) {
                            this.referer = refererUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    loadExtractor(
                        embedUrl,
                        "",
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
                } catch (e: Exception) {
                    try {
                        val decor = activity?.window?.decorView as? ViewGroup
                        decor?.addView(webView, FrameLayout.LayoutParams(1920, 1080, Gravity.START or Gravity.TOP))
                    } catch (_: Exception) {}
                }
            }

            // Always call layout and measure to ensure WebKit engine initializes properly and executes JS headlessly/without attachment
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
            var timeoutRunnable: Runnable? = null

            fun cleanup() {
                val runCleanup = {
                    try { timeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                    try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                    try { webView.stopLoading() } catch (_: Exception) {}
                    try { webView.destroy() } catch (_: Exception) {}
                    try { if (dialog?.isShowing == true) dialog.dismiss() } catch (_: Exception) {}
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
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

    private fun getActivity(context: Context): Activity? {
        var currentContext = context
        while (currentContext is android.content.ContextWrapper) {
            if (currentContext is Activity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
        return null
    }
}
