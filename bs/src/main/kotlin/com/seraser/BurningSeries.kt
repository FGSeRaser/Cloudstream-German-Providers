
package com.seraser

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document

class BurningSeries : MainAPI() {

    override var name = "BurningSeries"
    override var mainUrl = "https://example.com"
    override var lang = "de"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Testseite"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val results = try {
            val document = app.get(mainUrl).document
            parseLinks(document)
        } catch (e: Exception) {
            logError("Startseite konnte nicht geladen werden: ${e.message}")
            emptyList()
        }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        return try {
            val document = app.get(mainUrl).document

            parseLinks(document).filter {
                it.name.contains(query, ignoreCase = true)
            }
        } catch (e: Exception) {
            logError("Suche fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    private fun parseLinks(
        document: Document
    ): List<SearchResponse> {
        return document.select("a[href]")
            .mapNotNull { element ->
                val title = element.text().trim()
                val url = element.absUrl("href")

                if (title.isBlank() || url.isBlank()) {
                    null
                } else {
                    newTvSeriesSearchResponse(
                        title,
                        url,
                        TvType.TvSeries
                    )
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank { document.title() }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            emptyList()
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logError(
            "Diagnosemodus: Keine Wiedergabelinks geladen."
        )
        return false
    }
}
