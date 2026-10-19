package com.seraser

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*

class BurningSeries : MainAPI() {
    override var name = "BurningSeries"
    override var mainUrl = "https://burningseries.ac"
    override var lang = "de"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // Cloudflare-Bypass
    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    private val tag = "BS"

    override val mainPage = mainPageOf(
        "serie-alphabet" to "Alle Serien"
    )

    // ---------------------------------------------------------------
    // Hilfsfunktionen
    // ---------------------------------------------------------------

    private suspend fun getDoc(url: String) =
        app.get(url, interceptor = cloudflareInterceptor).document

    /** Liest alle Serien-Links (serie/Name) aus einer Seite. */
    private fun parseSeriesList(document: org.jsoup.nodes.Document): List<SearchResponse> {
        // Nur Links der Form serie/Name (ohne Staffel/Episode)
        val seriesRegex = Regex("""^/?serie/[^/]+/?$""")
        return document.select("a[href*='serie/']").mapNotNull { el ->
            val rawHref = el.attr("href").trim()
            val path = rawHref.removePrefix(mainUrl)
            if (!seriesRegex.matches(path)) return@mapNotNull null

            val title = el.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            newTvSeriesSearchResponse(title, fixUrl(rawHref), TvType.TvSeries)
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // Hauptseite
    // ---------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc("$mainUrl/${request.data}")
        Log.d(tag, "MainPage: title='${document.title()}', links=${document.select("a").size}")

        val home = parseSeriesList(document)
        Log.d(tag, "MainPage: ${home.size} Serien gefunden")

        return newHomePageResponse(request.name, home)
    }

    // ---------------------------------------------------------------
    // Suche
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/serie-alphabet")
        return parseSeriesList(document).filter {
            it.name.contains(query, ignoreCase = true)
        }
    }

    // ---------------------------------------------------------------
    // Serie laden (Staffeln + Episoden)
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val title = document.selectFirst("#sp_left h2")?.ownText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1, h2")?.text()?.trim()
            ?: "Unbekannte Serie"

        val poster = fixUrlNull(
            document.selectFirst("#sp_right img, .poster img")?.attr("src")
        )

        val description = document.selectFirst("#sp_left p, .description")
            ?.text()?.trim()

        // Serien-Slug aus der URL, z. B. "Breaking-Bad"
        val slug = url.substringAfter("/serie/").substringBefore("/")

        // Staffel-Links: serie/<slug>/<nummer>[/de]
        val seasonRegex = Regex("""serie/\Q$slug\E/(\d+)""")
        val seasonLinks = document.select("#seasons li a, ul.nav.nav-tabs li a, a[href*='serie/$slug/']")
            .mapNotNull { a ->
                val href = a.attr("href")
                val num = seasonRegex.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                // Nur Staffelseiten, keine Episodenlinks
                val rest = href.substringAfter("serie/$slug/").trim('/').split("/")
                if (rest.size > 2) return@mapNotNull null
                num to fixUrl(href)
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        Log.d(tag, "load: slug=$slug, Staffeln=${seasonLinks.size}")

        // Fallback: Falls keine Staffel-Links gefunden wurden, Staffel 1 versuchen
        val seasons = if (seasonLinks.isEmpty()) listOf(1 to "$mainUrl/serie/$slug/1/de") else seasonLinks

        val episodes = mutableListOf<Episode>()

        seasons.forEach { (seasonNum, seasonUrl) ->
            try {
                val seasonDoc = getDoc(seasonUrl)
                seasonDoc.select("table.episodes tr, table tr").forEach { row ->
                    // Episoden-Link: serie/<slug>/<staffel>/<nr>-<titel>
                    val epLink = row.select("td a").firstOrNull { a ->
                        Regex("""serie/\Q$slug\E/$seasonNum/\d+-""").containsMatchIn(a.attr("href"))
                    } ?: return@forEach

                    val epHref = fixUrl(epLink.attr("href"))
                    val epNum = Regex("""/$seasonNum/(\d+)-""")
                        .find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: row.selectFirst("td")?.text()?.filter { it.isDigit() }?.toIntOrNull()

                    val epTitle = row.selectFirst("td strong")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: epLink.text().trim().ifEmpty { "Episode ${epNum ?: ""}" }

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Staffel $seasonNum fehlgeschlagen: ${e.message}")
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }
        Log.d(tag, "load: ${uniqueEpisodes.size} Episoden")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ---------------------------------------------------------------
    // Videolinks
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDoc(data)

        val hosterLinks = document.select("ul.hoster-tabs li a, .hoster-list a, a.watch")
            .map { fixUrl(it.attr("href")) }
            .filter { it.isNotEmpty() }
            .distinct()

        Log.d(tag, "loadLinks: ${hosterLinks.size} Hoster")

        var found = false

        hosterLinks.forEach { hosterUrl ->
            try {
                val targetDoc = getDoc(hosterUrl)

                // Mögliche Ziele: Watch-Button, iframe, source
                val finalUrl = targetDoc.selectFirst("a.watch-episode, .hoster-player a")?.attr("href")
                    ?.takeIf { it.isNotEmpty() }
                    ?: targetDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotEmpty() }
                    ?: targetDoc.selectFirst("source")?.attr("src")?.takeIf { it.isNotEmpty() }
                    ?: targetDoc.location()

                if (finalUrl.isNotEmpty() && finalUrl != hosterUrl) {
                    if (loadExtractor(fixUrl(finalUrl), data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Hoster $hosterUrl fehlgeschlagen: ${e.message}")
            }
        }

        return found
    }
}
