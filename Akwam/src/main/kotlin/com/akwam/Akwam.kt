package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlin.Pair

class Akwam : MainAPI() {
    data class PosterData(val posterUrl: String?)

    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )


    private fun getPoster(element: Element?): String? {
        return element?.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.isNullOrBlank()) {
            val base = request.data.trim()
            val pageUrl = if (page > 1) {
                when {
                    base.endsWith("/page/") -> "$base$page/"
                    base.contains("?") -> "$base&page=$page"
                    else -> "$base?page=$page"
                }
            } else base

            val doc = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                try {
                    app.get(pageUrl).document
                } catch (e: Exception) {
                    null
                }
            } ?: throw ErrorLoadingException("failed to load category page")

            val list = doc.select("div.col-lg-auto.col-md-4.col-6").mapNotNull { el ->
                val a = el.selectFirst("h3.entry-title a") ?: return@mapNotNull null
                val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val href = el.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null
                val poster = getPoster(el)
                val urlWithPoster = "$href#${poster ?: ""}"
                newAnimeSearchResponse(name = title, url = urlWithPoster) {
                    this.posterUrl = poster
                }
            }

            if (list.isEmpty()) throw ErrorLoadingException()
            return newHomePageResponse(listOf(HomePageList(request.name ?: "Ù‚Ø§Ø¦Ù…Ø©", list)))
        }

        val urls = listOf(
            "$mainUrl/movies" to "Ø£Ø­Ø¯Ø« Ø§Ù„Ø£ÙÙ„Ø§Ù…",
            "$mainUrl/series" to "Ø£Ø­Ø¯Ø« Ø§Ù„Ù…Ø³Ù„Ø³Ù„Ø§Øª",
            "$mainUrl/shows" to "Ø§Ù„Ø¹Ø±ÙˆØ¶",
            "$mainUrl/series?section=29&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª Ø¹Ø±Ø¨ÙŠ",
            "$mainUrl/series?section=32&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª ØªØ±ÙƒÙŠ",
            "$mainUrl/series?section=33&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª Ø§Ø³ÙŠÙˆÙŠØ©",
            "$mainUrl/series?section=30&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª Ø§Ø¬Ù†Ø¨ÙŠ",
            "$mainUrl/series?section=31&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ù…Ø³Ù„Ø³Ù„Ø§Øª Ù‡Ù†Ø¯ÙŠ",
            "$mainUrl/movies?section=29&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ø£ÙÙ„Ø§Ù… Ø¹Ø±Ø¨ÙŠ",
            "$mainUrl/movies?section=32&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ø£ÙÙ„Ø§Ù… ØªØ±ÙƒÙŠ",
            "$mainUrl/movies?section=33&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ø£ÙÙ„Ø§Ù… Ø§Ø³ÙŠÙˆÙŠØ©",
            "$mainUrl/movies?section=30&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ø£ÙÙ„Ø§Ù… Ø§Ø¬Ù†Ø¨ÙŠ",
            "$mainUrl/movies?section=31&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "Ø£ÙÙ„Ø§Ù… Ù‡Ù†Ø¯ÙŠ"
        )

        val items = ArrayList<HomePageList>()
        for ((baseUrl, titleName) in urls) {
            try {
                val fullUrl = if (page > 1) {
                    if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
                } else baseUrl

                val doc = try {
                    app.get(fullUrl).document
                } catch (_: Exception) {
                    null
                } ?: continue

                val list = doc.select("div.col-lg-auto.col-md-4.col-6").mapNotNull { el ->
                    val a = el.selectFirst("h3.entry-title a") ?: return@mapNotNull null
                    val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val href = el.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null
                    val poster = getPoster(el)
                    val urlWithPoster = "$href#${poster ?: ""}"
                    newAnimeSearchResponse(name = title, url = urlWithPoster) {
                        this.posterUrl = poster
                    }
                }
                if (list.isNotEmpty()) items.add(HomePageList(titleName, list))
            } catch (_: Exception) {
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/search?q=$q"
        val document = app.get(url).document
        return document.select("div.col-lg-auto.col-md-4.col-6").mapNotNull {
            val title = it.selectFirst("h3.entry-title a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = getPoster(it)

            val urlWithPoster = "$href#${poster ?: ""}"
            newMovieSearchResponse(name = title, url = urlWithPoster, type = TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun getEpisodeNumberFromString(name: String): Int? {
        return Regex("""\d+""").findAll(name).lastOrNull()?.value?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("#")
        val pageUrl = parts[0]
        val poster = parts.getOrNull(1)?.ifBlank { null }

        val defaultHeaders = mapOf("Referer" to mainUrl)
        val mainDoc = app.get(pageUrl, headers = defaultHeaders).document

        val title = mainDoc.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val plot = mainDoc.selectFirst("h2:contains(Ù‚ØµØ© Ø§Ù„Ù…Ø³Ù„Ø³Ù„) + div > p")?.text()?.trim()
            ?: mainDoc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val rating = mainDoc.selectFirst("span.mx-2:contains(/)")
            ?.text()?.substringAfter("/")?.trim()?.toIntOrNull()

        val tags =
            mainDoc.select("div.font-size-16.text-white a[href*='/genre/'], div.font-size-16.text-white a[href*='/category/']")
                .map { it.text() }

        val year =
            mainDoc.select("div.font-size-16.text-white a[href*='/year/']").firstOrNull()?.text()
                ?.toIntOrNull()


        val recommendations = mainDoc.select("div.widget-body div[class*='col-']").mapNotNull {
            val recTitle = it.selectFirst("h3 a")?.text()?.trim() ?: return@mapNotNull null
            val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = getPoster(it)
            val urlWithPoster = "$recHref#${recPoster ?: ""}"
            newMovieSearchResponse(recTitle, urlWithPoster, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        val seasonsMap = linkedMapOf<String, Pair<String, String>>()
        val currentSeasonName = mainDoc.selectFirst("h1.entry-title")?.text()?.trim() ?: title
        seasonsMap[pageUrl] = Pair(currentSeasonName, pageUrl)

        val seasonSelector = "div.widget-body > a.btn[href*='/series/']"
        mainDoc.select(seasonSelector).forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank()) {
                val seasonUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val seasonName = a.text().trim()
                if (!seasonsMap.containsKey(seasonUrl)) {
                    seasonsMap[seasonUrl] = Pair(seasonName, seasonUrl)
                }
            }
        }

        val directEpisodes = mainDoc.select("div#series-episodes div[class*='col-']")
        val isSeries = seasonsMap.size > 1 || directEpisodes.isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(
                name = title,
                url = pageUrl,
                type = TvType.Movie,
                dataUrl = pageUrl
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
        // rating = rating
                this.recommendations = recommendations // <-- Ø¥Ø¶Ø§ÙØ© Ø§Ù„ØªÙˆØµÙŠØ§Øª Ù‡Ù†Ø§
            }
        }

        val sortedSeasons = seasonsMap.values.sortedBy { getSeasonNumber(it.first) }
        val allEpisodes = mutableListOf<Episode>()
        val docCache = mutableMapOf(pageUrl to mainDoc)

        for ((seasonName, seasonUrl) in sortedSeasons) {
            val seasonNumber = getSeasonNumber(seasonName)
            val seasonDoc = docCache.getOrPut(seasonUrl) {
                app.get(seasonUrl, headers = defaultHeaders).document
            }
            seasonDoc.select("div#series-episodes div.col-lg-4, div#series-episodes div.col-md-6")
                .forEach { episodeContainer ->
                    val episodeLink =
                        episodeContainer.selectFirst("a[href*='/episode/']") ?: return@forEach
                    val epUrl = episodeLink.attr("abs:href")
                    val epName =
                        episodeLink.selectFirst("h2")?.text()?.trim() ?: episodeLink.text().trim()
                    val epPoster = getPoster(episodeContainer)
                    if (epUrl.isNotBlank() && epName.isNotBlank()) {
                        allEpisodes.add(newEpisode(epUrl) {
                            name = epName
                            this.season = seasonNumber
                            this.episode = getEpisodeNumberFromString(epName)
                            this.posterUrl = epPoster
                        })
                    }
                }
        }

        if (allEpisodes.isEmpty()) {
            return newMovieLoadResponse(
                name = title,
                url = pageUrl,
                type = TvType.Movie,
                dataUrl = pageUrl
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
        // rating = rating
                this.recommendations = recommendations // <-- Ø¥Ø¶Ø§ÙØ© Ø§Ù„ØªÙˆØµÙŠØ§Øª Ù‡Ù†Ø§
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = pageUrl,
            type = TvType.TvSeries,
            episodes = allEpisodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        // rating = rating
            this.recommendations = recommendations // <-- Ø¥Ø¶Ø§ÙØ© Ø§Ù„ØªÙˆØµÙŠØ§Øª Ù‡Ù†Ø§
        }
    }

    private fun getSeasonNumber(seasonName: String): Int {
        val map = mapOf(
            "Ø§Ù„Ø§ÙˆÙ„" to 1,
            "Ø§Ù„Ø£ÙˆÙ„" to 1,
            "Ø§Ù„Ø«Ø§Ù†ÙŠ" to 2,
            "Ø§Ù„Ø«Ø§Ù„Ø«" to 3,
            "Ø§Ù„Ø±Ø§Ø¨Ø¹" to 4,
            "Ø§Ù„Ø®Ø§Ù…Ø³" to 5,
            "Ø§Ù„Ø³Ø§Ø¯Ø³" to 6,
            "Ø§Ù„Ø³Ø§Ø¨Ø¹" to 7,
            "Ø§Ù„Ø«Ø§Ù…Ù†" to 8,
            "Ø§Ù„ØªØ§Ø³Ø¹" to 9,
            "Ø§Ù„Ø¹Ø§Ø´Ø±" to 10,
            "Ø§Ù„Ø­Ø§Ø¯ÙŠ Ø¹Ø´Ø±" to 11,
            "Ø§Ù„Ø«Ø§Ù†ÙŠ Ø¹Ø´Ø±" to 12,
            "Ø§Ù„Ø«Ø§Ù„Ø« Ø¹Ø´Ø±" to 13,
            "Ø§Ù„Ø±Ø§Ø¨Ø¹ Ø¹Ø´Ø±" to 14,
            "Ø§Ù„Ø®Ø§Ù…Ø³ Ø¹Ø´Ø±" to 15,
            "Ø§Ù„Ø³Ø§Ø¯Ø³ Ø¹Ø´Ø±" to 16,
            "Ø§Ù„Ø³Ø§Ø¨Ø¹ Ø¹Ø´Ø±" to 17,
            "Ø§Ù„Ø«Ø§Ù…Ù† Ø¹Ø´Ø±" to 18,
            "Ø§Ù„ØªØ§Ø³Ø¹ Ø¹Ø´Ø±" to 19,
            "Ø§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 20,
            "Ø§Ù„Ø­Ø§Ø¯ÙŠ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 21,
            "Ø§Ù„Ø«Ø§Ù†ÙŠ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 22,
            "Ø§Ù„Ø«Ø§Ù„Ø« ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 23,
            "Ø§Ù„Ø±Ø§Ø¨Ø¹ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 24,
            "Ø§Ù„Ø®Ø§Ù…Ø³ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 25,
            "Ø§Ù„Ø³Ø§Ø¯Ø³ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 26,
            "Ø§Ù„Ø³Ø§Ø¨Ø¹ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 27,
            "Ø§Ù„Ø«Ø§Ù…Ù† ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 28,
            "Ø§Ù„ØªØ§Ø³Ø¹ ÙˆØ§Ù„Ø¹Ø´Ø±ÙˆÙ†" to 29,
            "Ø§Ù„Ø«Ù„Ø§Ø«ÙˆÙ†" to 30
        )
        val lower = seasonName.lowercase()
        for ((k, v) in map) {
            if (lower.contains(k)) return v
        }
        val nums = Regex("\\d+").findAll(seasonName).map { it.value.toIntOrNull() ?: 0 }.toList()
        if (nums.isNotEmpty()) return nums.last()
        return 999
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = data

        try {
            val step1Doc = try {
                app.get(episodeUrl).document
            } catch (e: Exception) {
                return false
            }

            val watchPathElement = step1Doc.selectFirst("a.link-show")
            val pageIdElement = step1Doc.selectFirst("input#page_id")

            if (watchPathElement == null || pageIdElement == null) {
                return false
            }

            val watchPath =
                watchPathElement.attr("href").ifBlank { watchPathElement.attr("abs:href") }
            val pageId = pageIdElement.attr("value").ifBlank { pageIdElement.attr("data-value") }

            if (watchPath.isBlank() || pageId.isBlank()) {
                return false
            }

            val main = mainUrl.trimEnd('/')
            val watchSuffix = run {
                val idx = watchPath.indexOf("watch")
                if (idx >= 0) watchPath.substring(idx + "watch".length) else watchPath
            }.trim()
            val watchUrl = (main + "/watch" + watchSuffix.trimEnd('/') + "/" + pageId).replace(
                "//watch",
                "/watch"
            )
                .replace(":/", "://")

            val step2Doc = try {
                app.get(watchUrl).document
            } catch (e1: Exception) {
                try {
                    app.get(watchUrl, headers = mapOf("Referer" to episodeUrl)).document
                } catch (e2: Exception) {
                    return false
                }
            }

            val sourceElements = step2Doc.select("source[src]")

            if (sourceElements.isEmpty()) {
                return false
            }

            val seen = mutableSetOf<String>()
            for (srcEl in sourceElements) {
                val rawVideoUrl = srcEl.attr("abs:src").ifBlank { srcEl.attr("src") }.trim()

                val videoUrl = rawVideoUrl.replace(" ", "%20")
                    .replace("https://", "http://")

                if (videoUrl.isBlank()) continue
                if (!seen.add(videoUrl)) continue

                val qualityAttr =
                    srcEl.attr("size").ifBlank { srcEl.attr("label") }.ifBlank { "direct" }

                callback(
                    newExtractorLink(source = this.name, name = name, url = videoUrl) {
                        this.referer = episodeUrl
                        this.quality = getQualityFromName(qualityAttr)
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }
}


