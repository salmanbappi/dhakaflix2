package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.regex.Pattern

class DhakaFlix2 : AnimeHttpSource() {

    override val name = "DhakaFlix 2"

    override val baseUrl = "http://172.16.50.9"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419843L

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "*/*")
        .add("Referer", "$baseUrl/")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotEmpty()) {
            return withContext(Dispatchers.IO) {
                val results = mutableListOf<SAnime>()
                val servers = listOf(
                    "http://172.16.50.14" to "DHAKA-FLIX-14",
                    "http://172.16.50.12" to "DHAKA-FLIX-12",
                    "http://172.16.50.9" to "DHAKA-FLIX-9",
                    "http://172.16.50.7" to "DHAKA-FLIX-7"
                )

                for ((url, serverName) in servers) {
                    try {
                        searchOnServer(url, serverName, query, results)
                    } catch (e: Exception) {
                        // Continue to next server on failure
                    }
                }

                AnimesPage(sortByTitle(results, query), false)
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchOnServer(serverUrl: String, serverName: String, query: String, results: MutableList<SAnime>) {
        val searchUrl = "$serverUrl/$serverName/"
        val jsonPayload = "{\"action\":\"get\",\"search\":{\"href\":\"/$serverName/\",\"pattern\":\"$query\",\"ignorecase\":true}}"
        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = POST(searchUrl, headers, body)
        client.newCall(request).execute().use {
 response ->
            val bodyString = response.body?.string() ?: return
            val hostUrl = serverUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return
            
            val pattern = Pattern.compile("\"href\":\"([^\"]+)\"[^}]*\"size\":null", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(bodyString)
            
            while (matcher.find()) {
                var href = matcher.group(1).replace("\\", "/").replace(Regex("/+"), "/")
                while (href.endsWith("/") && href.length > 1) {
                    href = href.substring(0, href.length - 1)
                }
                
                val rawTitle = href.substringAfterLast("/")
                val title = try {
                    URLDecoder.decode(rawTitle, "UTF-8").trim()
                } catch (e: Exception) {
                    rawTitle.trim()
                }
                if (title.isEmpty()) continue
                
                val anime = SAnime.create().apply {
                    this.title = title
                    val finalUrl = if (href.endsWith("/")) href else "$href/"
                    this.url = "$hostUrl$finalUrl"
                    
                    val thumbSuffix = if (serverName.contains("9")) "a11.jpg" else "a_AL_.jpg"
                    this.thumbnail_url = "${this.url}$thumbSuffix".replace(" ", "%20")
                }
                synchronized(results) {
                    results.add(anime)
                }
            }
        }
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> {
        return list.sortedByDescending { diceCoefficient(it.title, query) }
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val str1 = s1.lowercase()
        val str2 = s2.lowercase()
        if (str1.length < 2 || str2.length < 2) return 0.0
        
        val pairs1 = (0 until str1.length - 1).map { str1.substring(it, it + 2) }.toSet()
        val pairs2 = (0 until str2.length - 1).map { str2.substring(it, it + 2) }
        
        var intersection = 0
        for (pair in pairs2) {
            if (pair in pairs1) intersection++
        }
        
        return 2.0 * intersection / (str1.length + str2.length - 2)
    }

    override fun popularAnimeRequest(page: Int): Request {
        return GET("http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%282025%29/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        val cards = document.select("div.card")
        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                val link = card.select("h5 a")
                val title = link.text()
                val url = link.attr("abs:href")
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    animeList.add(SAnime.create().apply {
                        this.title = title
                        this.url = url
                        val img = card.select("img[src~=(?i)a11|a_al|poster|banner|thumb], img:not([src~=(?i)back|folder|parent|icon|/icons/])")
                        this.thumbnail_url = (img.attr("abs:data-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("abs:data-lazy-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("abs:src")).replace(" ", "%20")
                    })
                }
            }
        } else {
            document.select("a").forEach { element ->
                val title = element.text()
                val url = element.attr("abs:href")
                if (isValidDirectoryItem(title, url)) {
                    animeList.add(SAnime.create().apply {
                        this.title = if (title.endsWith("/")) title.dropLast(1) else title
                        this.url = url
                        val finalUrl = if (url.endsWith("/")) url else "$url/"
                        this.thumbnail_url = "${finalUrl}a_AL_.jpg".replace(" ", "%20")
                    })
                }
            }
        }
        return AnimesPage(animeList, false)
    }

    private fun isValidDirectoryItem(title: String, url: String): Boolean {
        val excluded = listOf("modern browsers", "powered by", "javascript", "parent directory", "name", "last modified", "size", "description", "index of")
        val lowerTitle = title.lowercase()
        if (excluded.any { lowerTitle.contains(it) }) return false
        if (url.contains("../") || url.contains("?")) return false
        return true
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET(Filters.getUrl(query, filters), headers)
    }
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val mediaType = getMediaType(document)
        
        return if (mediaType != null) {
            if (mediaType == "m") getMovieDetails(document) else getSeriesDetails(document)
        } else {
            SAnime.create().apply {
                status = SAnime.COMPLETED
                val img = document.select("img[src~=(?i)a11|a_al|poster|banner|thumb], img:not([src~=(?i)back|folder|parent|icon|/icons/])")
                var thumb = img.attr("abs:src")
                if (thumb.isEmpty()) {
                    thumb = document.select("a[href~=(?i)\.(jpg|jpeg|png|webp)]:not([href~=(?i)back|folder|parent|icon])").attr("abs:href")
                }
                thumbnail_url = thumb.replace(" ", "%20")
            }
        }
    }

    private fun getMediaType(document: Document): String? {
        val html = document.select("script").html()
        return when {
            html.contains("/m/lazyload/") -> "m"
            html.contains("/s/lazyload/") -> "s"
            else -> null
        }
    }

    private fun getMovieDetails(document: Document) = SAnime.create().apply {
        status = SAnime.COMPLETED
        thumbnail_url = document.select("figure.movie-detail-banner img, .movie-detail-banner img, .col-md-3 img, .poster img")
            .attr("abs:src").replace(" ", "%20")
        genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
        description = document.select("p.storyline").text().trim()
    }

    private fun getSeriesDetails(document: Document) = SAnime.create().apply {
        status = SAnime.ONGOING
        thumbnail_url = document.select("figure.movie-detail-banner img, .movie-detail-banner img, .col-md-3 img, .poster img")
            .attr("abs:src").replace(" ", "%20")
        genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
        description = document.select("p.storyline").text().trim()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return withContext(Dispatchers.IO) {
            val response = client.newCall(GET(anime.url, headers)).execute()
            val document = response.asJsoup()
            val mediaType = getMediaType(document)

            val episodes = when (mediaType) {
                "s" -> {
                    val extracted = extractEpisodes(document)
                    if (extracted.isNotEmpty()) sortEpisodes(extracted) else parseDirectoryParallel(document)
                }
                "m" -> getMovieMedia(document)
                else -> parseDirectoryParallel(document)
            }

            if (episodes.isEmpty()) throw Exception("No results found")
            episodes
        }
    }

    private fun extractEpisodes(document: Document): List<EpisodeData> {
        return document.select("div.card, div.episode-item, div.download-link").mapNotNull { element ->
            val titleElement = element.select("h5").firstOrNull() ?: return@mapNotNull null
            val rawTitle = titleElement.ownText().trim()
            val name = rawTitle.split("&nbsp;").first().trim()
            val url = element.select("h5 a").attr("abs:href").trim()
            val qualityText = element.select("h5 .badge-fill").text() ?: ""
            val quality = sizeRegex.replace(qualityText, "$1").trim()
            val epName = element.select("h4").firstOrNull()?.ownText()?.trim() ?: ""
            val size = element.select("h4 .badge-outline").firstOrNull()?.text()?.trim() ?: ""
            
            if (name.isNotEmpty() && url.isNotEmpty()) {
                EpisodeData(name, url, quality, epName, size)
            } else null
        }
    }

    private fun getMovieMedia(document: Document): List<SEpisode> {
        val linkElement = document.select("div.col-md-12 a.btn, .movie-buttons a, a[href*=/m/lazyload/], a[href*=/s/lazyload/], .download-link a").lastOrNull()
        val url = linkElement?.attr("abs:href")?.let { it.replace(" ", "%20") } ?: ""
        val quality = document.select(".badge-wrapper .badge-fill").lastOrNull()?.text()?.replace("|", "")?.trim() ?: ""
        
        return listOf(SEpisode.create().apply {
            this.url = url
            this.name = "Movie"
            this.episode_number = 1f
            this.scanlator = quality
        })
    }

    private val semaphore = Semaphore(5)

    private suspend fun parseDirectoryParallel(document: Document): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val visited = mutableSetOf<String>()
        parseDirectoryRecursive(document, 3, episodes, visited)
        return episodes
    }

    private suspend fun parseDirectoryRecursive(document: Document, depth: Int, episodes: MutableList<SEpisode>, visited: MutableSet<String>) {
        val currentUrl = document.location()
        visited.add(currentUrl)

        val links = document.select("a[href]")
        val (files, dirs) = links.map { it to it.attr("abs:href") }
            .filter { (_, absHref) -> absHref.isNotEmpty() && absHref !in visited }
            .partition { (element, _) -> isVideoFile(element.attr("href")) }

        files.forEach { (element, absHref) ->
            episodes.add(SEpisode.create().apply {
                this.url = absHref
                this.name = element.text()
                this.episode_number = -1f
            })
            visited.add(absHref)
        }

        if (depth > 0 && files.isEmpty()) {
            coroutineScope {
                dirs.filter { (element, _) -> 
                    val href = element.attr("href")
                    href != "../" && !href.startsWith("?") && href.endsWith("/") && !href.contains("_h5ai")
                }.forEach { (_, absHref) ->
                    semaphore.withPermit {
                        try {
                            val resp = client.newCall(GET(absHref, headers)).execute()
                            val doc = resp.asJsoup()
                            parseDirectoryRecursive(doc, depth - 1, episodes, visited)
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    private fun isVideoFile(href: String): Boolean {
        val h = href.lowercase()
        return listOf(".mkv", ".mp4", ".avi", ".ts", ".m4v", ".webm", ".mov").any { h.contains(it) }
    }

    private fun sortEpisodes(list: List<EpisodeData>): List<SEpisode> {
        var episodeCount = 0f
        return list.map { data ->
            SEpisode.create().apply {
                url = data.videoUrl
                name = "${data.seasonEpisode} - ${data.episodeName}"
                episode_number = ++episodeCount
                scanlator = "${data.quality}  ${data.size}"
            }
        }.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, "Video", episode.url))
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")
    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    data class EpisodeData(
        val seasonEpisode: String,
        val videoUrl: String,
        val quality: String,
        val episodeName: String,
        val size: String
    )

    companion object {
        private val sizeRegex = Regex("""(\d+\.\d+ [GM]B|\d+ [GM]B).*""")
    }
}
