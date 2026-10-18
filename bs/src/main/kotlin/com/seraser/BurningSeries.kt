package com.seraser

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class BurningSeries : MainAPI() {
    override var mainUrl = "https://burningseries.ac"
    override var name = "BurningSeries"
    override val supportedTypes = setOf(TvType.TvSeries)

    override var lang = "de"
    override val hasMainPage = true

    // 1. Startseite / Serien-Übersicht laden
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/serie-alphabet").document
        val seriesList = doc.select("#series ul li a").mapNotNull {
            val title = it.text()
            val href = fixUrl(it.attr("href"))
            newTvSeriesSearchResponse(title, href, TvType.TvSeries)
        }
        return newHomePageResponse(
            listOf(HomePageList("Alle Serien (A-Z)", seriesList)),
            hasNext = false
        )
    }

    // 2. Suche umsetzen
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/serie-alphabet").document
        return doc.select("#series ul li a").filter {
            it.text().contains(query, ignoreCase = true)
        }.map {
            newTvSeriesSearchResponse(it.text(), fixUrl(it.attr("href")), TvType.TvSeries)
        }
    }

    // 3. Serien-Details, Staffeln und Episoden auslesen
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h2 span").text().ifEmpty { doc.select("#sp_left h2").text() }
        val poster = fixUrlNull(doc.select("#sp_right img").attr("src"))
        val description = doc.select("#sp_left p").text()

        val episodes = mutableListOf<Episode>()
        val seasonElements = doc.select("#sp_left ul.nav.nav-tabs li a")

        for (seasonElem in seasonElements) {
            val seasonUrl = fixUrl(seasonElem.attr("href"))
            val seasonNum = seasonElem.text().toIntOrNull() ?: continue
            val seasonDoc = app.get(seasonUrl).document

            seasonDoc.select("#sp_left table.episodes tr").forEach { row ->
                val epLink = row.select("td a").firstOrNull() ?: return@forEach
                val epHref = fixUrl(epLink.attr("href"))
                val epTitle = epLink.text()
                val epNum = row.select("td").firstOrNull()?.text()?.toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // 4. Streams / Hoster-Links extrahieren
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("ul.hoster-tabs li a").forEach { hosterLink ->
            val redirectUrl = fixUrl(hosterLink.attr("href"))
            val targetDoc = app.get(redirectUrl).document
            val finalUrl = targetDoc.select("a.watch-episode").attr("href")

            if (finalUrl.isNotEmpty()) {
                loadExtractor(finalUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
