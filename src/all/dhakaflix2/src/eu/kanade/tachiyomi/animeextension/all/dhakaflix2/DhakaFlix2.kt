package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DhakaFlix2 : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "DhakaFlix 2"
    override val baseUrl = "http://172.16.50.9"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419841L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val searchCache = mutableMapOf<String, List<SAnime>>()
    private val cacheTime = mutableMapOf<String, Long>()

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(20, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val original = chain.request()
            val requestUrl = original.url
            val referer = "${requestUrl.scheme}://${requestUrl.host}/"
            val newRequest = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Referer", referer)
                .build()
            chain.proceed(newRequest)
        }
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 60
            maxRequestsPerHost = 20
        })
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_API_KEY
            title = "TMDb API Key"
            summary = "Used for high-quality covers. Get one at themoviedb.org"
            setDefaultValue("")
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_TMDB_COVERS
            title = "Use TMDb Covers"
            summary = "Fetch high-quality covers from TMDb. If disabled, NO images will load for speed."
            setDefaultValue(false)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "clear_tmdb_cache"
            title = "Clear TMDb Cache"
            summary = "Clears all cached TMDb poster URLs"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    val editor = preferences.edit()
                    preferences.all.keys.filter { it.startsWith("tmdb_cover_") }.forEach { editor.remove(it) }
                    editor.apply()
                    android.widget.Toast.makeText(screen.context, "TMDb Cache Cleared", android.widget.Toast.LENGTH_SHORT).show()
                    this.isChecked = false
                }
                false
            }
        }.also { screen.addPreference(it) }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        var u = url.trim()
        val lastHttp = u.lastIndexOf("http://", ignoreCase = true)
        val lastHttps = u.lastIndexOf("https://", ignoreCase = true)
        val lastProtocol = if (lastHttp > lastHttps) lastHttp else lastHttps
        if (lastProtocol > 0) u = u.substring(lastProtocol)
        u = IP_HTTP_REGEX.replace(u, "$1/http")
        u = DOUBLE_PROTOCOL_REGEX.replace(u, "http$1://")
        u = u.replace(":://://", ":://")
        u = MULTI_SLASH_REGEX.replace(u, "/")
        return u.replace(" ", "%20").replace("&", "%26")
    }

    private val enrichmentSemaphore = Semaphore(5)

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (!useTmdb) return
        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return

        withTimeoutOrNull(10000) {
            coroutineScope {
                animes.take(40).map { anime ->
                    async {
                        enrichmentSemaphore.withPermit {
                            val tmdbCover = fetchTmdbImage(anime.title)
                            if (tmdbCover != null) anime.thumbnail_url = tmdbCover
                        }
                    }
                }.awaitAll()
            }
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).execute()
        return popularAnimeParse(response).also { enrichAnimes(it.animes) }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).execute()
        return latestUpdatesParse(response).also { enrichAnimes(it.animes) }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isEmpty()) return super.getSearchAnime(page, query, filters)

        val now = System.currentTimeMillis()
        if (searchCache.containsKey(query) && now - (cacheTime[query] ?: 0) < 1800000) {
            return AnimesPage(searchCache[query]!!, false)
        }

        val results = withContext(Dispatchers.IO) {
            val servers = listOf(
                "http://172.16.50.14" to "DHAKA-FLIX-14",
                "http://172.16.50.12" to "DHAKA-FLIX-12",
                "http://172.16.50.9" to "DHAKA-FLIX-9",
                "http://172.16.50.7" to "DHAKA-FLIX-7"
            )

            val deferredResults = servers.flatMap { (baseUrl, serverName) ->
                val paths = mutableListOf("/$serverName/")
                if (serverName == "DHAKA-FLIX-9") {
                    paths.add("/$serverName/Anime & Cartoon TV Series/")
                    paths.add("/$serverName/Anime & Cartoon Movies/")
                }
                if (serverName == "DHAKA-FLIX-12") {
                    paths.add("/$serverName/TV-WEB-Series/")
                    paths.add("/$serverName/Hindi Movies/")
                }
                
                paths.map { path ->
                    async {
                        try {
                            searchSingleServer(baseUrl, serverName, path, query)
                        } catch (e: Exception) {
                            emptyList<SAnime>()
                        }
                    }
                }
            }
            
            val allAnime = deferredResults.awaitAll().flatten().distinctBy { it.url }
            sortByTitle(allAnime, query)
        }

        if (results.isNotEmpty()) {
            searchCache[query] = results
            cacheTime[query] = now
        }

        return AnimesPage(results, false).also { enrichAnimes(it.animes) }
    }

    private fun searchSingleServer(baseUrl: String, serverName: String, path: String, query: String): List<SAnime> {
        val searchUrl = "$baseUrl/$serverName/"
        val jsonPayload = JSONObject().apply {
            put("action", "get")
            put("search", JSONObject().apply {
                put("href", path)
                put("pattern", query)
                put("ignorecase", true)
            })
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val fastClient = client.newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val response = try {
            fastClient.newCall(POST(searchUrl, headers, body)).execute()
        } catch (e: Exception) {
            return emptyList()
        }

        val bodyString = response.body?.string() ?: return emptyList()
        response.close()

        if (baseUrl.contains("172.16.50.7")) {
            val results = mutableListOf<SAnime>()
            Server7Parser.parseServer7Response(bodyString, baseUrl, serverName, results, query)
            return results.filter { diceCoefficient(it.title.lowercase(), query.lowercase()) > 0.15 }
        }

        val results = mutableListOf<SAnime>()
        try {
            val json = JSONObject(bodyString)
            val searchArr = json.optJSONArray("search") ?: return emptyList()

            for (i in 0 until searchArr.length()) {
                val item = searchArr.getJSONObject(i)
                val href = item.getString("href").replace('\\', '/')
                val size = item.opt("size")
                val isFolder = size == null || size == JSONObject.NULL || size == "null"

                var cleanHrefForTitle = href
                while (cleanHrefForTitle.endsWith("/")) cleanHrefForTitle = cleanHrefForTitle.dropLast(1)
                val rawTitle = cleanHrefForTitle.substringAfterLast("/")
                val title = try { URLDecoder.decode(rawTitle, "UTF-8").trim() } catch (e: Exception) { rawTitle.trim() }

                if (title.isBlank() || isIgnored(title, query)) continue

                if (diceCoefficient(title.lowercase(), query.lowercase()) < 0.15) continue

                val anime = SAnime.create().apply {
                    this.title = title
                    val finalHref = if (href.startsWith("/")) href else "/$href"
                    this.url = "$baseUrl$finalHref"
                    this.thumbnail_url = "" 
                }
                
                if (isFolder) results.add(0, anime) else results.add(anime)
            }
        } catch (e: Exception) {
        }
        return results
    }

    private fun isIgnored(text: String, query: String = ""):
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of", "JavaScript", "powered by", "_h5ai", "Subtitle", "Extras", "Sample", "Trailer")
        if (ignored.any { text.contains(it, ignoreCase = true) }) return true
        val uploaderTags = listOf("-LOKI", "-LOKiHD", "-TDoc", "-Tuna", "-PSA", "-Pahe", "-QxR", "-YIFY", "-RARBG")
        if (uploaderTags.any { text.contains(it, ignoreCase = true) }) {
             if (query.isNotEmpty() && uploaderTags.any { it.substring(1).equals(query, ignoreCase = true) }) return false
             return true
        }
        return false
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> {
        return list.sortedByDescending { diceCoefficient(it.title.lowercase(), query.lowercase()) }
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val n1 = s1.length
        val n2 = s2.length
        if (n1 == 0 || n2 == 0) return 0.0
        
        val bigrams1 = HashSet<String>()
        for (i in 0 until n1 - 1) bigrams1.add(s1.substring(i, i + 2))
        
        var intersection = 0
        for (i in 0 until n2 - 1) {
            val bigram = s2.substring(i, i + 2)
            if (bigrams1.contains(bigram)) intersection++
        }
        
        return (2.0 * intersection) / (n1 + n2 - 2)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%282025%29/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select("div.card")
        val animeList = if (cards.isNotEmpty()) {
            cards.mapNotNull { card ->
                val link = card.selectFirst("h5 a") ?: return@mapNotNull null
                SAnime.create().apply {
                    title = link.text().trim()
                    url = fixUrl(link.attr("abs:href"))
                    thumbnail_url = ""
                }
            }
        } else {
            document.select("a").mapNotNull { element ->
                val titleStr = element.text().trim()
                val href = element.attr("abs:href")
                if (titleStr.isNotEmpty() && !isIgnored(titleStr) && !href.contains("?") && !href.endsWith("../")) {
                    SAnime.create().apply {
                        title = if (titleStr.endsWith("/")) titleStr.dropLast(1) else titleStr
                        url = fixUrl(href)
                        thumbnail_url = ""
                    }
                } else null
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList {
        if (dynamicCategories.isEmpty()) fetchFilters()
        return Filters.getFilterList(dynamicCategories)
    }

    private var dynamicCategories: Array<String> = emptyArray()
    private fun fetchFilters() {
        thread {
            try {
                val doc = client.newCall(GET("$baseUrl/", headers)).execute().asJsoup()
                val sidebar = doc.select("ul.nav-sidebar a, .sidebar-menu a")
                dynamicCategories = sidebar.map { it.text().trim() }.filter { it.isNotEmpty() }.toTypedArray()
            } catch (e: Exception) {}
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(fixUrl(Filters.getUrl(query, filters)), headers)
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (useTmdb) {
            try {
                fetchTmdbImage(anime.title)?.let { anime.thumbnail_url = it }
            } catch (e: Exception) {}
        }
        return anime
    }

    private fun fetchTmdbImage(title: String): String? {
        val cacheKey = "tmdb_cover_${title.hashCode()}"
        val cached = preferences.getString(cacheKey, null)
        if (cached != null) return cached.takeIf { it.isNotEmpty() }
        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return null

        val cleanTitle = title.replace(Regex("(?i)Doraemon\s+The\s+Movie-?|\(.*?\)|\[.*?\]|\\d{3,4}p|576p|480p|720p|1080p|HDTC|HDRip|WEB-DL|BluRay|BRRip|Hindi Dubbed|Dual Audio|MSubs|ESub|4k|UltraHD|10bit|HEVC|x264|x265"), "").replace(Regex("[-_.]"), " ").trim()
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$cleanTitle".toHttpUrl()
        
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use {
                val results = JSONObject(it.body?.string()).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val path = results.getJSONObject(0).optString("poster_path")
                    if (path.isNotEmpty() && path != "null") {
                        val thumb = "https://image.tmdb.org/t/p/w500$path"
                        preferences.edit().putString(cacheKey, thumb).apply()
                        thumb
                    } else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(fixUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val html = document.select("script").html()
        val isMovie = html.contains("/m/lazyload/")
        
        return SAnime.create().apply {
            status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING
            genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
            description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""
            thumbnail_url = ""
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(45000) {
            val document = client.newCall(GET(fixUrl(anime.url), headers)).execute().asJsoup()
            val html = document.select("script").html()
            
            val episodes = when {
                html.contains("/m/lazyload/") -> getMovieMedia(document)
                else -> {
                    val extracted = extractEpisodes(document)
                    if (extracted.isNotEmpty()) sortEpisodes(extracted) else parseDirectoryRecursive(document)
                }
            }
            if (episodes.isEmpty()) throw Exception("No results found")
            episodes
        } ?: emptyList()
    }

    private fun extractEpisodes(document: Document): List<EpisodeData> {
        return document.select("div.card, div.episode-item, div.download-link").mapNotNull { element ->
            val titleElement = element.selectFirst("h5") ?: return@mapNotNull null
            val name = titleElement.ownText().split("&nbsp;").first().trim()
            val url = titleElement.selectFirst("a")?.attr("abs:href") ?: ""
            val quality = element.selectFirst("h5 .badge-fill")?.text()?.let {
                Regex("(\\d+\\.\\d+ [GM]B|\\d+ [GM]B).*", RegexOption.IGNORE_CASE).replace(it, "$1")
            } ?: ""
            val episodeName = element.selectFirst("h4")?.ownText()?.trim() ?: ""
            val size = element.selectFirst("h4 .badge-outline")?.text()?.trim() ?: ""
            
            if (name.isNotEmpty() && url.isNotEmpty()) {
                EpisodeData(name, url, quality, episodeName, size)
            } else {
                null
            }
        }
    }

    private fun getMovieMedia(document: Document): List<SEpisode> {
        val url = document.select("div.col-md-12 a.btn, .movie-buttons a, a[href*=/m/lazyload/], .download-link a").lastOrNull()?.attr("abs:href")?.replace(" ", "%20") ?: ""
        val quality = document.select(".badge-wrapper .badge-fill").lastOrNull()?.text()?.replace("|", "")?.trim() ?: ""
        return listOf(SEpisode.create().apply {
            this.url = url
            this.name = "Movie"
            this.episode_number = 1f
            this.scanlator = quality
        })
    }

    private val semaphore = Semaphore(5)
    private suspend fun parseDirectoryRecursive(document: Document): List<SEpisode> = parseDir(document.location(), 3, document)

    private suspend fun parseDir(url: String, depth: Int, initialDoc: Document? = null): List<SEpisode> {
        if (depth < 0) return emptyList()
        val doc = initialDoc ?: try { client.newCall(GET(url, headers)).execute().asJsoup() } catch (e: Exception) { return emptyList() }
        val currentHttpUrl = doc.location().toHttpUrlOrNull() ?: return emptyList()
        
        val fileEpisodes = mutableListOf<SEpisode>()
        val subDirs = mutableListOf<String>()

        doc.select("a").forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            if (href.contains("..") || href.startsWith("?") || isIgnored(text)) return@forEach
            val absUrl = currentHttpUrl.resolve(href)?.toString() ?: return@forEach
            
            if (isVideoFile(href)) {
                fileEpisodes.add(SEpisode.create().apply {
                    this.url = absUrl
                    this.name = try { URLDecoder.decode(text, "UTF-8") } catch(e:Exception) { text }
                    this.episode_number = -1f
                })
            } else if (href.endsWith("/") || absUrl.endsWith("/")) subDirs.add(absUrl)
        }

        if (fileEpisodes.isNotEmpty()) return fileEpisodes.sortedBy { it.name }.reversed()
        
        return coroutineScope {
            subDirs.map { async(Dispatchers.IO) { semaphore.withPermit { parseDir(it, depth - 1) } } }.awaitAll().flatten()
        }
    }

    private fun isVideoFile(href: String): Boolean {
        val h = href.lowercase()
        return listOf(".mkv", ".mp4", ".avi", ".ts", ".m4v", ".webm", ".mov").any { h.endsWith(it) || h.contains("$it?") }
    }

    private fun sortEpisodes(list: List<EpisodeData>): List<SEpisode> {
        return list.sortedWith(compareBy<EpisodeData> { 
            parseEpisodeNumber(it.seasonEpisode) 
        }.thenBy { it.seasonEpisode }).map {
            SEpisode.create().apply {
                url = it.videoUrl
                name = if (it.seasonEpisode.isNotEmpty()) "${it.seasonEpisode} - ${it.episodeName}".trim() else it.episodeName
                episode_number = parseEpisodeNumber(it.seasonEpisode)
                scanlator = "${it.quality} ${it.size}".trim()
            }
        }.reversed()
    }

    private fun parseEpisodeNumber(text: String): Float {
        return try {
            val number = Regex("(?i)(?:Episode|Ep|E|Vol)\.?\s*(\\d+(\\.\\d+)?)").find(text)?.groupValues?.get(1)
            number?.toFloatOrNull() ?: Regex("(\\d+(\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        } catch (e: Exception) { 0f }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = fixUrl(episode.url)
        val httpUrl = url.toHttpUrlOrNull()
        val referer = httpUrl?.let { "${it.scheme}://${it.host}/" } ?: "$baseUrl/"
        
        val videoHeaders = headersBuilder()
            .add("Referer", referer)
            .build()
            
        return listOf(Video(url, "Video", url, headers = videoHeaders))
    }
    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")
    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    data class EpisodeData(val seasonEpisode: String, val videoUrl: String, val quality: String, val episodeName: String, val size: String)

    companion object {
        private const val PREF_TMDB_API_KEY = "tmdb_api_key"
        private const val PREF_USE_TMDB_COVERS = "use_tmdb_covers"
        private val IP_HTTP_REGEX = Regex("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\s*http")
        private val DOUBLE_PROTOCOL_REGEX = Regex("http(s)?://http(s)?://")
        private val MULTI_SLASH_REGEX = Regex("(?<!:)/{2,}")
    }
}