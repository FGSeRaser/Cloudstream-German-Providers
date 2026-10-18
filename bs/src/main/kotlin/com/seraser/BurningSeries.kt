package com.seraser

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*

class BurningSeries : MainAPI() {
    override var name = "BurningSeries"
    override var mainUrl = "https://burningseries.ac"
    override var lang = "de"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // Wichtig: Cloudflare-Bypass wie bei Kinoger aktivieren
    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "serie-alphabet" to "Alle Serien"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}", interceptor = cloudflareInterceptor).document
        val home = document.select("a[href*='/serie/']").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrl(element.attr("href"))
            if (title.isNotEmpty() && href.contains("/serie/") && !title.contains("Login", true) && !title.contains("Register", true)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            } else null
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/serie-alphabet", interceptor = cloudflareInterceptor).document
        return document.select("a[href*='/serie/']").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrl(element.attr("href"))
            if (title.contains(query, ignoreCase = true) && href.contains("/serie/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            } else null
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cloudflareInterceptor).document
        val title = document.select("h1, h2 span, .series-title").firstOrNull()?.text()?.trim() ?: "Unbekannte Serie"
        val poster = fixUrlNull(document.select(".poster img, img[alt*='Poster'], #sp_right img").attr("src"))
        val description = document.select("#sp_left p, .description, .series-description").firstOrNull()?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        
        document.select("ul.nav.nav-tabs li a, .seasons a, a[href*='/season-']").forEach { seasonElem ->
            val seasonUrl = fixUrl(seasonElem.attr("href"))
            val seasonNum = seasonElem.text().filter { it.isDigit() }.toIntOrNull() ?: 1

            try {
                val seasonDoc = app.get(seasonUrl, interceptor = cloudflareInterceptor).document
                seasonDoc.select("table.episodes tr, .episode-row, tr").forEach { row ->
                    val epLink = row.select("td a, a").firstOrNull() ?: return@forEach
                    val epHref = fixUrl(epLink.attr("href"))
                    val epTitle = epLink.text().trim()
                    val epNum = row.select("td").firstOrNull()?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0

                    if (epHref.contains("/")) {
                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // Fehler bei einzelnen Staffeln ignorieren
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = cloudflareInterceptor).document

        document.select("ul.hoster-tabs li a, .hoster-list a, a.watch").forEach { hoster ->
            val redirectUrl = fixUrl(hoster.attr("href"))
            try {
                val targetDoc = app.get(redirectUrl, interceptor = cloudflareInterceptor).document
                val finalUrl = targetDoc.select("a.watch-episode, iframe, source").attr("href").ifEmpty {
                    targetDoc.location()
                }
                if (finalUrl.isNotEmpty()) {
                    loadExtractor(finalUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Hoster überspringen
            }
        }
        return true
    }
}
