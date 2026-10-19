package com.seraser

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class BurningSeries : MainAPI() {
    override var name = "BurningSeries"

    // bs.to hat laut News der Seite aktuell Domain-Probleme.
    // Aktive Spiegel: burningseries.ac, burningseries.cx, bs.cine.to
    override var mainUrl = "https://burningseries.ac"
    override var lang = "de"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val cloudflareInterceptor by lazy { CloudflareKiller() }
    private val tag = "BS"

    override val mainPage = mainPageOf(
        "" to "Neueste Serien",
        "serie-alphabet" to "Alle Serien (A-Z)"
    )

    // ---------------------------------------------------------------
    // Hilfsfunktionen
    // ---------------------------------------------------------------

    // Serie:   /serie/Die-Simpsons-The-Simpsons
    private val seriesRegex = Regex("""/serie/([^/?#]+)/?$""")

    // Episode: /serie/<slug>/<staffel>/<nr>-<titel>/<sprache>
    private val episodeRegex = Regex("""/serie/([^/]+)/(\d+)/(\d+)-([^/]+)/([^/]+)/?$""")

    // Hoster:  /serie/<slug>/<staffel>/<nr>-<titel>/<sprache>/<hoster>
    private val hosterRegex = Regex("""/serie/([^/]+)/(\d+)/(\d+)-([^/]+)/([^/]+)/([^/]+)/?$""")

    private suspend fun getDoc(url: String): Document =
        app.get(url, interceptor = cloudflareInterceptor).document

    /** Macht aus relativen/absoluten/falschen Domains immer eine Pfad-URL auf mainUrl. */
    private fun normalize(href: String): String {
        val path = href.trim()
            .replace(Regex("""^https?://[^/]+"""), "")
            .removePrefix("//")
        return fixUrl(if (path.startsWith("/")) path else "/$path")
    }

    private fun parseSeries(document: Document, scope: String? = null): List<SearchResponse> {
        val links = if (scope != null) document.select(scope) else document.select("a[href*='/serie/']")
        return links.mapNotNull { el ->
            val href = el.attr("href")
            val path = href.replace(Regex("""^https?://[^/]+"""), "")
            if (!seriesRegex.containsMatchIn(path)) return@mapNotNull null

            val title = el.text().trim().ifEmpty { el.attr("title").trim() }
            if (title.isEmpty()) return@mapNotNull null

            newTvSeriesSearchResponse(title, normalize(href), TvType.TvSeries)
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // Hauptseite
    // ---------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc("$mainUrl/${request.data}")
        Log.d(tag, "MainPage '${request.name}': title='${document.title()}', links=${document.select("a").size}")

        var home: List<SearchResponse> = emptyList()

        if (request.data.isEmpty()) {
            // Startseite: Abschnitt "Neueste Serien" gezielt ansprechen
            home = parseSeries(document, "h3:containsOwn(Neueste Serien) ~ ul a")
            if (home.isEmpty()) {
                home = parseSeries(document, "h3:containsOwn(Neueste Serien) + ul a")
            }
            if (home.isEmpty()) {
                // Fallback: alle Serienlinks (enthält dann auch die Favoriten aus dem Menü)
                home = parseSeries(document)
            }
        } else {
            home = parseSeries(document)
        }

        Log.d(tag, "MainPage '${request.name}': ${home.size} Serien")
        return newHomePageResponse(request.name, home)
    }

    // ---------------------------------------------------------------
    // Suche (filtert die Gesamtliste)
    // ---------------------------------------------------------------

    private var allSeriesCache: List<SearchResponse>? = null

    override suspend fun search(query: String): List<SearchResponse> {
        val all = allSeriesCache ?: parseSeries(getDoc("$mainUrl/serie-alphabet")).also {
            if (it.isNotEmpty()) allSeriesCache = it
        }
        Log.d(tag, "search('$query'): Liste hat ${all.size} Serien")
        return all.filter { it.name.contains(query, ignoreCase = true) }
    }

    // ---------------------------------------------------------------
    // Serie laden
    // ---------------------------------------------------------------

    private fun parseEpisodes(document: Document, slug: String, fallbackSeason: Int?): List<Episode> {
        return document.select("a[href*='/serie/$slug/']").mapNotNull { a ->
            val href = a.attr("href")
            val m = episodeRegex.find(href.replace(Regex("""^https?://[^/]+"""), ""))
                ?: return@mapNotNull null

            val season = m.groupValues[2].toIntOrNull() ?: fallbackSeason
            val epNum = m.groupValues[3].toIntOrNull()
            val epTitle = a.attr("title").trim().ifEmpty { a.text().trim() }
                .ifEmpty { m.groupValues[4].replace("-", " ") }

            newEpisode(normalize(href)) {
                this.name = epTitle
                this.season = season
                this.episode = epNum
            }
        }.distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val slug = Regex("""/serie/([^/?#]+)""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Ungültige Serien-URL: $url")

        val title = (document.selectFirst("h2")?.text() ?: document.title())
            .replace(Regex("""\s+Staffel\s+\d+.*$"""), "")
            .replace(Regex("""\s*-\s*Burning Series.*$"""), "")
            .trim()
            .ifEmpty { slug.replace("-", " ") }

        val poster = fixUrlNull(
            document.selectFirst("img[src*='/public/images/cover/']")?.attr("src")
        )

        val description = document.select("p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 60 }

        // Staffel-Links: /serie/<slug>/<nr>/<sprache>   (0 = Specials)
        val seasonRegex = Regex("""/serie/\Q$slug\E/(\d+)/([^/]+)/?$""")
        val seasonLinks = document.select("a[href*='/serie/$slug/']").mapNotNull { a ->
            val path = a.attr("href").replace(Regex("""^https?://[^/]+"""), "")
            val m = seasonRegex.find(path) ?: return@mapNotNull null
            val num = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            num to normalize(a.attr("href"))
        }.distinctBy { it.first }.sortedBy { it.first }

        Log.d(tag, "load: slug=$slug, Staffeln=${seasonLinks.size}")

        val episodes = mutableListOf<Episode>()

        // Die Serienseite selbst zeigt bereits eine Staffel
        episodes += parseEpisodes(document, slug, null)

        // Restliche Staffeln nachladen
        seasonLinks.forEach { (num, seasonUrl) ->
            try {
                val seasonDoc = getDoc(seasonUrl)
                episodes += parseEpisodes(seasonDoc, slug, num)
            } catch (e: Exception) {
                Log.e(tag, "Staffel $num fehlgeschlagen: ${e.message}")
            }
        }

        val unique = episodes.distinctBy { it.data }
        Log.d(tag, "load: ${unique.size} Episoden")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique) {
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

        // Hoster-Links der Episode, z. B. .../1/1-Titel/de/VOE
        val hosterUrls = document.select("a[href*='/serie/']").mapNotNull { a ->
            val path = a.attr("href").replace(Regex("""^https?://[^/]+"""), "")
            if (hosterRegex.containsMatchIn(path)) normalize(a.attr("href")) else null
        }.distinct()

        Log.d(tag, "loadLinks: ${hosterUrls.size} Hoster für $data")

        var found = false

        hosterUrls.forEach { hosterUrl ->
            try {
                val hosterDoc = getDoc(hosterUrl)

                // "Hier klicken, um auf VOE zuschauen" -> Link/Redirect zum echten Hoster
                val target = hosterDoc.selectFirst(
                    "a:containsOwn(Hier klicken), a.watchEpisode, a.watch-episode, " +
                        ".hoster-player a, a[href*='/out/'], iframe[src]"
                )?.let { el ->
                    el.attr("href").ifEmpty { el.attr("src") }
                }?.takeIf { it.isNotEmpty() }

                Log.d(tag, "Hoster $hosterUrl -> target=$target")

                if (target != null) {
                    // Redirect auflösen (burningseries.ac/out/... -> voe.sx/...)
                    val resolved = if (target.startsWith("http") && !target.contains("burningseries")) {
                        target
                    } else {
                        app.get(fixUrl(target), referer = hosterUrl, interceptor = cloudflareInterceptor).url
                    }

                    if (loadExtractor(resolved, hosterUrl, subtitleCallback, callback)) {
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
