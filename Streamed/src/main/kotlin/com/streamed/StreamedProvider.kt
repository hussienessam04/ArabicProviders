package com.streamed

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val sportPriority = listOf(
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
                app.get("$mainUrl/api/matches/$sport").parsedSafe<List<MatchItem>>().orEmpty()
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
                app.get("$mainUrl/api/matches/$sport").parsedSafe<List<MatchItem>>().orEmpty()
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
        val streams = app.get("$mainUrl/api/stream/${payload.source}/${payload.matchId}")
            .parsedSafe<List<StreamItem>>()
            .orEmpty()

        streams.forEachIndexed { index, stream ->
            val embedUrl = stream.embedUrl ?: return@forEachIndexed

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
                    this.referer = "$mainUrl/"
                    this.quality = if (stream.hd == true) Qualities.P1080.value else Qualities.Unknown.value
                }
            )
        }

        return streams.isNotEmpty()
    }

    private fun MatchItem.toSearchResponse(): SearchResponse? {
        val matchId = id ?: return null
        val sourceInfo = sources?.firstOrNull() ?: return null
        val titleText = title ?: listOfNotNull(teams?.home?.name, teams?.away?.name).joinToString(" vs ")
            .ifBlank { return null }

        val payload = LoadData(
            matchId = matchId,
            source = sourceInfo.source ?: return null,
            title = titleText,
            posterUrl = category?.let { "$mainUrl/api/images/$it/$matchId" }
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
        val sources: List<MatchSource>? = null
    )

    data class Teams(
        val home: Team? = null,
        val away: Team? = null
    )

    data class Team(
        val name: String? = null
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
        val posterUrl: String? = null
    )
}