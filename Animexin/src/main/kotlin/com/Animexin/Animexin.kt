package com.Animexin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home     = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("div.bsx > a").attr("title")
        val href      = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title       = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href=document.selectFirst(".eplister li > a")?.attr("href") ?:""
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type=document.selectFirst(".spe")?.text().toString()
        val tvtag=if (type.contains("Movie")) TvType.Movie else TvType.TvSeries
        return if (tvtag == TvType.TvSeries) {
            val Eppage= document.selectFirst(".eplister li > a")?.attr("href") ?:""
            val doc= app.get(Eppage).document
            val episodes=doc.select("div.episodelist > ul > li").map { info->
                        val href1 = info.select("a").attr("href")
                        val episode = info.select("a span").text().substringAfter("-").substringBeforeLast("-")
                        val posterr=info.selectFirst("a img")?.attr("src") ?:""
                        newEpisode(href1)
                        {
                            this.name=episode
                            this.posterUrl=posterr
                        }
            }
            if (poster.isEmpty())
            {
                poster=document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty())
            {
                poster=document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        val sources = document.select(".mobius option")
            .mapIndexedNotNull { index, option -> option.toResolvedSource(index) }
            .sortedWith(preferredSourceOrder())

        sources.forEach { source ->
            loadExtractorCustom(
                source = source,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        return sources.isNotEmpty()
    }

    private fun Element.toResolvedSource(order: Int): ResolvedSource? {
        val base64 = attr("value")
        if (base64.isNullOrBlank()) return null

        val decodedHtml = runCatching { base64Decode(base64) }.getOrNull() ?: return null
        val iframe = Jsoup.parse(decodedHtml).selectFirst("iframe") ?: return null

        val rawUrl = iframe.attr("src")
        if (rawUrl.isNullOrBlank()) return null

        val url = Http(rawUrl)
        val label = text()?.trim().orEmpty()

        val quality = QUALITY_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val language = when {
            label.contains("English", ignoreCase = true) -> Language.ENGLISH
            label.contains("Indo", ignoreCase = true) || label.contains("Indonesia", ignoreCase = true) -> Language.INDONESIAN
            else -> Language.UNKNOWN
        }

        return ResolvedSource(
            url = url,
            label = label.ifBlank { url },
            host = iframe.attr("data-host").ifBlank { url.toHttpUrlOrNull()?.host.orEmpty() },
            language = language,
            quality = quality,
            order = order
        )
    }

    private suspend fun loadExtractorCustom(
        source: ResolvedSource,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (source.isDailymotion()) {
            loadDailyMotionEnhanced(source, subtitleCallback, callback)
            return
        }

        loadExtractor(source.url, subtitleCallback) { link ->
            callback(link.decorate(source))
        }
    }

    private fun ExtractorLink.decorate(source: ResolvedSource): ExtractorLink {
        val cleanedHost = source.host.takeUnless { it.isBlank() }
        val cleanedExtractor = name.takeUnless { generic ->
            GENERIC_NAMES.any { it.equals(generic, ignoreCase = true) }
        }

        val decoratedName = buildList {
            addIfNotBlank(source.label)
            addIfNotBlank(cleanedExtractor?.takeUnless {
                it.equals(source.label, ignoreCase = true) || cleanedHost?.equals(it, ignoreCase = true) == true
            })
            addIfNotBlank(cleanedHost?.takeUnless { it.equals(source.label, ignoreCase = true) })
            addIfNotBlank(source.language.displayName.takeUnless {
                it.isBlank() || source.label.contains(it, ignoreCase = true)
            })
        }.joinToString(" · ").ifBlank { name }

        val resolvedQuality = quality.takeUnless { it == Qualities.Unknown.value }
            ?: source.quality
            ?: quality

        return this.copy(
            name = decoratedName,
            quality = resolvedQuality,
        )
    }

    private suspend fun loadDailyMotionEnhanced(
        source: ResolvedSource,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = source.url.substringAfterLast("/video/")
            .substringBefore("?")
            .substringBefore(' ') // safety for malformed ids
        if (videoId.isBlank()) {
            loadExtractor(source.url, subtitleCallback) { link ->
                callback(link.decorate(source))
            }
            return
        }

        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val response = runCatching {
            app.get(metadataUrl, referer = source.url).parsedSafe<DailymotionMetadata>()
        }.getOrNull()

        if (response?.qualities?.isNotEmpty() == true) {
            response.qualities.entries
                .flatMap { (qualityKey, streams) ->
                    val keyHeight = qualityKey.toIntOrNull()
                    streams.map { stream -> stream to (stream.height ?: keyHeight) }
                }
                .sortedByDescending { it.second ?: 0 }
                .forEach { (stream, height) ->
                    generateM3u8(
                        name = source.label,
                        url = stream.url,
                        referer = source.url
                    ).forEach { link ->
                        val decoratedSource = source.copy(quality = height)
                        callback(link.decorate(decoratedSource))
                    }
                }

            response.subtitles.orEmpty().forEach { subtitle ->
                subtitleCallback(SubtitleFile(subtitle.label, subtitle.url))
            }
            return
        }

        loadExtractor(source.url, subtitleCallback) { link ->
            callback(link.decorate(source))
        }
    }

    private fun preferredSourceOrder() = compareByDescending<ResolvedSource> { source ->
        when (source.language) {
            Language.ENGLISH -> 2
            Language.UNKNOWN -> 1
            Language.INDONESIAN -> 0
        }
    }.thenByDescending { it.quality ?: 0 }
        .thenBy { it.order }

    private fun MutableList<String>.addIfNotBlank(value: String?) {
        if (!value.isNullOrBlank()) add(value)
    }

    private fun ResolvedSource.isDailymotion(): Boolean {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        return host.contains("dailymotion", ignoreCase = true) || host.contains("geo.dmcdn", ignoreCase = true)
    }

    private data class ResolvedSource(
        val url: String,
        val label: String,
        val host: String,
        val language: Language,
        val quality: Int?,
        val order: Int
    )

    private data class DailymotionMetadata(
        val qualities: Map<String, List<DailymotionStream>> = emptyMap(),
        val subtitles: List<DailymotionSubtitle>? = emptyList()
    )

    private data class DailymotionStream(
        val type: String?,
        val url: String,
        val height: Int?
    )

    private data class DailymotionSubtitle(
        val language: String?,
        val label: String,
        val url: String
    )

    private enum class Language(val displayName: String) {
        ENGLISH("English"),
        INDONESIAN("Indonesian"),
        UNKNOWN("")
    }

    companion object {
        private val QUALITY_REGEX = Regex("(\\d{3,4})", RegexOption.IGNORE_CASE)
        private val GENERIC_NAMES = setOf("Dailymotion", "StreamSB", "StreamWish", "FileMoon", "Main")
    }
}
