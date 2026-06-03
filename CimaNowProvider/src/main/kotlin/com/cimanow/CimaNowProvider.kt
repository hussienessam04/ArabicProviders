package com.cimanow

import android.os.Handler
import android.util.Log
import kotlin.math.min
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.text.toIntOrNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.math.BigInteger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.javascript.Scriptable
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

import kotlin.text.RegexOption

class CimaNow : MainAPI() {
    override var name = "CimaNow"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val HEADERS = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")


    override val mainPage = mainPageOf(
        mainUrl to "Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ©",
        "$mainUrl/category/Ø§Ù„Ø§ÙÙ„Ø§Ù…/" to "Ø§Ù„Ø£ÙÙ„Ø§Ù…",
        "$mainUrl/category/Ø§Ù„Ù…Ø³Ù„Ø³Ù„Ø§Øª/" to "Ø§Ù„Ù…Ø³Ù„Ø³Ù„Ø§Øª",
        "$mainUrl/category/Ø§ÙÙ„Ø§Ù…-Ø§Ø¬Ù†Ø¨ÙŠØ©/" to "Ø£ÙÙ„Ø§Ù… Ø£Ø¬Ù†Ø¨ÙŠØ©",
        "$mainUrl/category/Ù…Ø³Ù„Ø³Ù„Ø§Øª-Ø§Ø¬Ù†Ø¨ÙŠØ©/" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª Ø£Ø¬Ù†Ø¨ÙŠØ©",
        "$mainUrl/category/Ø§ÙÙ„Ø§Ù…-Ø¹Ø±Ø¨ÙŠØ©/" to "Ø£ÙÙ„Ø§Ù… Ø¹Ø±Ø¨ÙŠØ©",
        "$mainUrl/category/Ù…Ø³Ù„Ø³Ù„Ø§Øª-Ø¹Ø±Ø¨ÙŠØ©/" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª Ø¹Ø±Ø¨ÙŠØ©",
        "$mainUrl/category/Ø§ÙÙ„Ø§Ù…-Ù‡Ù†Ø¯ÙŠØ©/" to "Ø£ÙÙ„Ø§Ù… Ù‡Ù†Ø¯ÙŠØ©",
        "$mainUrl/category/Ø§ÙÙ„Ø§Ù…-ØªØ±ÙƒÙŠØ©/" to "Ø£ÙÙ„Ø§Ù… ØªØ±ÙƒÙŠØ©",
        "$mainUrl/category/Ù…Ø³Ù„Ø³Ù„Ø§Øª-ØªØ±ÙƒÙŠØ©/" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª ØªØ±ÙƒÙŠØ©"
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.attr("abs:href")
        if (url.contains("javascript")) return null
        val posterUrl = select("img")?.attr("data-src")
        var title = select("li[aria-label=\"title\"]").html().replace(" <em>.*|\\\\n".toRegex(), "").replace("&nbsp;", "")
        val year = select("li[aria-label=\"year\"]").text().toIntOrNull()
        val tvType = if (url.contains("ÙÙŠÙ„Ù…|Ù…Ø³Ø±Ø­ÙŠØ©|Ø­ÙÙ„Ø§Øª".toRegex())) TvType.Movie else TvType.TvSeries

        val dubEl = select("li[aria-label=\"ribbon\"]:nth-child(2)").isNotEmpty()
        val dubStatus = if (dubEl) select("li[aria-label=\"ribbon\"]:nth-child(2)").text().contains("Ù…Ø¯Ø¨Ù„Ø¬")
        else select("li[aria-label=\"ribbon\"]:nth-child(1)").text().contains("Ù…Ø¯Ø¨Ù„Ø¬")
        if (dubStatus) title = "$title (Ù…Ø¯Ø¨Ù„Ø¬)"

        return newMovieSearchResponse(
            "$title ${select("li[aria-label=\"ribbon\"]:contains(Ø§Ù„Ù…ÙˆØ³Ù…)").text()}",
            url,
            tvType,
        ) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == mainUrl
        val url = if (isHomePage) {
            if (page > 1) return newHomePageResponse(emptyList())
            "$mainUrl/home"
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val doc = app.get(url, timeout = 60000, headers = HEADERS).document

        return if (isHomePage) {
            val pages = doc.select("section:has(span):has(.owl-body)").mapNotNull { section ->
                val nameElement = section.selectFirst("span") ?: return@mapNotNull null
                val name = nameElement.ownText().trim()
                val categoryUrl = nameElement.selectFirst("a")?.attr("abs:href")

                if (name.contains("Ø£Ø®ØªØ± ÙˆØ¬Ù‡ØªÙƒ Ø§Ù„Ù…ÙØ¶Ù„Ø©|ØªÙ… Ø§Ø¶Ø§ÙØªÙ‡ Ø­Ø¯ÙŠØ«Ø§Ù‹".toRegex())) return@mapNotNull null

                val list = section.select(".owl-body a").mapNotNull { element ->
                    element.toSearchResponse()
                }

                if (list.isEmpty()) return@mapNotNull null
                HomePageList(name, list, isHorizontalImages = true)
            }
            newHomePageResponse(pages.filter { it.list.isNotEmpty() })
        } else {
            val items = doc.select("section[aria-label='posts'] article").mapNotNull {
                it.selectFirst("a")?.toSearchResponse()
            }
            val hasNext = doc.select("ul[aria-label=\"pagination\"] li.active + li").isNotEmpty()
            newHomePageResponse(request.name, items, hasNext = hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", timeout = 60000, headers = HEADERS).document
        return doc.select("section article[aria-label='post'] a").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 60000, headers = HEADERS).document
        val posterUrl = doc.select("meta[property=\"og:image\"]").attr("content")
        val year = doc.select("article ul:nth-child(1) li a").last()?.text()?.toIntOrNull()
        val title = doc.select("title").text().split(" | ")[0]
        val isMovie = title.contains("ÙÙŠÙ„Ù…|Ø­ÙÙ„Ø§Øª|Ù…Ø³Ø±Ø­ÙŠØ©".toRegex())
        val youtubeTrailer = doc.select("iframe")?.attr("src")
        val synopsis = doc.select("ul#details li:contains(Ù„Ù…Ø­Ø©) p").text()
        val tags = doc.select("article ul").first()?.select("li")?.map { it.text() }
        val recommendations = doc.select("ul#related li").mapNotNull { element ->
            newMovieSearchResponse(
                element.select("img:nth-child(2)").attr("alt"),
                element.select("a").attr("abs:href"),
                TvType.Movie,
            ) {
                this.posterUrl = element.select("img:nth-child(2)").attr("src")
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                addTrailer(youtubeTrailer)
            }
        } else {
            val seasons = doc.select("section[aria-label=\"seasons\"] ul li a").mapNotNull {
                Pair(it.attr("abs:href"), it.text().getIntFromText())
            }

            val episodes = if (seasons.isNotEmpty()) {
                seasons.amap { (seasonUrl, seasonNum) ->
                    val seasonDoc = app.get(seasonUrl, timeout = 60000, headers = HEADERS).document
                    seasonDoc.select("ul#eps li a").mapNotNull { epEl ->
                        newEpisode(epEl.attr("abs:href")) {
                            this.name = epEl.select("img:nth-child(2)").attr("alt")
                            this.season = seasonNum
                            this.episode = epEl.select("em").text().toIntOrNull()
                            this.posterUrl = posterUrl
                        }
                    }
                }.flatten()
            } else {
                doc.select("ul#eps li a").mapNotNull { epEl ->
                    newEpisode(epEl.attr("abs:href")) {
                        this.name = epEl.select("img:nth-child(2)").attr("alt")
                        this.season = doc.select("span[aria-label=\"season-title\"]").text().getIntFromText()
                        this.episode = epEl.select("em").text().toIntOrNull()
                        this.posterUrl = posterUrl
                    }
                }
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinctBy { it.name }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations
                addTrailer(youtubeTrailer)
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val serverLogTag = "CimaNowDecode"

        val serverRegex = Regex("<li[^>]+data-index=\"(\\d+)\"[^>]+data-id=\"(\\d+)\"[^>]*>([^<]+)</li>")
        val iframeSrcRegex = Regex("<iframe[^>]+src=\"([^\"]+)\"")

        val watchUrl = data // No /watching/ appended!

        try {
            val responseText = withContext(Dispatchers.IO) {
                app.get(
                    watchUrl,
                    referer = "https://cimanow.cc/",
                    headers = HEADERS,
                    timeout = 30000,
                    interceptor = WebViewResolver(Regex("data-index=\"\\d+\"|class=\"server\""))
                ).text
            }

            Log.e(serverLogTag, "HTML DUMP 1: " + responseText.take(2500))

            // Extract iframes
            val iframes = iframeSrcRegex.findAll(responseText).mapNotNull { it.groups[1]?.value }.toList()
            iframes.forEach { iframeUrl ->
                if (!iframeUrl.contains("youtube.com")) {
                    val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                    Log.i(serverLogTag, "Found embedded iframe: $finalUrl")
                    loadExtractor(finalUrl, "https://cimanow.cc/", subtitleCallback, callback)
                }
            }

            // Extract servers
            val servers = serverRegex.findAll(responseText)
                .mapNotNull { match ->
                    val (index, id, name) = match.destructured
                    Triple(index, id, name.trim())
                }.toList()

            Log.i(serverLogTag, "Extracted ${servers.size} servers from DOM")

            servers.map { (index, id, name) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(5000) {
                        try {
                            val switchUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$id"
                            val serverResponse = app.get(
                                switchUrl,
                                referer = "https://cimanow.cc/",
                                headers = HEADERS
                            ).text

                            if (name.equals("Cima Now", ignoreCase = true)) {
                                val iframeUrl = iframeSrcRegex.find(serverResponse)?.groupValues?.get(1)
                                if (!iframeUrl.isNullOrEmpty()) {
                                    val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                                    Log.i(serverLogTag, "[$name] CimaNow iframe: $finalUrl")

                                    try {
                                        val iframeResponse = app.get(
                                            finalUrl,
                                            referer = finalUrl,
                                            headers = HEADERS
                                        ).text

                                        val regex = Regex("""\[(\d+p)]\s+(/uploads/[^\"]+\.mp4)""")
                                        val baseUrl = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: ""

                                        regex.findAll(iframeResponse).forEach { match ->
                                            val quality = match.groupValues[1]
                                            val filePath = match.groupValues[2]
                                            val videoUrl = baseUrl + filePath

                                            val link = newExtractorLink(
                                                source = "CimaNow",
                                                name = "CimaNow $quality",
                                                url = videoUrl
                                            ).apply {
                                                this.quality = getQualityFromName(quality)
                                                this.referer = finalUrl
                                            }
                                            callback.invoke(link)
                                        }
                                    } catch (ex: Exception) {}
                                }
                            } else {
                                val iframeUrl = iframeSrcRegex.find(serverResponse)?.groupValues?.get(1)
                                if (!iframeUrl.isNullOrEmpty()) {
                                    val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                                    Log.i(serverLogTag, "[$name] -> iframe: $finalUrl")
                                    loadExtractor(finalUrl, "https://cimanow.cc/", subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            }.awaitAll()

            return@coroutineScope true
        } catch (e: Exception) {
            Log.e(serverLogTag, "loadLinks failed", e)
            return@coroutineScope false
        }
    }
}




