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
import okhttp3.Headers

import kotlin.text.RegexOption

class CimaNow : MainAPI() {
    override var name = "CimaNow"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
        "Cache-Control" to "no-cache, no-store",
        "Pragma" to "no-cache"
    )


    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/%D8%A7%D9%84%D8%A7%D8%AD%D8%AF%D8%AB/" to "الأحدث",
        "$mainUrl/%D8%A7%D9%84%D8%A7%D9%83%D8%AB%D8%B1-%D9%85%D8%B4%D8%A7%D9%87%D8%AF%D8%A9/" to "الأكثر مشاهدة",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9/" to "مسلسلات عربية",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9/" to "مسلسلات اجنبية",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%B1%D9%83%D9%8A%D8%A9/" to "مسلسلات تركية",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9/" to "افلام عربية",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9/" to "افلام اجنبية",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%AA%D8%B1%D9%83%D9%8A%D8%A9/" to "افلام تركية",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D9%87%D9%86%D8%AF%D9%8A%D8%A9/" to "افلام هندية",
        "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%8A%D9%85%D9%8A%D8%B4%D9%86/" to "افلام انيميشن",
        "$mainUrl/category/%D8%A7%D9%84%D8%A8%D8%B1%D8%A7%D9%85%D8%AC-%D8%A7%D9%84%D8%AA%D9%84%D9%81%D8%B2%D9%8A%D9%88%D9%86%D9%8A%D8%A9/" to "البرامج التلفزيونية",
        "$mainUrl/category/%D9%85%D8%B3%D8%B1%D8%AD%D9%8A%D8%A7%D8%AA/" to "مسرحيات",
        "$mainUrl/category/%D8%AD%D9%81%D9%84%D8%A7%D8%AA/" to "حفلات"
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

        // 1. Extract shineUrl and run token solver
        val detailsUrl = if (data.contains("/watching")) data.substringBefore("/watching") else data
        var shineUrl = try {
            val doc = app.get(detailsUrl, headers = HEADERS).document
            doc.select("a.shine").attr("abs:href")
        } catch (e: Exception) {
            Log.e(serverLogTag, "Failed to load details page to extract redirect URL", e)
            ""
        }

        val watchUrlResponseText = if (shineUrl.isNotEmpty()) {
            try {
                val redirectHost = try {
                    java.net.URI(shineUrl).host
                } catch (_: Exception) {
                    null
                } ?: "rm.freex2line.online"

                val cookiesMap = mutableMapOf<String, String>()

                fun updateCookies(headers: okhttp3.Headers) {
                    headers.values("Set-Cookie").forEach { cookieStr ->
                        val pair = cookieStr.substringBefore(";").split("=", limit = 2)
                        if (pair.size == 2) {
                            cookiesMap[pair[0].trim()] = pair[1].trim()
                        }
                    }
                }

                fun getHeadersWithCookies(): Map<String, String> {
                    if (cookiesMap.isEmpty()) return HEADERS
                    val cookieHeaderValue = cookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    return HEADERS + ("Cookie" to cookieHeaderValue)
                }

                Log.i(serverLogTag, "1. Requesting loadon redirect URL...")
                val res1 = app.get(shineUrl, referer = "https://cimanow.cc/", headers = HEADERS)
                Log.i(serverLogTag, "res1 headers: " + res1.headers)
                updateCookies(res1.headers)

                Log.i(serverLogTag, "2. Requesting redirectingfree...")
                val url2 = "https://$redirectHost/redirectingfree/"
                val res2 = app.get(url2, referer = shineUrl, headers = getHeadersWithCookies())
                Log.i(serverLogTag, "res2 headers: " + res2.headers)
                updateCookies(res2.headers)

                Log.i(serverLogTag, "3. Requesting blog-post.html...")
                val url3 = "https://$redirectHost/2020/02/blog-post.html"
                var res3 = app.get(url3, referer = url2, headers = getHeadersWithCookies(), allowRedirects = false)
                var redirectCount = 0
                while ((res3.code == 301 || res3.code == 302 || res3.code == 303 || res3.code == 307 || res3.code == 308) && redirectCount < 5) {
                    val location = res3.headers["Location"] ?: break
                    val nextUrl = if (location.startsWith("/")) {
                        "https://$redirectHost$location"
                    } else if (!location.startsWith("http")) {
                        "https://$redirectHost/2020/02/$location"
                    } else {
                        location
                    }
                    updateCookies(res3.headers)
                    res3 = app.get(nextUrl, referer = res3.url, headers = getHeadersWithCookies(), allowRedirects = false)
                    redirectCount++
                }
                updateCookies(res3.headers)
                val html = res3.text
                Log.i(serverLogTag, "res3 URL: " + res3.url)
                Log.i(serverLogTag, "res3 length: " + html.length)
                Log.i(serverLogTag, "res3 start: " + html.take(400))

                Log.i(serverLogTag, "4. Parsing variables...")
                val ptrRegex = Regex("""window\.ptr_\w+\s*=\s*['"](\w+)['"];""")
                val ctxName = ptrRegex.find(html)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse ctxName. HTML sample: " + html.take(400))

                val mapRegex = Regex("""(?s)window\.map_\w+\s*=\s*(\x7B.*?\x7D);""")
                val mapStr = mapRegex.find(html)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse mapStr")

                val chKey = Regex("""ch:\s*['"](\w+)['"]""").find(mapStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse chKey")
                val riKey = Regex("""ri:\s*['"](\w+)['"]""").find(mapStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse riKey")
                val keKey = Regex("""ke:\s*['"](\w+)['"]""").find(mapStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse keKey")
                val seKey = Regex("""se:\s*['"](\w+)['"]""").find(mapStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse seKey")

                val ctxRegex = Regex("""(?s)window\['$ctxName'\]\s*=\s*(\x7B.*?\x7D);""")
                val ctxStr = ctxRegex.find(html)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse ctxStr")

                val chVal = Regex("""['"]$chKey['"]:\s*['"]([^'"]*)['"]""").find(ctxStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse chVal")
                val riVal = Regex("""['"]$riKey['"]:\s*['"]([^'"]*)['"]""").find(ctxStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse riVal")
                val keVal = Regex("""['"]$keKey['"]:\s*['"]([^'"]*)['"]""").find(ctxStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse keVal")
                val seVal = Regex("""['"]$seKey['"]:\s*['"]([^'"]*)['"]""").find(ctxStr)?.groupValues?.get(1)
                    ?: throw Exception("Failed to parse seVal")

                Log.i(serverLogTag, "5. XOR Decrypting HMAC key...")
                val decodedKeBytes = Base64.decode(keVal, Base64.DEFAULT)
                val seBytes = seVal.toByteArray(Charsets.ISO_8859_1)
                val hmacKeyBytes = ByteArray(decodedKeBytes.size)
                for (i in decodedKeBytes.indices) {
                    val decodedKeByte = decodedKeBytes[i].toInt() and 0xFF
                    val seByte = seBytes[i % seBytes.size].toInt() and 0xFF
                    hmacKeyBytes[i] = (decodedKeByte xor seByte).toByte()
                }
                val hmacKey = String(hmacKeyBytes, Charsets.UTF_8)

                Log.i(serverLogTag, "6. Generating HMAC token...")
                val fpPlain = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36/en-US"
                val fpB64 = Base64.encodeToString(fpPlain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val dataToSign = "$riVal$chVal$fpB64"

                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                val secretKey = javax.crypto.spec.SecretKeySpec(hmacKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
                mac.init(secretKey)
                val sig = mac.doFinal(dataToSign.toByteArray(Charsets.UTF_8))
                val hmacToken = Base64.encodeToString(sig, Base64.NO_WRAP)

                Log.i(serverLogTag, "7. Simulating countdown wait...")
                delay(12000)

                Log.i(serverLogTag, "8. Fetching final watch URL via get-link.php...")
                val getLinkUrl = "https://$redirectHost/2020/02/blog-post.html/get-link.php"
                val resFinal = app.get(
                    getLinkUrl,
                    params = mapOf(
                        "request_id" to riVal,
                        "hmac_token" to hmacToken,
                        "ch" to chVal,
                        "fp" to fpB64
                    ),
                    referer = "https://$redirectHost/2020/02/blog-post.html/",
                    headers = getHeadersWithCookies()
                )
                var finalWatchUrl = resFinal.text
                if (finalWatchUrl.startsWith("\uFEFF")) {
                    finalWatchUrl = finalWatchUrl.substring(1)
                }
                finalWatchUrl = finalWatchUrl.trim()
                Log.i(serverLogTag, "Resolved watch URL: $finalWatchUrl")

                Log.i(serverLogTag, "9. Fetching resolved watch page...")
                app.get(
                    finalWatchUrl,
                    referer = "https://$redirectHost/2020/02/blog-post.html/",
                    headers = HEADERS
                ).text

            } catch (e: Exception) {
                Log.e(serverLogTag, "Blogger redirection/token bypass failed", e)
                ""
            }
        } else {
            ""
        }

        // Fallback to simple watch URL and WebViewResolver if programmatic bypass failed or was not possible
        val responseText = if (watchUrlResponseText.isNotEmpty() && (watchUrlResponseText.contains("hide_my_HTML_") || watchUrlResponseText.contains("atob"))) {
            watchUrlResponseText
        } else {
            Log.w(serverLogTag, "Falling back to WebViewResolver for watch page...")
            val fallbackWatchUrl = if (data.contains("/watching")) data else data.trimEnd('/') + "/watching/"
            try {
                withContext(Dispatchers.IO) {
                    app.get(
                        fallbackWatchUrl,
                        referer = "https://cimanow.cc/",
                        headers = HEADERS,
                        interceptor = WebViewResolver(Regex("hide_my_HTML_|atob")),
                        timeout = 60000
                    ).text
                }
            } catch (e: Exception) {
                Log.e(serverLogTag, "WebViewResolver fallback failed", e)
                ""
            }
        }

        var decryptedHtml = ""
        try {
            val scriptRegex = Regex("<script[^>]*>([\\s\\S]*?)</script>")
            val scripts = scriptRegex.findAll(responseText).map { it.groupValues[1] }
            
            for (script in scripts) {
                if (script.contains("atob") && script.contains(".split") && script.length > 5000) {
                    try {
                        val subMatch = Regex("parseInt\\([\\s\\S]+?\\)-\\s*([a-zA-Z0-9_]+)").find(script)
                        val encNameMatch = Regex("([a-zA-Z0-9_]+)\\.split\\(").find(script)
                        
                        if (subMatch != null && encNameMatch != null) {
                            val rVarName = subMatch.groupValues[1]
                            val rMatch = Regex("var\\s+$rVarName\\s*=\\s*([\\d\\+\\-\\s]+)\\s*;").find(script)
                            
                            if (rMatch != null) {
                                val rExpr = rMatch.groupValues[1]
                                val rVal = rExpr.split("+").filter { it.isNotBlank() }.map { it.trim().toInt() }.sum()
                                
                                val encName = encNameMatch.groupValues[1]
                                val valMatch = Regex("var\\s+$encName\\s*=\\s*([\\s\\S]+?);").find(script)
                                
                                if (valMatch != null) {
                                    val valExpr = valMatch.groupValues[1]
                                    val quotedRegex = Regex("['\"](.*?)['\"]")
                                    val encHtmlStr = quotedRegex.findAll(valExpr).map { it.groupValues[1] }.joinToString("")
                                    
                                    val decoded = StringBuilder()
                                    val numberBuilder = StringBuilder(10)
                                    encHtmlStr.split('~').forEach { part ->
                                        if (part.isNotEmpty()) {
                                            try {
                                                var paddedPart = part
                                                val missingPadding = paddedPart.length % 4
                                                if (missingPadding > 0) {
                                                    paddedPart += "=".repeat(4 - missingPadding)
                                                }
                                                val b64 = Base64.decode(paddedPart, Base64.DEFAULT)
                                                numberBuilder.setLength(0)
                                                for (byte in b64) {
                                                    val char = byte.toInt().toChar()
                                                    if (char.isDigit()) numberBuilder.append(char)
                                                }
                                                if (numberBuilder.isNotEmpty()) {
                                                    val digits = numberBuilder.toString()
                                                    val codePoint = digits.toInt() - rVal
                                                    if (codePoint in 0..0x10FFFF) decoded.append(codePoint.toChar())
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    decryptedHtml = decoded.toString()
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(serverLogTag, "Dynamic decryption failed", e)
                    }
                }
            }

            if (decryptedHtml.isEmpty()) {
                Log.e(serverLogTag, "❌ Failed to decrypt watch page HTML")
                return@coroutineScope false
            }

            // 5. Parse servers
            val servers = serverRegex.findAll(decryptedHtml)
                .mapNotNull { match ->
                    val (index, id, name) = match.destructured
                    Triple(index, id, name.trim())
                }.toList()

            Log.i(serverLogTag, "Extracted ${servers.size} servers from decrypted HTML")

            // 6. Request each server's iframe and load links
            servers.map { (index, id, name) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(8000) {
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




