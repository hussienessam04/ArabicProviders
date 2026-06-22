package com.yacinetv

import android.content.Context
import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class YacineTVProvider(private val context: Context) : MainAPI() {

    override var mainUrl = "https://yacinetv.watch"
    override var name = "YacineTV"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiBase = "https://ws.kora-api.space"
    private val detailBase = "https://ws.kora-api.top"
    private val cdnBase = "https://cdn.kora-api.space"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json,text/plain,*/*"
    )

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private fun ts(): String =
        java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private suspend fun fetchMatches(): List<MatchItem> {
        val url = "$apiBase/api/matches/${today()}/1?t=${ts()}"
        return runCatching {
            val res = app.get(url, headers = commonHeaders).text
            parseJson<MatchesResponse>(res).matches ?: emptyList()
        }.getOrDefault(emptyList())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = fetchMatches()
        val homeLists = mutableListOf<HomePageList>()

        val live = matches.filter { it.status == 1 }.mapNotNull { it.toSearchResponse() }
        if (live.isNotEmpty()) {
            homeLists.add(HomePageList("Live Now — مباشر", live, isHorizontalImages = true))
        }

        val upcoming = matches.filter { it.status == 0 }.mapNotNull { it.toSearchResponse() }
        if (upcoming.isNotEmpty()) {
            homeLists.add(HomePageList("Today — اليوم", upcoming, isHorizontalImages = true))
        }

        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchMatches()
            .filter { m ->
                listOfNotNull(m.home_en, m.away_en, m.league_en, m.home, m.away, m.league)
                    .any { it.contains(q, ignoreCase = true) }
            }
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = parseJson<LoadData>(url)

        val detail = runCatching {
            app.get(
                "$detailBase/api/matche/${payload.matchId}/ar?t=${ts()}",
                headers = commonHeaders + ("Referer" to "https://strm01.app/")
            ).text.let { parseJson<MatchDetail>(it) }
        }.getOrNull()

        val league = detail?.league_en.orEmpty()
        val date = detail?.date.orEmpty()
        val time = detail?.time.orEmpty()
        val score = detail?.score?.takeIf { it.isNotBlank() && it != "-" }
        val statusText = when (detail?.status) {
            1 -> "LIVE — مباشر"
            0 -> "UPCOMING — قريباً"
            2 -> "FINISHED — انتهت"
            else -> ""
        }

        val plot = buildString {
            if (league.isNotBlank()) append(league)
            if (date.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(date)
            }
            if (time.isNotBlank()) append("  $time GMT")
            if (score != null) {
                if (isNotEmpty()) append('\n')
                append("النتيجة / Score: ").append(score)
            }
            if (statusText.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("الحالة / Status: ").append(statusText)
            }
            detail?.desc?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.ifBlank { null }

        return newLiveStreamLoadResponse(
            name = payload.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = payload.posterUrl
            this.backgroundPosterUrl = payload.posterUrl
            this.plot = plot
            this.tags = if (league.isNotBlank()) listOf(league) else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false

        Log.d("YacineTV", "loadLinks: matchId=${payload.matchId} title=${payload.title}")

        val detail = runCatching {
            app.get(
                "$detailBase/api/matche/${payload.matchId}/ar?t=${ts()}",
                headers = commonHeaders + ("Referer" to "https://strm01.app/"),
                timeout = 8000L
            ).text.let { parseJson<MatchDetail>(it) }
        }.getOrNull() ?: return false.also { Log.d("YacineTV", "loadLinks: detail fetch failed") }

        val channels = detail.channels ?: return false.also { Log.d("YacineTV", "loadLinks: no channels") }
        if (channels.isEmpty()) return false.also { Log.d("YacineTV", "loadLinks: channels empty") }
        Log.d("YacineTV", "loadLinks: ${channels.size} channels, edges=${detail.edges}, edge_domain=${detail.edge_domain}")

        var linksFound = 0
        val now = System.currentTimeMillis() / 1000
        val visitorId = java.util.UUID.randomUUID().toString()
        val edges = detail.edges ?: emptyList()
        val edgeDomain = detail.edge_domain
        val edgeCounter = java.util.concurrent.atomic.AtomicInteger(0)

        // Sort: Arabic-language channels first, then push dead reddit-soccer-streams
        // mirrors last. Mirrors the order the website (strm01.app) shows servers.
        val arabicChHints = setOf("max1", "max11", "alwan1")
        val sorted = channels.sortedWith(
            compareBy<Channel> {
                val isArabic = it.language.equals("Ar", ignoreCase = true) ||
                    it.server_name?.contains(" AR", ignoreCase = true) == true ||
                    (it.ch != null && arabicChHints.any { h -> it.ch!!.contains(h, ignoreCase = true) })
                if (isArabic) 0 else 1
            }.thenBy {
                if (it.link?.contains("reddit-soccer-streams", ignoreCase = true) == true) 1 else 0
            }
        )

        for ((idx, channel) in sorted.take(12).withIndex()) {
            if (linksFound >= 8) break
            val displayName = formatChannelName(channel, idx)
            Log.d("YacineTV", "channel[${idx}]: server_name=${channel.server_name} ch=${channel.ch} link=${channel.link} lang=${channel.language} quality=${channel.quality}")

            // ----- Direct iframe URL (edge=0 score808 / soccerball / poiy) -----
            val direct = channel.mobile_link?.takeIf { it.isNotBlank() && it != "0" }
                ?: channel.link?.let { decodeTokenFromLink(it) }

            if (!direct.isNullOrBlank()) {
                val m3u8 = resolveAlbaPlayer(direct, referer = "https://strm01.app/")
                if (m3u8 != null) {
                    Log.d("YacineTV", "direct OK: $displayName -> $m3u8")
                    pushLink(callback, displayName, m3u8, refererFor(direct), channel.quality)
                    linksFound++
                    continue
                }
            }

            // ----- Edge iframe (kora-plus.{app,mov}) via WebViewResolver -----
            // strm01.app builds: https://{edge}.{edge_domain}/frame.php?ch={ch}&p=12&token={visitorId}&kt={ts}
            // The iframe page bot-blocks direct HTTP, so we MUST go through WebView.
            val chKey = channel.ch?.takeIf { it.isNotBlank() }
            if (chKey != null && !edgeDomain.isNullOrBlank() && edges.isNotEmpty()) {
                val edge = edges[edgeCounter.getAndIncrement() % edges.size]
                val edgeIframe = "https://$edge.$edgeDomain/frame.php?ch=$chKey&p=12&token=$visitorId&kt=$now"
                Log.d("YacineTV", "webview attempt: $displayName via $edge")
                val m3u8 = runCatching {
                    val interceptor = WebViewResolver(Regex(".*\\.m3u8.*"))
                    val res = app.get(
                        edgeIframe,
                        referer = "https://strm01.app/",
                        interceptor = interceptor,
                        timeout = 25000L
                    )
                    if (res.url.contains(".m3u8")) res.url else null
                }.getOrNull()
                if (m3u8 != null) {
                    Log.d("YacineTV", "webview OK: $displayName -> $m3u8")
                    pushLink(callback, displayName, m3u8, "https://$edge.$edgeDomain/", channel.quality)
                    linksFound++
                } else {
                    Log.d("YacineTV", "webview FAILED: $displayName via $edge")
                }
            }
        }

        Log.d("YacineTV", "loadLinks done: $linksFound links found")
        return linksFound > 0
    }

    private suspend fun pushLink(
        callback: (ExtractorLink) -> Unit,
        displayName: String,
        m3u8Url: String,
        referer: String,
        qualityLabel: String?
    ) {
        val host = runCatching { java.net.URI(m3u8Url).host ?: "" }.getOrDefault("")
        val headers = mapOf(
            "User-Agent" to (commonHeaders["User-Agent"] ?: ""),
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to if (host.isNotBlank()) "https://$host" else referer,
            "Referer" to referer
        )
        M3u8Helper.generateM3u8(
            source = name,
            name = displayName,
            streamUrl = m3u8Url,
            referer = referer,
            headers = headers
        ).forEach { link ->
            callback(link)
        }
    }

    private fun formatChannelName(ch: Channel, index: Int): String {
        val base = when {
            ch.server_name?.isNotBlank() == true -> ch.server_name!!
            ch.server_name_en?.isNotBlank() == true -> ch.server_name_en!!
            ch.ch?.isNotBlank() == true -> ch.ch!!.uppercase()
            else -> "Server ${index + 1}"
        }
        val q = ch.quality?.takeIf { it.isNotBlank() }
        val l = ch.language?.takeIf { it.isNotBlank() }
        return when {
            q != null && l != null -> "$base ($q • $l)"
            q != null -> "$base ($q)"
            l != null -> "$base ($l)"
            else -> base
        }
    }

    private fun decodeTokenFromLink(link: String): String? {
        if (!link.contains("token=")) return null
        val token = link.substringAfter("token=").substringBefore("&")
        return decodeHexToken(token)?.replace("https://href.li/?", "")
    }

    private fun decodeHexToken(token: String): String? = runCatching {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < token.length) {
            val hex = token.substring(i, i + 2)
            sb.append(hex.toInt(16).toChar())
            i += 2
        }
        sb.toString()
    }.getOrNull()

    private suspend fun resolveAlbaPlayer(url: String, referer: String): String? = runCatching {
        val text = app.get(
            url,
            referer = referer,
            headers = commonHeaders,
            timeout = 8000L
        ).text

        // Pattern 1: CONFIG.token = "urlSafeBase64String" (strm01.app iframe)
        val tokenMatch = Regex("""token\s*[:=]\s*"([A-Za-z0-9_-]+)"""").find(text)
        if (tokenMatch != null) {
            val decoded = urlSafeBase64Decode(tokenMatch.groupValues[1])
            if (!decoded.isNullOrBlank() && decoded.contains(".m3u8")) return@runCatching decoded
        }

        // Pattern 2: AlbaPlayerControl('base64String') (SyriaLive pattern)
        val albaMatch = Regex("""AlbaPlayerControl\(\s*['"]([^'"]+)['"]""").find(text)
        if (albaMatch != null) {
            val decoded = runCatching {
                String(Base64.decode(albaMatch.groupValues[1], Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.contains(".m3u8")) return@runCatching decoded
        }

        // Pattern 3: source = "url" (direct m3u8 or PHP wrapper)
        val sourceMatch = Regex("""source\s*[:=]\s*["']([^"']+)["']""").find(text)
        val rawSource = sourceMatch?.groupValues?.get(1)?.trim()
        if (!rawSource.isNullOrBlank()) {
            if (rawSource.endsWith(".m3u8", ignoreCase = true)) return@runCatching rawSource
            val resolved = resolvePhpWrapperToM3u8(rawSource, referer = url)
            if (resolved != null) return@runCatching resolved
        }

        // Pattern 4: raw .m3u8 URL anywhere in the text
        val rawM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(text)
        if (rawM3u8 != null) return@runCatching rawM3u8.groupValues[1]

        null
    }.getOrNull()

    private fun urlSafeBase64Decode(str: String): String? = runCatching {
        var base64 = str.replace("-", "+").replace("_", "/")
        val padding = base64.length % 4
        if (padding != 0) base64 += "=".repeat(4 - padding)
        String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private suspend fun resolvePhpWrapperToM3u8(url: String, referer: String): String? = runCatching {
        val body = app.get(
            url,
            headers = mapOf(
                "User-Agent" to (commonHeaders["User-Agent"] ?: ""),
                "Referer" to referer
            ),
            timeout = 6000L
        ).text

        if (!body.trimStart().startsWith("#EXTM3U")) return@runCatching null

        // Master playlist: parse variants and pick the highest bandwidth.
        var bestUrl: String? = null
        var bestBw = -1L
        body.lineSequence().forEachIndexed { idx, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                // Next non-empty, non-comment line is the variant URL.
                for (i in (idx + 1) until body.lineSequence().count()) {
                    val candidate = body.lineSequence().toList().getOrNull(i)?.trim().orEmpty()
                    if (candidate.isBlank() || candidate.startsWith("#")) continue
                    if (candidate.endsWith(".m3u8", ignoreCase = true)) {
                        if (bw >= bestBw) { bestBw = bw; bestUrl = candidate }
                    }
                    break
                }
            }
        }
        bestUrl
    }.getOrNull()

    private fun refererFor(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}/"
    }.getOrDefault("")

    private fun qualityFrom(q: String?): Int = when (q?.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FHD", "1080P", "1080" -> Qualities.P1080.value
        "HD", "720P", "720" -> Qualities.P720.value
        "SD", "480P", "480" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun MatchItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val homeName = home_en ?: home ?: "Home"
        val awayName = away_en ?: away ?: "Away"
        val titleText = "$homeName vs $awayName"

        val posterUrl = home_logo?.let { "$cdnBase/uploads/team/$it" }
            ?: league_logo?.let { "$cdnBase/uploads/league/$it" }

        val payload = LoadData(
            matchId = id,
            title = titleText,
            posterUrl = posterUrl
        )

        return newLiveSearchResponse(
            name = titleText,
            url = payload.toJson()
        ) {
            this.type = TvType.Live
            this.posterUrl = posterUrl
        }
    }

    data class LoadData(
        val matchId: String,
        val title: String,
        val posterUrl: String? = null
    )

    data class MatchesResponse(
        val matches: List<MatchItem>? = null
    )

    data class MatchItem(
        val id: String? = null,
        val status: Int? = null,
        val category: String? = null,
        val home: String? = null,
        val away: String? = null,
        val home_en: String? = null,
        val away_en: String? = null,
        val league: String? = null,
        val league_en: String? = null,
        val home_logo: String? = null,
        val away_logo: String? = null,
        val league_logo: String? = null,
        val time: String? = null,
        val date: String? = null,
        val score: String? = null
    )

    data class MatchDetail(
        val id: String? = null,
        val status: Int? = null,
        val home_en: String? = null,
        val away_en: String? = null,
        val league_en: String? = null,
        val league: String? = null,
        val date: String? = null,
        val time: String? = null,
        val score: String? = null,
        val desc: String? = null,
        val active: String? = null,
        val has_channels: Int? = null,
        val edges: List<String>? = null,
        val edge_domain: String? = null,
        val channels: List<Channel>? = null
    )

    data class Channel(
        val id: String? = null,
        val server_name: String? = null,
        val server_name_en: String? = null,
        val link: String? = null,
        val mobile_link: String? = null,
        val type: String? = null,
        val ch: String? = null,
        val key: String? = null,
        val edge: String? = null,
        val quality: String? = null,
        val language: String? = null
    )
}
