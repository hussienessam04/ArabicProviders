package com.siiiirtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64

class SiiiiirTVProvider : MainAPI() {
    override var mainUrl = "https://www.siiiiir.tv"
    override var name = "Siiiiir TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to ua)

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

            val team1 = element.selectFirst(".TM1")?.text()?.trim() ?: ""
            val team2 = element.selectFirst(".TM2")?.text()?.trim() ?: ""
            if (team1.isBlank() || team2.isBlank()) return@mapNotNull null

            val score = element.selectFirst(".MT_Score")?.text()?.trim() ?: ""
            val time = element.selectFirst(".MT_Time")?.text()?.trim() ?: ""
            val title = if (score.isNotBlank()) "$team1 $score $team2" else "$team1 vs $team2"

            val poster = element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")

            newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
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
        val doc = app.get(url, headers = headers).document
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
        var found = false

        try {
            val resp = app.get(data, headers = headers)
            val doc = resp.document
            val finalUrl = resp.url

            val iframeSrc = doc.selectFirst("iframe#player")?.attr("src")
                ?: doc.selectFirst("iframe[src*='liveonlinesports']")?.attr("src")
                ?: doc.selectFirst(".entry-content iframe")?.attr("src")
                ?: doc.selectFirst(".player-wrapper iframe")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                val playerUrl = fixUrl(iframeSrc)
                found = resolvePlayer(playerUrl, finalUrl, callback) || found
            }

            if (!found && finalUrl.contains("liveonlinesports")) {
                found = resolvePlayer(finalUrl, finalUrl, callback) || found
            }

            doc.select(".video-serv a, [href*='.m3u8']").forEach { btn ->
                val href = btn.attr("href")
                if (href.isNotBlank()) {
                    if (href.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, href, referer = finalUrl)
                            .forEach { callback(it); found = true }
                    } else {
                        loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                            .let { if (it) found = true }
                    }
                }
            }

        } catch (_: Exception) { }

        return found
    }

    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val text = app.get(playerUrl, headers = headers + ("Referer" to referer)).text

            val tokenMatch = Regex("""["']token["']\s*[:=]\s*["']([A-Za-z0-9_\-+/=]+)["']""").find(text)
            if (tokenMatch != null) {
                val decoded = urlSafeDecode(tokenMatch.groupValues[1])
                if (decoded?.contains(".m3u8") == true) {
                    M3u8Helper.generateM3u8(name, decoded, referer = playerUrl)
                        .forEach { callback(it); found = true }
                }
            }

            if (!found) {
                val b64Match = Regex("""AlbaPlayerControl\(\s*['"]([^'"]+)['"]""").find(text)
                if (b64Match != null) {
                    val decoded = try {
                        String(Base64.decode(b64Match.groupValues[1], Base64.DEFAULT))
                    } catch (_: Exception) { null }
                    if (decoded?.contains(".m3u8") == true) {
                        M3u8Helper.generateM3u8(name, decoded, referer = playerUrl)
                            .forEach { callback(it); found = true }
                    }
                }
            }

            if (!found) {
                val srcMatch = Regex("""source\s*[:=]\s*['"]([^'"]+)['"]""").find(text)
                if (srcMatch != null) {
                    val src = srcMatch.groupValues[1]
                    if (src.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, src, referer = playerUrl)
                            .forEach { callback(it); found = true }
                    } else {
                        found = loadExtractor(src, playerUrl, {}, callback)
                    }
                }
            }

            if (!found) {
                val raw = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(text)
                if (raw != null) {
                    M3u8Helper.generateM3u8(name, raw.groupValues[1], referer = playerUrl)
                        .forEach { callback(it); found = true }
                }
            }

        } catch (_: Exception) { }
        return found
    }

    private fun urlSafeDecode(str: String): String? = runCatching {
        val b64 = str.replace("-", "+").replace("_", "/")
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        String(Base64.decode(b64, Base64.DEFAULT))
    }.getOrNull()
}
