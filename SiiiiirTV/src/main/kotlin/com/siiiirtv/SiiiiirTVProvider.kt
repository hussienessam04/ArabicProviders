package com.siiiirtv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

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
        val streamUrl = fetchStreamUrl(burnerUrl) ?: return false

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

    // ponytail: no WebView — site uses browser fingerprinting (JA3/Cloudflare) that blocks non-Chrome TLS clients.
    // Falls back to OkHttp with full browser headers. If TLS fingerprint is rejected, this returns null fast (no 50s hang).
    private suspend fun fetchStreamUrl(burnerUrl: String): String? {
        val hardPageUrl = runCatching {
            val resp = app.get(burnerUrl, headers = browserHeaders, allowRedirects = true)
            resp.url
        }.getOrNull() ?: return null

        val hardHtml = runCatching {
            app.get(hardPageUrl, headers = browserHeaders + ("Referer" to "https://www.siiiiir.tv/")).text
        }.getOrNull() ?: return null

        val iframeMatch = Regex("""window\.__playerSrc\s*=\s*["']([^"']+)["']""").find(hardHtml)
        val iframeUrl = iframeMatch?.groupValues?.getOrNull(1) ?: return null

        val iframeHtml = runCatching {
            app.get(iframeUrl, headers = browserHeaders + ("Referer" to hardPageUrl)).text
        }.getOrNull() ?: return null

        val streamMatch = Regex("""https?://[^\s"'<>]+/kooora/[^\s"'<>]+""").find(iframeHtml)
        return streamMatch?.value
    }
}
