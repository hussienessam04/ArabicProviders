package com.asia2tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Asia2tvProvider : MainAPI() {
    override var mainUrl = "https://ww1.asia2tv.pw"
    override var name = "Asia2TV 2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private fun getPosterFromElement(element: Element?): String? {
        return element?.selectFirst("div.image img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/category/asian-drama/", "Ø§Ù„Ø¯Ø±Ø§Ù…Ø§ Ø§Ù„Ø¢Ø³ÙŠÙˆÙŠØ©"),
            Pair("$mainUrl/category/asian-drama/korean/", "Ø¯Ø±Ø§Ù…Ø§ ÙƒÙˆØ±ÙŠØ©"),
            Pair("$mainUrl/category/asian-drama/japanese/", "Ø¯Ø±Ø§Ù…Ø§ ÙŠØ§Ø¨Ø§Ù†ÙŠØ©"),
            Pair("$mainUrl/category/asian-movies/", "Ø£ÙÙ„Ø§Ù… Ø¢Ø³ÙŠÙˆÙŠØ©"),
            Pair("$mainUrl/completed-dramas/", "Ø¯Ø±Ø§Ù…Ø§ Ù…ÙƒØªÙ…Ù„Ø©")
        )

        val items = urls.amap { (url, name) ->
            val pageUrl = if (page > 1) "$url/page/$page/" else url
            val soup = app.get(pageUrl).document
            val home = soup.select("div.box-item").mapNotNull {
                val a = it.selectFirst("div.postmovie-photo a") ?: return@mapNotNull null
                val title = a.attr("title")
                if (title.isBlank()) return@mapNotNull null
                val href = a.attr("href")
                val posterUrl = getPosterFromElement(it)
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = posterUrl
                }
            }
            HomePageList(name, home)
        }
        return newHomePageResponse(items.filter { it.list.isNotEmpty() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.box-item").mapNotNull {
            val a = it.selectFirst("div.postmovie-photo a") ?: return@mapNotNull null
            val title = a.attr("title")
            if (title.isBlank()) return@mapNotNull null
            val href = a.attr("href")
            val posterUrl = getPosterFromElement(it)

            val type = if (href.contains("/category/asian-movies/")) TvType.Movie else TvType.TvSeries
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }



    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url).document

        val title = soup.selectFirst("h1 span.title")?.text()?.trim() ?: "Unknown"

        val poster = soup.selectFirst("div.single-thumb-bg > img")?.attr("src")
            ?: soup.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = soup.selectFirst("div.getcontent p")?.text()?.trim()
        val tags = soup.select("div.box-tags a, li:contains(Ø§Ù„Ø¨Ù„Ø¯) a").map { it.text() }

        val year = soup.select("div.post-date")?.last()?.text()?.toIntOrNull()

        val episodeElements = soup.select("div.loop-episode a")
        if (episodeElements.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }


        val episodes = episodeElements.mapNotNull { element ->
            val href = element.attr("href")

            val episodeName = element.selectFirst("div.titlepisode")?.text()?.trim()

            if (episodeName.isNullOrBlank()) return@mapNotNull null

            val episodeNumber = episodeName.filter { it.isDigit() }.toIntOrNull()

            newEpisode(href) {
                this.name = episodeName
                this.episode = episodeNumber
                this.posterUrl = poster // Ø¥Ø¶Ø§ÙØ© Ø¨ÙˆØ³ØªØ± Ø§Ù„Ù…Ø³Ù„Ø³Ù„ Ù„ÙƒÙ„ Ø­Ù„Ù‚Ø©
            }
        }.reversed() // Ø¹ÙƒØ³ Ø§Ù„ØªØ±ØªÙŠØ¨ Ù„Ø£Ù† Ø§Ù„Ù…ÙˆØ§Ù‚Ø¹ ØºØ§Ù„Ø¨Ù‹Ø§ Ù…Ø§ ØªØ¹Ø±Ø¶ Ø§Ù„Ø­Ù„Ù‚Ø§Øª Ù…Ù† Ø§Ù„Ø£Ø­Ø¯Ø« Ù„Ù„Ø£Ù‚Ø¯Ù…


        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {


        val serverPageUrl = if (data.contains("/watching/")) {
            data // Ù‡Ø°Ø§ Ø¨Ø§Ù„ÙØ¹Ù„ Ø±Ø§Ø¨Ø· Ø§Ù„Ù…Ø´Ø§Ù‡Ø¯Ø©
        } else {
            val doc = app.get(data).document

            doc.selectFirst("div.loop-episode a.current")?.attr("href") // Ù„Ù„Ù…Ø³Ù„Ø³Ù„Ø§Øª
                ?: doc.selectFirst("a.watch_player")?.attr("href") // Ù„Ù„Ø£ÙÙ„Ø§Ù…
                ?: return false
        }

        val serversPage = app.get(serverPageUrl).document
        val serverUrls = serversPage.select("ul.server-list-menu li").mapNotNull {
            it.attr("data-server")
        }

        serverUrls.amap { url ->
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}

