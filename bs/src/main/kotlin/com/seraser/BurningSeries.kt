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

    // Wichtig: Browser-Header mitschicken, damit die Seite Anfragen nicht blockiert
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // 1. Startseite / Übersicht laden
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document
        val seriesList = doc.select("a[href*='/serie/']").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrl(element.attr("href"))
            if (title.isNotEmpty() && href.contains("/serie/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            } else null
        }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList("Beliebte & Neue Serien", seriesList)),
            hasNext = false
        )
    }

    // 2. Suche umsetzen
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/andere-serien", headers = headers).document
        return doc.select("a[href*='/serie/']").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrl(element.attr("href"))
            if (title.contains(query, ignoreCase = true) && href.contains("/serie/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            } else null
        }.distinctBy { it.url }
    }

    // 3. Serien-Details, Staffeln und Episoden auslesen
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.select("h1, h2 span, .series-title").firstOrNull()?.text()?.trim() ?: "Unbekannte Serie"
        val poster = fixUrlNull(doc.select(".poster img, img[alt*='Poster'], .series-poster img").attr("src"))
        val description = doc.select(".description, .series-description, p").firstOrNull()?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        doc.select(".seasons a, ul.nav-tabs a, a[href*='/season-']").forEach { seasonElem ->
            val seasonUrl = fixUrl(seasonElem.attr("href"))
            val seasonNum = seasonElem.text().filter { it.isDigit() }.toIntOrNull() ?: 1
            
            try {
                val seasonDoc = app.get(seasonUrl, headers = headers).document
                seasonDoc.select("table.episodes tr, .episode-row, tr").forEach { row ->
                    val epLink = row.select("a").firstOrNull() ?: return@forEach
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
                // Ignorieren, falls eine Staffel fehlschlägt
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
        val doc = app.get(data, headers = headers).document

        doc.select(".hoster-list a, ul.hoster-tabs li a, a.watch").forEach { hosterLink ->
            val redirectUrl = fixUrl(hosterLink.attr("href"))
            try {
                val targetDoc = app.get(redirectUrl, headers = headers).document
                val finalUrl = targetDoc.select("a.watch-episode, iframe, source").attr("href").ifEmpty {
                    targetDoc.location()
                }

                if (finalUrl.isNotEmpty()) {
                    loadExtractor(finalUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Hoster überspringen, wenn nicht ladbar
            }
        }
        return true
    }
}
